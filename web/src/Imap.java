import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small read-only IMAP client: signs in, opens one folder with EXAMINE (read-only, so nothing is marked read, moved
 * or deleted), searches by sender and fetches whole messages with BODY.PEEK. Only what MailWatch needs.
 *
 * Always TLS (port 993) with the server's certificate and host name checked, except plainForTests.
 */
final class Imap implements Closeable {
    static final int TIMEOUT_MS = 30_000;
    static final int MAX_MESSAGE = 512 * 1024;
    private static final DateTimeFormatter SINCE = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH);

    /** Tests run a fake server without TLS. */
    static volatile boolean plainForTests;

    /** raw is null when the message was bigger than MAX_MESSAGE (a MariBank alert is about 25 KB). */
    record Message(long uid, byte[] raw) {}

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private int tag;
    private long uidValidity;

    Imap(String host, int port) throws IOException {
        Socket s;
        if (plainForTests) {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        } else {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            ssl.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            var params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS"); // checks the certificate is for this host
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            s = ssl;
        }
        s.setSoTimeout(TIMEOUT_MS);
        socket = s;
        in = new BufferedInputStream(s.getInputStream());
        out = new BufferedOutputStream(s.getOutputStream());
        String greeting = line();
        if (!greeting.startsWith("* OK")) throw new IOException("Mail server did not say hello");
    }

    void login(String user, String password) throws IOException {
        try {
            command("LOGIN " + quote(user) + " " + quote(password));
        } catch (IOException e) {
            throw new IOException("Sign-in failed. Check the email address and the app password.");
        }
    }

    /** Opens the folder read-only. Returns its UIDVALIDITY (when it changes, old UIDs mean nothing). */
    long examine(String folder) throws IOException {
        uidValidity = 0;
        for (String l : command("EXAMINE " + quote(folder))) {
            Matcher m = Pattern.compile("\\[UIDVALIDITY (\\d+)]").matcher(l);
            if (m.find()) uidValidity = Long.parseLong(m.group(1));
        }
        return uidValidity;
    }

    /** UIDs above afterUid of messages from sender (since the date, when afterUid is 0), lowest first. */
    List<Long> search(String sender, long afterUid, LocalDate since) throws IOException {
        String criteria = (afterUid > 0 ? "UID " + (afterUid + 1) + ":* " : "SINCE " + since.format(SINCE) + " ") + "FROM " + quote(sender);
        List<Long> uids = new ArrayList<>();
        for (String l : command("UID SEARCH " + criteria)) {
            if (!l.startsWith("* SEARCH")) continue;
            for (String n : l.substring("* SEARCH".length()).trim().split("\\s+")) {
                if (n.matches("\\d{1,15}") && Long.parseLong(n) > afterUid) uids.add(Long.parseLong(n));
            }
        }
        Collections.sort(uids);
        return uids;
    }

    /** The whole message, without marking it read. */
    Message fetch(long uid) throws IOException {
        String t = send("UID FETCH " + uid + " (UID BODY.PEEK[])");
        byte[] raw = null;
        boolean found = false;
        while (true) {
            String l = line();
            if (l.startsWith(t + " ")) {
                if (!l.startsWith(t + " OK")) throw new IOException("Mail server refused: " + l.substring(t.length()).trim());
                break;
            }
            Matcher m = Pattern.compile("\\{(\\d{1,9})}$").matcher(l);
            if (l.startsWith("* ") && l.contains("FETCH") && m.find()) {
                int size = Integer.parseInt(m.group(1));
                found = true;
                if (size > MAX_MESSAGE) {
                    in.skipNBytes(size);
                } else {
                    raw = in.readNBytes(size);
                    if (raw.length != size) throw new EOFException("Mail server closed the connection");
                }
            }
        }
        if (!found) throw new IOException("Message " + uid + " not found");
        return new Message(uid, raw);
    }

    @Override
    public void close() {
        try {
            send("LOGOUT");
        } catch (IOException ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- protocol

    private String send(String cmd) throws IOException {
        String t = "a" + (++tag);
        out.write((t + " " + cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return t;
    }

    /** Sends a command and returns the untagged lines; throws when the answer isn't OK. */
    private List<String> command(String cmd) throws IOException {
        String t = send(cmd);
        List<String> lines = new ArrayList<>();
        while (true) {
            String l = line();
            if (l.startsWith(t + " ")) {
                if (!l.startsWith(t + " OK")) throw new IOException("Mail server refused: " + l.substring(t.length()).trim());
                return lines;
            }
            Matcher m = Pattern.compile("\\{(\\d{1,9})}$").matcher(l);
            if (m.find()) in.skipNBytes(Long.parseLong(m.group(1))); // a literal we don't need
            lines.add(l);
        }
    }

    private String line() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 64 * 1024) throw new IOException("Mail server sent a line that is too long");
        }
        if (c == -1 && b.size() == 0) throw new EOFException("Mail server closed the connection");
        return b.toString(StandardCharsets.UTF_8);
    }

    /** IMAP quoted string. Line breaks are refused (they would start a new command). */
    static String quote(String s) {
        if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\0') >= 0) throw new IllegalArgumentException("Line breaks are not allowed");
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

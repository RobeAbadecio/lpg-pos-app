import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a MariBank "MariBank Transfer Notification" email (money received) into a Transfer.
 *
 * Trust: an email is only believed when the mailbox's own mail server (Gmail, "mx.google.com") says, in the topmost
 * Authentication-Results header, that MariBank's DKIM signature and DMARC passed. That header is added by Gmail when the
 * mail arrives, above anything the sender wrote, so a faked "From: alerts@maribank.com.ph" fails here. Gmail forwarding
 * keeps MariBank's signature, so this also works in a separate mailbox that gets the alerts forwarded.
 *
 * Check saved emails by hand:  java -cp build:lib/* BankMail file.eml ...
 */
final class BankMail {
    static final String SENDER = "alerts@maribank.com.ph";
    static final String DOMAIN = "maribank.com.ph";
    static final String TRUSTED_SERVER = "mx.google.com";

    /** One transfer into the account. amount in centavos; time as written in the email (Philippine time). */
    record Transfer(String messageId, String received, String time, int amount, String reference, String from) {}

    /** transfer is null when the email isn't a transfer we can read; problem says why it isn't trusted ("" = trusted). */
    record Result(Transfer transfer, String problem) {
        boolean trusted() { return transfer != null && problem.isEmpty(); }
    }

    private static final DateTimeFormatter EMAIL_TIME = DateTimeFormatter.ofPattern("d MMM yyyy H:mm", Locale.ENGLISH);
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern TIME = Pattern.compile("Transaction time:\\s*(\\d{1,2} [A-Za-z]{3} \\d{4} \\d{1,2}:\\d{2})");
    private static final Pattern FROM = Pattern.compile("Transfer from:\\s*(.{1,80}?)\\s*Transfer to:");
    private static final Pattern AMOUNT = Pattern.compile("Transfer amount:\\s*PHP\\s*([\\d,]{1,12}\\.\\d{2})\\b");
    private static final Pattern REFERENCE = Pattern.compile("Reference No\\.?:\\s*([A-Za-z0-9]{4,32})\\b");

    private BankMail() {}

    static Result read(byte[] raw) {
        String text = new String(raw, StandardCharsets.ISO_8859_1); // headers are ASCII; bodies are decoded per part
        int split = headerEnd(text);
        Map<String, List<String>> headers = headers(text.substring(0, split));
        String messageId = first(headers, "message-id").trim();
        String from = first(headers, "from");
        String subject = first(headers, "subject");

        String body = plain(text.substring(split), first(headers, "content-type"), first(headers, "content-transfer-encoding"));
        Matcher t = TIME.matcher(body), f = FROM.matcher(body), a = AMOUNT.matcher(body), r = REFERENCE.matcher(body);
        if (!body.contains("You have received a funds transfer") || !t.find() || !a.find() || !r.find()) {
            return new Result(null, "Not a MariBank money-received email");
        }
        Transfer tr;
        try {
            tr = new Transfer(messageId.isEmpty() ? null : messageId, received(first(headers, "date")),
                    LocalDateTime.parse(t.group(1), EMAIL_TIME).format(OUT),
                    new BigDecimal(a.group(1).replace(",", "")).movePointRight(2).intValueExact(),
                    r.group(1).toUpperCase(Locale.ROOT), f.find() ? f.group(1) : "");
        } catch (RuntimeException e) {
            return new Result(null, "Could not read the amount or time");
        }
        return new Result(tr, problem(headers, from, subject));
    }

    /** "" when the email really comes from MariBank, else the reason it isn't trusted. */
    private static String problem(Map<String, List<String>> headers, String from, String subject) {
        if (!address(from).equals(SENDER)) return "Not sent from " + SENDER;
        if (!subject.trim().equals("MariBank Transfer Notification")) return "Unexpected subject";
        List<String> auth = headers.getOrDefault("authentication-results", List.of());
        if (auth.isEmpty()) return "No sender check from the mail server";
        String top = auth.get(0).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (!top.startsWith(TRUSTED_SERVER + ";")) return "Sender check is not from " + TRUSTED_SERVER;
        if (!Pattern.compile("\\bdkim=pass [^;]*header\\.(i=@|d=)" + Pattern.quote(DOMAIN) + "(?=[\\s;]|$)").matcher(top).find()) return "MariBank signature (DKIM) did not pass";
        if (!Pattern.compile("\\bdmarc=pass [^;]*header\\.from=" + Pattern.quote(DOMAIN) + "(?=[\\s;]|$)").matcher(top).find()) return "DMARC did not pass";
        return "";
    }

    // ---------------------------------------------------------------- MIME

    private static int headerEnd(String s) {
        int i = s.indexOf("\r\n\r\n");
        if (i >= 0) return i + 4;
        i = s.indexOf("\n\n");
        return i >= 0 ? i + 2 : s.length();
    }

    /** Header names lower-cased, folded lines joined, in the order they appear (topmost first). */
    static Map<String, List<String>> headers(String block) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String name = null;
        StringBuilder value = new StringBuilder();
        for (String line : block.split("\r?\n")) {
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                if (name != null) value.append(' ').append(line.trim());
                continue;
            }
            if (name != null) out.computeIfAbsent(name, k -> new ArrayList<>()).add(value.toString());
            int colon = line.indexOf(':');
            name = colon > 0 ? line.substring(0, colon).trim().toLowerCase(Locale.ROOT) : null;
            value.setLength(0);
            if (name != null) value.append(line.substring(colon + 1).trim());
        }
        if (name != null) out.computeIfAbsent(name, k -> new ArrayList<>()).add(value.toString());
        return out;
    }

    private static String first(Map<String, List<String>> h, String name) {
        List<String> v = h.get(name);
        return v == null || v.isEmpty() ? "" : v.get(0);
    }

    /** The text of the first text/html or text/plain part, tags removed and spaces collapsed. */
    private static String plain(String body, String contentType, String encoding) {
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("multipart/")) {
            String boundary = param(contentType, "boundary");
            if (boundary.isEmpty()) return "";
            for (String part : body.split(Pattern.quote("--" + boundary))) {
                if (part.startsWith("--")) break;
                String p = part.replaceFirst("^\r?\n", "");
                int split = headerEnd(p);
                Map<String, List<String>> h = headers(p.substring(0, split));
                String inner = plain(p.substring(split), first(h, "content-type"), first(h, "content-transfer-encoding"));
                if (!inner.isEmpty()) return inner;
            }
            return "";
        }
        if (!type.isEmpty() && !type.startsWith("text/")) return "";
        byte[] bytes = decode(body, encoding.trim().toLowerCase(Locale.ROOT));
        Charset cs;
        try {
            cs = Charset.forName(param(contentType, "charset").isEmpty() ? "UTF-8" : param(contentType, "charset"));
        } catch (RuntimeException e) {
            cs = StandardCharsets.UTF_8;
        }
        String s = new String(bytes, cs);
        if (type.startsWith("text/html")) {
            s = s.replaceAll("(?is)<(style|script)[^>]*>.*?</\\1>", " ").replaceAll("(?s)<!--.*?-->", " ").replaceAll("<[^>]*>", " ");
            s = s.replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        }
        return s.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static byte[] decode(String body, String encoding) {
        if (encoding.equals("base64")) {
            try {
                return Base64.getMimeDecoder().decode(body.trim());
            } catch (IllegalArgumentException e) {
                return new byte[0];
            }
        }
        byte[] in = body.getBytes(StandardCharsets.ISO_8859_1);
        if (!encoding.equals("quoted-printable")) return in;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < in.length; i++) {
            if (in[i] != '=') {
                out.write(in[i]);
            } else if (i + 1 < in.length && (in[i + 1] == '\r' || in[i + 1] == '\n')) {
                i += in[i + 1] == '\r' && i + 2 < in.length && in[i + 2] == '\n' ? 2 : 1; // soft line break
            } else if (i + 2 < in.length && hex(in[i + 1]) >= 0 && hex(in[i + 2]) >= 0) {
                out.write(hex(in[i + 1]) * 16 + hex(in[i + 2]));
                i += 2;
            } else {
                out.write('=');
            }
        }
        return out.toByteArray();
    }

    private static int hex(byte b) {
        return Character.digit((char) b, 16);
    }

    private static String param(String header, String name) {
        Matcher m = Pattern.compile("(?i);\\s*" + name + "\\s*=\\s*(\"([^\"]*)\"|[^;\\s]+)").matcher(header);
        return !m.find() ? "" : m.group(2) != null ? m.group(2) : m.group(1);
    }

    /** "MariBank PH Alerts <alerts@maribank.com.ph>" → "alerts@maribank.com.ph" (the last <...>, not one in the name). */
    static String address(String from) {
        Matcher m = Pattern.compile("<([^<>]+)>\\s*$").matcher(from);
        return (m.find() ? m.group(1) : from).trim().toLowerCase(Locale.ROOT);
    }

    /** The Date header as local time "yyyy-MM-dd HH:mm:ss", or now when it can't be read. */
    private static String received(String date) {
        try {
            String d = date.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
            return java.time.ZonedDateTime.parse(d, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime().format(DataStore.TIME);
        } catch (RuntimeException e) {
            return Platform.now();
        }
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            Result r = read(Files.readAllBytes(Path.of(a)));
            Transfer t = r.transfer();
            System.out.println(a + ": " + (t == null ? "not read" : Billing.peso(t.amount()) + ", ref " + t.reference() + ", " + t.time()
                    + ", from " + t.from()) + " · " + (r.trusted() ? "TRUSTED" : "NOT TRUSTED: " + r.problem()));
        }
    }
}

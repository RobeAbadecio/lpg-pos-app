import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a Cloudflare quick tunnel (`cloudflared tunnel --url ...`) so the POS is reachable
 * from anywhere over HTTPS without router port-forwarding. Only the POS port is tunneled;
 * the admin dashboard stays bound to this Mac.
 *
 * While the link is switched on, the tunnel is restarted automatically if cloudflared exits
 * (network loss, crash), since nobody may be at this Mac to restart it. A restart gets a new
 * trycloudflare.com address; each new address is reported through onNewUrl.
 */
final class Tunnel {
    private static final Pattern URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    private final int targetPort;
    private final Consumer<String> onNewUrl;
    private Process process;
    private volatile boolean wanted;
    private volatile String status = "stopped";   // stopped | starting | online | reconnecting | error
    private volatile String url;
    private volatile String error;
    private int failures;
    private final Deque<String> output = new ArrayDeque<>();

    Tunnel(int targetPort, Consumer<String> onNewUrl) {
        this.targetPort = targetPort;
        this.onNewUrl = onNewUrl;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    static String binary() {
        List<String> candidates = new ArrayList<>(List.of("/opt/homebrew/bin/cloudflared", "/usr/local/bin/cloudflared"));
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(File.pathSeparator)) candidates.add(d + "/cloudflared");
        for (String c : candidates) if (Files.isExecutable(Path.of(c))) return c;
        return null;
    }

    synchronized void start() throws IOException {
        wanted = true;
        failures = 0;
        launch();
    }

    private synchronized void launch() throws IOException {
        if (!wanted || (process != null && process.isAlive())) return;
        String bin = binary();
        if (bin == null) {
            wanted = false;
            status = "error";
            error = "cloudflared is not installed";
            throw new IOException(error);
        }
        synchronized (output) {
            output.clear();
        }
        url = null;
        error = null;
        status = "starting";
        ProcessBuilder pb = new ProcessBuilder(bin, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:" + targetPort);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        process = p;
        Thread reader = new Thread(() -> read(p), "cloudflared-output");
        reader.setDaemon(true);
        reader.start();
    }

    private void read(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean registered = false;
            while ((line = r.readLine()) != null) {
                synchronized (output) {
                    output.addLast(line);
                    while (output.size() > 40) output.removeFirst();
                }
                Matcher m = URL.matcher(line);
                if (url == null && m.find()) url = m.group();
                if (line.contains("Registered tunnel connection")) registered = true;
                if (registered && url != null && "starting".equals(status)) {
                    status = "online";
                    synchronized (this) {
                        failures = 0;
                    }
                    onNewUrl.accept(url);
                }
            }
        } catch (IOException ignored) {
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (this) {
            if (process != p) return;
            process = null;
            url = null;
            if (!wanted) {
                status = "stopped";
                return;
            }
            // Back off 5s, 10s, 20s ... up to 2 minutes between attempts.
            long delay = Math.min(120_000L, 5_000L << Math.min(failures, 5));
            failures++;
            status = "reconnecting";
            error = "cloudflared exited (code " + code + "); retrying in " + delay / 1000 + "s";
            Thread retry = new Thread(() -> {
                try {
                    Thread.sleep(delay);
                    launch();
                } catch (Exception e) {
                    status = "error";
                    error = e.getMessage();
                }
            }, "cloudflared-retry");
            retry.setDaemon(true);
            retry.start();
        }
    }

    synchronized void stop() {
        wanted = false;
        if (process != null && process.isAlive()) {
            status = "stopping";
            process.destroy();
        } else {
            status = "stopped";
        }
    }

    Map<String, Object> info() {
        List<String> log;
        synchronized (output) {
            log = new ArrayList<>(output);
        }
        boolean showLog = "error".equals(status) || "reconnecting".equals(status);
        return Json.obj(
                "installed", binary() != null,
                "status", status,
                "url", url,
                "error", error,
                "log", showLog ? log.subList(Math.max(0, log.size() - 8), log.size()) : List.of());
    }
}

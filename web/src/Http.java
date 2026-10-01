import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Small request/response helpers on top of the JDK's built-in HttpServer. */
final class Http {
    private Http() {}

    static final class Error extends RuntimeException {
        final int status;

        Error(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    interface Handler {
        void handle(Req r) throws IOException;
    }

    static HttpHandler wrap(Handler h) {
        return ex -> {
            Req r = new Req(ex);
            try {
                h.handle(r);
            } catch (Error e) {
                r.json(e.status, Json.obj("error", e.getMessage()));
            } catch (DataStore.StockException e) {
                r.json(409, Json.obj("error", e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    r.json(500, Json.obj("error", "Server error: " + e.getMessage()));
                } catch (IOException ignored) {
                }
            } finally {
                ex.close();
            }
        };
    }

    static final class Req {
        private static final int MAX_BODY = 64 * 1024;
        final HttpExchange ex;
        final String method;
        final String path;
        private Map<String, String> query;
        private Map<String, String> form;
        private boolean sent;

        Req(HttpExchange ex) {
            this.ex = ex;
            this.method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            this.path = ex.getRequestURI().getPath();
        }

        String header(String name) {
            return ex.getRequestHeaders().getFirst(name);
        }

        Map<String, String> query() {
            if (query == null) query = parseForm(ex.getRequestURI().getRawQuery());
            return query;
        }

        Map<String, String> form() throws IOException {
            if (form == null) {
                byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
                if (body.length > MAX_BODY) throw new Error(413, "Request too large");
                form = parseForm(new String(body, StandardCharsets.UTF_8));
            }
            return form;
        }

        /** Trimmed single-line form value ("" when absent). */
        String param(String name) throws IOException {
            String v = form().get(name);
            return v == null ? "" : v.replaceAll("[\\r\\n\\t]+", " ").trim();
        }

        String cookie(String name) {
            List<String> headers = ex.getRequestHeaders().get("Cookie");
            if (headers == null) return null;
            for (String h : headers) {
                for (String part : h.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq > 0 && part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
                }
            }
            return null;
        }

        boolean fromLoopback() {
            return ex.getRemoteAddress().getAddress().isLoopbackAddress();
        }

        /** cloudflared connects from 127.0.0.1 and adds CF-Connecting-IP with the real client. */
        boolean viaTunnel() {
            return fromLoopback() && header("CF-Connecting-IP") != null;
        }

        String clientIp() {
            if (viaTunnel()) return header("CF-Connecting-IP").trim();
            if (fromLoopback()) return "localhost";
            return ex.getRemoteAddress().getAddress().getHostAddress();
        }

        String via() {
            if (viaTunnel()) return "Internet";
            return fromLoopback() ? "This Mac" : "Local network";
        }

        boolean secure() {
            return "https".equalsIgnoreCase(header("X-Forwarded-Proto"));
        }

        void addHeader(String name, String value) {
            ex.getResponseHeaders().add(name, value);
        }

        void json(int status, Object body) throws IOException {
            send(status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8), "no-store");
        }

        void file(Path file) throws IOException {
            send(200, mime(file), Files.readAllBytes(file), "no-cache");
        }

        private void send(int status, String type, byte[] bytes, String cache) throws IOException {
            if (sent) return;
            sent = true;
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", type);
            h.set("Cache-Control", cache);
            h.set("X-Content-Type-Options", "nosniff");
            h.set("X-Frame-Options", "DENY");
            h.set("Referrer-Policy", "no-referrer");
            h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                    + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            boolean noBody = method.equals("HEAD") || bytes.length == 0;
            ex.sendResponseHeaders(status, noBody ? -1 : bytes.length);
            if (!noBody) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }

    /** Serves files from appRoot, plus /shared/* from sharedRoot. */
    static void serveStatic(Req r, Path appRoot, Path sharedRoot) throws IOException {
        if (!r.method.equals("GET") && !r.method.equals("HEAD")) throw new Error(405, "Method not allowed");
        Path base = appRoot;
        String rel = r.path.equals("/") ? "index.html" : r.path.substring(1);
        if (r.path.startsWith("/shared/")) {
            base = sharedRoot;
            rel = r.path.substring("/shared/".length());
        }
        Path file = base.resolve(rel).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) throw new Error(404, "Not found");
        r.file(file);
    }

    /** Mutating API calls must carry this header; browsers won't add it cross-site without a CORS preflight. */
    static void requireCsrfHeader(Req r) {
        if (!r.method.equals("GET") && !r.method.equals("HEAD") && !"1".equals(r.header("X-LPG"))) {
            throw new Error(403, "Missing request header");
        }
    }

    static Map<String, String> parseForm(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String mime(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".json") || n.endsWith(".webmanifest")) return "application/json";
        return "application/octet-stream";
    }
}

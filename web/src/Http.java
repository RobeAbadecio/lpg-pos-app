import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

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
            } catch (DataStore.Conflict e) {
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
        private int maxBody = 64 * 1024;
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
                byte[] body = ex.getRequestBody().readNBytes(maxBody + 1);
                if (body.length > maxBody) throw new Error(413, "Request too large");
                form = parseForm(new String(body, StandardCharsets.UTF_8));
            }
            return form;
        }

        /** For the few calls that carry a picture: allows a body of up to 4 MB. Call before form(). */
        void allowLargeBody() {
            maxBody = 4 * 1024 * 1024;
        }

        /** A private picture (receipt, QR code): never cached. */
        void image(String type, byte[] bytes) throws IOException {
            send(200, type, bytes, "private, no-store");
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

        /** 304 for a client that already has this version of the data. */
        boolean notModified(String etag) throws IOException {
            if (!etag.equals(header("If-None-Match"))) return false;
            sent = true;
            ex.getResponseHeaders().set("ETag", etag);
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(304, -1);
            return true;
        }

        void file(Path file) throws IOException {
            send(200, mime(file), Files.readAllBytes(file), "no-cache");
        }

        /** While held, a response is kept back (an edit sends it only once the whole change is saved). */
        private Object[] held;
        private boolean holding;

        void hold() { holding = true; }

        /** Sends the response kept back, if any. */
        void release() throws IOException {
            holding = false;
            if (held != null) send((int) held[0], (String) held[1], (byte[]) held[2], (String) held[3]);
            held = null;
        }

        /** Forgets the response kept back, so an error can be sent instead. */
        void discardHeld() {
            holding = false;
            held = null;
        }

        private void send(int status, String type, byte[] bytes, String cache) throws IOException {
            if (holding) {
                held = new Object[]{status, type, bytes, cache};
                return;
            }
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
            String accept = header("Accept-Encoding");
            if (!noBody && bytes.length > 1024 && accept != null && accept.contains("gzip") && !type.equals("image/png") && !type.equals("image/jpeg")) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(bytes.length / 4);
                try (GZIPOutputStream gz = new GZIPOutputStream(buf)) {
                    gz.write(bytes);
                }
                bytes = buf.toByteArray();
                h.set("Content-Encoding", "gzip");
                h.set("Vary", "Accept-Encoding");
            }
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

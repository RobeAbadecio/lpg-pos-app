import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Admin dashboard, bound to 127.0.0.1 only, so it is reachable from this Mac and nowhere
 * else (the tunnel forwards the POS port only). No password: being on this Mac is the
 * credential. The Host check blocks DNS-rebinding pages from reaching it via the browser.
 */
final class AdminApi implements Http.Handler {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,32}");

    private final DataStore store;
    private final Auth auth;
    private final ActivityLog log;
    private final Tunnel tunnel;
    private final Path appRoot, sharedRoot;
    private final int posPort, adminPort;
    private final long startedAt = System.currentTimeMillis();

    AdminApi(DataStore store, Auth auth, ActivityLog log, Tunnel tunnel, Path appRoot, Path sharedRoot, int posPort, int adminPort) {
        this.store = store;
        this.auth = auth;
        this.log = log;
        this.tunnel = tunnel;
        this.appRoot = appRoot;
        this.sharedRoot = sharedRoot;
        this.posPort = posPort;
        this.adminPort = adminPort;
    }

    @Override
    public void handle(Http.Req r) throws IOException {
        String host = Optional.ofNullable(r.header("Host")).orElse("").toLowerCase(Locale.ROOT);
        if (!r.fromLoopback() || !(host.equals("localhost:" + adminPort) || host.equals("127.0.0.1:" + adminPort)
                || host.equals("[::1]:" + adminPort))) {
            throw new Http.Error(403, "The admin dashboard only opens on this Mac at http://localhost:" + adminPort);
        }
        if (!r.path.startsWith("/api/")) {
            Http.serveStatic(r, appRoot, sharedRoot);
            return;
        }
        Http.requireCsrfHeader(r);

        String[] seg = r.path.substring("/api/".length()).split("/");
        String id = seg.length > 1 ? seg[1] : null;
        String action = seg.length > 2 ? seg[2] : null;
        String route = r.method + " " + seg[0] + (id == null ? "" : "/:id") + (action == null ? "" : "/" + action);
        switch (route) {
            case "GET overview" -> r.json(200, overview());
            case "GET stats" -> r.json(200, Stats.compute(store, r.query().getOrDefault("range", "30")));

            case "POST users" -> {
                String username = r.param("username").toLowerCase(Locale.ROOT);
                if (!USERNAME.matcher(username).matches()) {
                    throw new Http.Error(400, "Username: 3–32 characters, letters, numbers, dot, dash or underscore");
                }
                String password = password(r);
                String salt = Auth.newSalt();
                if (!store.addUser(username, salt, Auth.hash(password, salt))) throw new Http.Error(409, "That username is taken");
                log.add("admin", "this Mac", "Created staff account " + username);
                r.json(201, Json.obj("ok", true));
            }
            case "PUT users/:id/password" -> {
                String password = password(r);
                String salt = Auth.newSalt();
                if (!store.setPassword(id, salt, Auth.hash(password, salt))) throw new Http.Error(404, "No such account");
                auth.revokeUser(id, null);
                log.add("admin", "this Mac", "Reset password for " + id);
                r.json(200, Json.obj("ok", true));
            }
            case "DELETE users/:id" -> {
                if (!store.deleteUser(id)) throw new Http.Error(404, "No such account");
                auth.revokeUser(id, null);
                log.add("admin", "this Mac", "Deleted staff account " + id);
                r.json(200, Json.obj("ok", true));
            }
            case "GET password" -> r.json(200, Json.obj("password", Auth.randomPassword()));
            case "DELETE sessions/:id" -> {
                Auth.Session s = auth.revokeById(id);
                if (s == null) throw new Http.Error(404, "Session already ended");
                log.add("admin", "this Mac", "Signed out " + s.username + " (" + s.ip + ")");
                r.json(200, Json.obj("ok", true));
            }
            case "POST tunnel/:id" -> {
                if ("start".equals(id)) {
                    tunnel.start();
                    log.add("admin", "this Mac", "Started public link");
                } else if ("stop".equals(id)) {
                    tunnel.stop();
                    log.add("admin", "this Mac", "Stopped public link");
                } else {
                    throw new Http.Error(404, "Unknown endpoint");
                }
                r.json(200, tunnel.info());
            }
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
    }

    private static String password(Http.Req r) throws IOException {
        String p = r.form().getOrDefault("password", "");
        if (p.length() < 8) throw new Http.Error(400, "Password must be at least 8 characters");
        if (p.length() > 200) throw new Http.Error(400, "Password is too long");
        return p;
    }

    private Map<String, Object> overview() {
        List<Auth.Session> sessions = auth.active();
        Set<String> online = new HashSet<>();
        List<Object> sessionOut = new ArrayList<>();
        for (Auth.Session s : sessions) {
            online.add(s.username);
            sessionOut.add(Json.obj("id", s.id, "username", s.username, "ip", s.ip, "via", s.via,
                    "device", device(s.userAgent), "since", iso(s.created), "lastSeen", iso(s.lastSeen)));
        }
        List<Object> users = new ArrayList<>();
        for (DataStore.User u : store.users()) {
            users.add(Json.obj("username", u.username(), "created", u.created(), "online", online.contains(u.username())));
        }
        List<Object> activity = new ArrayList<>();
        for (ActivityLog.Entry e : log.latest(60)) {
            activity.add(Json.obj("time", e.time(), "actor", e.actor(), "ip", e.ip(), "message", e.message()));
        }
        List<String> lan = new ArrayList<>();
        for (String ip : lanAddresses()) lan.add("http://" + ip + ":" + posPort);
        return Json.obj(
                "server", Json.obj(
                        "startedAt", iso(startedAt),
                        "uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000,
                        "localUrl", "http://localhost:" + posPort,
                        "lanUrls", lan,
                        "dataDir", store.dir().toString(),
                        "java", System.getProperty("java.version")),
                "tunnel", tunnel.info(),
                "inventory", Stats.inventory(store),
                "receivables", Stats.receivables(store),
                "sessions", sessionOut,
                "users", users,
                "activity", activity);
    }

    private static String iso(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).withNano(0).format(DataStore.TIME);
    }

    private static String device(String ua) {
        String os = ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad" : ua.contains("Android") ? "Android"
                : ua.contains("Mac OS X") ? "Mac" : ua.contains("Windows") ? "Windows" : ua.contains("Linux") ? "Linux" : "Device";
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("Chrome/") ? "Chrome" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Safari/") ? "Safari" : "";
        return browser.isEmpty() ? os : browser + " on " + os;
    }

    static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
        }
        return out;
    }
}

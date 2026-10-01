import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * LPG POS web server.
 *
 *   POS (staff web app)   http://<this-mac>:8080   all interfaces, staff sign-in required
 *   Admin dashboard       http://localhost:8090    this Mac only
 *
 * Options (environment variables): LPG_PORT, LPG_ADMIN_PORT, LPG_DATA_DIR.
 * LPG_AUTO_LOGIN=<username> skips the POS sign-in (demo only; refused for the real data folder).
 * Pass --tunnel to open the public Cloudflare link at startup.
 */
public final class WebServer {
    public static void main(String[] args) throws Exception {
        int port = intEnv("LPG_PORT", 8080);
        int adminPort = intEnv("LPG_ADMIN_PORT", 8090);
        Path webDir = Path.of(System.getProperty("lpg.web", ".")).toAbsolutePath().normalize();
        Path dataDir = Path.of(System.getenv().getOrDefault("LPG_DATA_DIR",
                System.getProperty("user.home") + "/POSSystemData")).toAbsolutePath().normalize();
        Path publicDir = webDir.resolve("public");
        Path shared = publicDir.resolve("shared");

        DataStore store = new DataStore(dataDir);
        String autoUser = System.getenv("LPG_AUTO_LOGIN");
        Path realData = Path.of(System.getProperty("user.home"), "POSSystemData").toAbsolutePath().normalize();
        if (autoUser != null && (dataDir.equals(realData) || store.user(autoUser) == null)) {
            System.out.println("  LPG_AUTO_LOGIN ignored: only allowed for demo data with an existing user.");
            autoUser = null;
        }
        Auth auth = new Auth();
        ActivityLog log = new ActivityLog(dataDir);
        Tunnel tunnel = new Tunnel(port, url -> {
            log.add("admin", "this Mac", "Public link is now " + url);
            System.out.println("  Public link (share with staff): " + url);
        });

        HttpServer pos = HttpServer.create(new InetSocketAddress(port), 0);
        pos.createContext("/", Http.wrap(new PosApi(store, auth, log, publicDir.resolve("app"), shared, port, autoUser)));
        pos.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        HttpServer admin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), adminPort), 0);
        admin.createContext("/", Http.wrap(new AdminApi(store, auth, log, tunnel, publicDir.resolve("admin"), shared, port, adminPort)));
        admin.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        pos.start();
        admin.start();
        log.add("admin", "this Mac", "Server started");

        System.out.println();
        System.out.println("  LPG POS web server is running");
        System.out.println("  ─────────────────────────────────────────────");
        System.out.println("  Admin dashboard (this Mac):  http://localhost:" + adminPort);
        System.out.println("  POS on this Mac:             http://localhost:" + port);
        List<String> lan = AdminApi.lanAddresses();
        for (String ip : lan) System.out.println("  POS on your network:         http://" + ip + ":" + port);
        System.out.println("  Data folder:                 " + dataDir);
        if (autoUser != null) System.out.println("  DEMO: POS opens without sign-in, as " + autoUser);
        if (store.users().isEmpty()) {
            System.out.println();
            System.out.println("  No staff accounts yet. Open the admin dashboard to create one.");
        }
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();

        if (List.of(args).contains("--tunnel")) {
            try {
                tunnel.start();
                log.add("admin", "this Mac", "Started public link");
            } catch (Exception e) {
                System.out.println("  Public link not started: " + e.getMessage());
            }
        }
    }

    private static int intEnv(String name, int fallback) {
        try {
            return Integer.parseInt(System.getenv(name));
        } catch (Exception e) {
            return fallback;
        }
    }
}

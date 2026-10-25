import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LPG POS web server.
 *
 *   POS (staff web app)   http://127.0.0.1:8080   loopback only; remote staff use Cloudflare
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
                webDir.resolve("../POSSystemData").normalize().toString())).toAbsolutePath().normalize();
        Path publicDir = webDir.resolve("public");
        Path shared = publicDir.resolve("shared");

        Files.createDirectories(dataDir);
        Platform platform = new Platform(dataDir.resolve("platform.db"));
        Tenants tenants = new Tenants(dataDir, platform);
        Billing billing = new Billing(platform);
        MailWatch mail = new MailWatch(platform, billing);
        long migrated = tenants.migrateLegacy(dataDir, System.getenv().getOrDefault("LPG_STORE_NAME", "My LPG store"));
        if (migrated > 0) System.out.println("  Copied the existing store data into business " + migrated + " (originals left in place).");

        String autoUser = System.getenv("LPG_AUTO_LOGIN");
        Path realData = Path.of(System.getProperty("user.home"), "POSSystemData").toAbsolutePath().normalize();
        Platform.Account auto = autoUser == null ? null : platform.account(autoUser);
        if (autoUser != null && (dataDir.equals(realData) || dataDir.equals(webDir.resolve("../POSSystemData").normalize()) || auto == null || !auto.active())) {
            System.out.println("  LPG_AUTO_LOGIN ignored: only allowed for demo data with an existing user.");
            autoUser = null;
        }
        Auth auth = new Auth();
        Tunnel tunnel = new Tunnel(port, url -> {
            platform.audit("system", null, "Public link is now " + url, "");
            System.out.println("  Public link (share with staff): " + url);
        });

        PosGate gate = new PosGate(platform, billing, tenants, auth, publicDir.resolve("app"), shared, port, autoUser);
        HttpServer pos = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        pos.createContext("/", Http.wrap(gate));
        pos.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        HttpServer admin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), adminPort), 0);
        admin.createContext("/", Http.wrap(new AdminApi(platform, billing, mail, tenants, gate, auth, tunnel, publicDir.resolve("admin"), shared, port, adminPort)));
        admin.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Backup and this month's invoices at start-up, then once a day; a failure is logged and never stops the POS.
        ScheduledExecutorService daily = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "background");
            t.setDaemon(true);
            return t;
        });
        daily.scheduleAtFixedRate(() -> {
            try {
                Backups.run(platform, tenants);
            } catch (Exception e) {
                System.err.println("  Backup failed: " + e.getMessage());
            }
            try {
                billing.ensureInvoices();
            } catch (Exception e) {
                System.err.println("  Invoices failed: " + e.getMessage());
            }
        }, 0, 24, TimeUnit.HOURS);
        // MariBank emails: read the mailbox every few minutes when it's turned on (the first look waits a minute).
        daily.scheduleWithFixedDelay(mail::tick, 1, MailWatch.POLL_MINUTES, TimeUnit.MINUTES);

        pos.start();
        admin.start();
        platform.audit("system", null, "Server started", "");

        System.out.println();
        System.out.println("  LPG POS web server is running");
        System.out.println("  ─────────────────────────────────────────────");
        System.out.println("  Admin dashboard (this Mac):  http://localhost:" + adminPort);
        System.out.println("  POS on this Mac:             http://localhost:" + port);
        System.out.println("  Remote staff access:         Cloudflare link only (LAN access disabled)");
        System.out.println("  Data folder:                 " + dataDir);
        if (autoUser != null) System.out.println("  DEMO: POS opens without sign-in, as " + autoUser);
        if (platform.setting("admin_hash") == null) {
            System.out.println();
            System.out.println("  Open the admin dashboard to set the platform admin password.");
        }
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();

        if (List.of(args).contains("--tunnel")) {
            try {
                tunnel.start();
                platform.audit("system", null, "Started public link", "");
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

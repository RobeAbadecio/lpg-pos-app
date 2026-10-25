import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * End-to-end check of the multi-business platform over real HTTP, on a throw-away data folder:
 * single-store migration, platform admin sign-in, business sign-up and approval, staff join and approval,
 * isolation between businesses, disabling and suspending, one owner per business, backup and restore.
 *
 *   javac -d /tmp/lpg-check -cp web/lib/* web/src/*.java web/tests/PlatformCheck.java
 *   java -cp "/tmp/lpg-check:web/lib/*" PlatformCheck
 */
public class PlatformCheck {
    static int pass;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
        pass++;
    }

    /** One browser: its own cookie jar. */
    static final class Browser {
        final HttpClient client = HttpClient.newHttpClient();
        final String base;
        final Map<String, String> cookies = new ConcurrentHashMap<>();

        Browser(String base) { this.base = base; }

        HttpResponse<String> send(String method, String path, String... form) throws Exception {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i + 1 < form.length; i += 2) {
                if (body.length() > 0) body.append('&');
                body.append(URLEncoder.encode(form[i], StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(form[i + 1], StandardCharsets.UTF_8));
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("X-LPG", "1")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Cookie", cookies.isEmpty() ? "none=1" : String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()))
                    .method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
            HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            for (String c : res.headers().allValues("Set-Cookie")) {
                String pair = c.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (pair.substring(eq + 1).isEmpty()) cookies.remove(pair.substring(0, eq));
                else cookies.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            return res;
        }

        int status(String method, String path, String... form) throws Exception { return send(method, path, form).statusCode(); }
    }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("lpg-platform-check-");
        // ---- a single-store data folder as it is today
        String salt = Auth.newSalt();
        Files.writeString(data.resolve("WebUsers.csv"), "domingo," + salt + "," + Auth.hash("domingo-pass", salt) + ",2026-01-01 08:00:00,1\n");
        Files.writeString(data.resolve("Customers.csv"), "1,Old,Customer,0917,Town\n");

        Platform platform = new Platform(data.resolve("platform.db"));
        Tenants tenants = new Tenants(data, platform);
        long first = tenants.migrateLegacy(data, "Robe's LPG");
        check(first == 1, "existing data becomes business 1");
        check(Files.exists(data.resolve("tenants/1/Customers.csv")) && Files.exists(data.resolve("Customers.csv")), "data copied, originals kept");
        check(tenants.migrateLegacy(data, "x") == 0, "migration runs once");
        check(platform.account("domingo") != null && platform.account("domingo").active(), "old staff account imported");

        Auth auth = new Auth();
        Path pub = Path.of("web/public");
        Billing billing = new Billing(platform);
        PosGate gate = new PosGate(platform, billing, tenants, auth, pub.resolve("app"), pub.resolve("shared"), 1, null);
        HttpServer pos = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pos.createContext("/", Http.wrap(gate));
        pos.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        pos.start();
        HttpServer adm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int adminPort = adm.getAddress().getPort();
        adm.createContext("/", Http.wrap(new AdminApi(platform, billing, new MailWatch(platform, billing), tenants, gate, auth, new Tunnel(1, u -> {}), pub.resolve("admin"), pub.resolve("shared"), 1, adminPort)));
        adm.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        adm.start();
        String posUrl = "http://localhost:" + pos.getAddress().getPort();
        try {
            run(posUrl, "http://localhost:" + adminPort, platform, tenants, data);
        } finally {
            pos.stop(0);
            adm.stop(0);
        }
        System.out.println("PASS: " + pass + " platform checks (migration, admin sign-in, approvals, isolation, roles, suspension, backup/restore)");
    }

    static void run(String posUrl, String adminUrl, Platform platform, Tenants tenants, Path data) throws Exception {
        // ---- old staff still sign in, and see their data
        Browser domingo = new Browser(posUrl);
        check(domingo.status("POST", "/api/login", "username", "domingo", "password", "domingo-pass") == 200, "migrated staff sign in");
        String d0 = domingo.send("GET", "/api/data").body();
        check(d0.contains("Old"), "migrated staff see their customers: " + d0.substring(0, Math.min(300, d0.length())));

        // ---- platform admin
        Browser admin = new Browser(adminUrl);
        check(admin.status("GET", "/api/platform") == 401, "admin pages need sign-in");
        check(admin.send("GET", "/api/admin/state").body().contains("\"setup\":true"), "first visit asks to set a password");
        check(admin.status("POST", "/api/admin/setup", "password", "short") == 400, "short admin password refused");
        check(admin.status("POST", "/api/admin/setup", "password", "admin-secret-1") == 200, "admin password set");
        check(new Browser(adminUrl).status("POST", "/api/admin/setup", "password", "attacker-pass") == 409, "admin password can't be set twice");
        Browser stranger = new Browser(adminUrl);
        check(stranger.status("POST", "/api/admin/login", "password", "wrong-pass") == 401, "wrong admin password refused");
        check(stranger.status("GET", "/api/overview") == 401, "store pages need admin sign-in");
        check(admin.status("GET", "/api/overview") == 200, "admin sees the store dashboard");
        Browser evil = new Browser(adminUrl.replace("localhost", "127.0.0.1"));
        HttpResponse<String> rebind = evil.client.send(HttpRequest.newBuilder(URI.create(adminUrl + "/api/platform"))
                .header("CF-Connecting-IP", "203.0.113.9").build(), HttpResponse.BodyHandlers.ofString());
        check(rebind.statusCode() == 403, "admin refuses tunnelled requests");

        // ---- a second business signs up
        Browser owner2 = new Browser(posUrl);
        check(owner2.status("POST", "/api/register", "storeName", "Gas Two", "contactName", "Ana Cruz", "phone", "0917 123 4567",
                "username", "ana", "password", "ana-pass-123") == 201, "business registers");
        check(new Browser(posUrl).status("POST", "/api/register", "storeName", "Gas Three", "contactName", "X", "phone", "09171234567",
                "username", "ana", "password", "other-pass-1") == 400, "short contact name refused");
        check(new Browser(posUrl).status("POST", "/api/register", "storeName", "Gas Three", "contactName", "Xian", "phone", "09171234567",
                "username", "domingo", "password", "other-pass-1") == 409, "username taken across businesses");
        HttpResponse<String> waiting = owner2.send("POST", "/api/login", "username", "ana", "password", "ana-pass-123");
        check(waiting.statusCode() == 403 && waiting.body().contains("waiting for approval"), "pending owner can't sign in yet");
        check(owner2.status("POST", "/api/login", "username", "ana", "password", "bad-pass-123") == 401, "wrong password says nothing about status");
        long biz2 = platform.businesses().stream().filter(b -> b.name().equals("Gas Two")).findFirst().orElseThrow().id();
        check(admin.status("POST", "/api/businesses/" + biz2 + "/status", "status", "active") == 200, "admin approves");
        check(owner2.status("POST", "/api/login", "username", "ana", "password", "ana-pass-123") == 200, "approved owner signs in");
        check(owner2.send("GET", "/api/me").body().contains("\"role\":\"owner\""), "owner role");

        // ---- isolation
        check(owner2.status("POST", "/api/customers", "firstName", "Bea", "lastName", "Two", "contact", "", "address", "") == 201, "owner adds a customer");
        String data2 = owner2.send("GET", "/api/data").body(), data1 = domingo.send("GET", "/api/data").body();
        check(data2.contains("Bea") && !data2.contains("Old"), "business 2 sees only its own customers");
        check(data1.contains("Old") && !data1.contains("Bea"), "business 1 sees only its own customers");
        check(owner2.send("GET", "/api/data?business=1").body().equals(data2), "a business id in the request is ignored");
        check(domingo.status("GET", "/api/team") == 403, "staff can't open the Team page");

        // ---- staff join with the store code, owner approves
        String code = platform.business(biz2).code();
        Browser ben = new Browser(posUrl);
        check(ben.status("POST", "/api/join", "code", "WRONG1", "fullName", "Ben", "username", "ben", "password", "ben-pass-123") == 400, "wrong store code refused");
        check(ben.status("POST", "/api/join", "code", code, "fullName", "Ben Diaz", "username", "ben", "password", "ben-pass-123") == 201, "staff asks to join");
        check(ben.status("POST", "/api/login", "username", "ben", "password", "ben-pass-123") == 403, "pending staff can't sign in");
        check(domingo.status("POST", "/api/team/ben/approve") == 403, "other business's staff can't approve");
        check(owner2.status("POST", "/api/team/ben/approve") == 200, "owner approves staff");
        check(ben.status("POST", "/api/login", "username", "ben", "password", "ben-pass-123") == 200, "approved staff sign in");
        check(ben.status("POST", "/api/team/ben/dashboard", "on", "1") == 403, "staff can't give themselves permissions");
        check(ben.status("GET", "/api/summary") == 403, "staff without dashboard access can't see the summary");
        check(owner2.status("POST", "/api/team/ana/disable") == 409, "owner can't disable themselves");
        check(owner2.status("POST", "/api/team/domingo/disable") == 404, "owner can't touch another business's staff");

        // ---- disabling ends sessions at once
        check(ben.status("GET", "/api/data") == 200, "staff working");
        check(owner2.status("POST", "/api/team/ben/disable") == 200, "owner disables staff");
        check(ben.status("GET", "/api/data") == 401, "disabled staff's session ends");
        check(ben.status("POST", "/api/login", "username", "ben", "password", "ben-pass-123") == 403, "disabled staff can't sign in");
        check(owner2.status("POST", "/api/team/ben/enable") == 200, "owner re-enables");

        // ---- suspension
        check(admin.status("POST", "/api/businesses/" + biz2 + "/status", "status", "suspended") == 200, "admin suspends");
        check(owner2.status("GET", "/api/data") == 401, "suspended business is signed out");
        check(domingo.status("GET", "/api/data") == 200, "other business unaffected");
        check(admin.status("POST", "/api/businesses/" + biz2 + "/status", "status", "active") == 200, "admin reactivates");
        check(owner2.status("POST", "/api/login", "username", "ana", "password", "ana-pass-123") == 200, "reactivated owner signs in");
        check(admin.status("POST", "/api/businesses/" + biz2 + "/status", "status", "rejected") == 409, "only pending can be rejected");

        // ---- one active owner
        check(admin.status("POST", "/api/accounts/ben/owner") == 200, "admin hands the store to another account");
        check(platform.account("ana").role().equals("staff") && platform.account("ben").owner(), "old owner is staff now");
        boolean blocked = false;
        try {
            platform.importAccount(biz2, "second", "owner", "check");
        } catch (Http.Error e) {
            blocked = e.status == 409;
        }
        check(blocked && platform.account("second") == null, "database refuses a second active owner");

        // ---- concurrent sign-ups for the same username: exactly one wins
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> new Browser(posUrl).status("POST", "/api/join", "code", code, "fullName", "Race Test",
                    "username", "racer", "password", "race-pass-123")));
        }
        int created = 0;
        for (Future<Integer> f : results) if (f.get() == 201) created++;
        pool.shutdown();
        check(created == 1, "one of 8 simultaneous sign-ups wins");

        // ---- audit trail
        String audit = admin.send("GET", "/api/platform/audit?business=" + biz2).body();
        check(audit.contains("Business approved") && audit.contains("Business suspended") && audit.contains("Approved staff ben"), "audit log");

        // ---- backup and restore
        HttpResponse<String> backup = admin.send("POST", "/api/platform/backup");
        check(backup.statusCode() == 200, "backup from the admin page");
        Path folder = Backups.list(Backups.dir(platform)).getLast();
        Path restoreTo = Files.createTempDirectory("lpg-restore-check-");
        Backups.restore(folder, restoreTo);
        try (Platform copy = new Platform(restoreTo.resolve("platform.db"))) {
            check(copy.businesses().size() == platform.businesses().size() && copy.account("ben") != null, "restored database matches");
            check(copy.integrity().equals("ok"), "restored database is healthy");
        }
        check(Files.readString(restoreTo.resolve("tenants/" + biz2 + "/Customers.csv")).contains("Bea"), "restored store data matches");
        check(admin.send("GET", "/api/platform").body().contains("\"database\":\"ok\""), "health shows database ok");
    }
}

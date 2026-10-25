import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * End-to-end check of each store's look and the owner's setup guide (5.3.0) over real HTTP, on a throw-away data
 * folder: color and receipt message, logo upload checked by content and size, logos kept apart per business and
 * included in backups, staff can see but not change the look, and the guide's steps following the store's records.
 *
 *   javac -d /tmp/lpg-check -cp "web/lib/*" web/src/*.java web/tests/*.java
 *   java -cp "/tmp/lpg-check:web/lib/*" BrandingCheck
 */
public class BrandingCheck {
    static void check(boolean ok, String what) { PlatformCheck.check(ok, what); }

    static String png(int size) {
        byte[] b = new byte[size];
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        System.arraycopy(head, 0, b, 0, head.length);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(b);
    }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("lpg-branding-check-");
        String salt = Auth.newSalt();
        Files.writeString(data.resolve("WebUsers.csv"), "robe," + salt + "," + Auth.hash("robe-pass-1", salt) + ",2026-01-01 08:00:00,1\n");
        Platform platform = new Platform(data.resolve("platform.db"));
        Tenants tenants = new Tenants(data, platform);
        tenants.migrateLegacy(data, "Robe's LPG");
        Billing billing = new Billing(platform);
        Auth auth = new Auth();
        Path pub = Path.of("web/public");
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
        try {
            run("http://localhost:" + pos.getAddress().getPort(), "http://localhost:" + adminPort, platform, tenants, billing, data);
        } finally {
            pos.stop(0);
            adm.stop(0);
        }
        System.out.println("PASS: " + PlatformCheck.pass + " branding checks (look, logo, receipts, guide)");
    }

    static void run(String posUrl, String adminUrl, Platform platform, Tenants tenants, Billing billing, Path data) throws Exception {
        PlatformCheck.Browser admin = new PlatformCheck.Browser(adminUrl);
        check(admin.status("POST", "/api/admin/setup", "password", "admin-secret-1") == 200, "admin password set");

        // ---- a new store: approved, owner signs in, sees the guide with nothing done
        PlatformCheck.Browser ana = new PlatformCheck.Browser(posUrl);
        check(ana.status("POST", "/api/register", "storeName", "Gas Two", "contactName", "Ana Cruz", "phone", "09171234567",
                "address", "Purok 3, Tagum", "username", "ana", "password", "ana-pass-123") == 201, "business registers");
        long biz2 = platform.businesses().stream().filter(b -> b.name().equals("Gas Two")).findFirst().orElseThrow().id();
        check(admin.status("POST", "/api/businesses/" + biz2 + "/status", "status", "active") == 200, "admin approves");
        check(ana.status("POST", "/api/login", "username", "ana", "password", "ana-pass-123") == 200, "owner signs in");
        String me = ana.send("GET", "/api/me").body();
        check(me.contains("\"brand\":{") && me.contains("\"color\":\"\"") && me.contains("\"logo\":null"), "me has the standard look: " + me);
        check(me.contains("\"address\":\"Purok 3, Tagum\"") && me.contains("\"phone\":\"09171234567\""), "me has what receipts print");
        String g = ana.send("GET", "/api/guide").body();
        check(g.contains("\"hidden\":false") && !g.contains("\"done\":true"), "guide: nothing done yet: " + g);
        check(!g.contains("\"key\":\"pay\""), "billing off: no pay step");
        for (String k : List.of("look", "products", "suppliers", "start", "staff", "sale")) check(g.contains("\"key\":\"" + k + "\""), "guide has step " + k);

        // ---- color and receipt message
        check(ana.status("PUT", "/api/business/brand", "color", "pink", "receiptNote", "") == 400, "unknown color refused");
        check(ana.status("PUT", "/api/business/brand", "color", "green", "receiptNote", "x".repeat(121)) == 400, "long receipt message refused");
        HttpResponse<String> look = ana.send("PUT", "/api/business/brand", "color", "green", "receiptNote", "Salamat! Call 0917 for delivery.");
        check(look.statusCode() == 200 && look.body().contains("\"color\":\"green\""), "color and message saved: " + look.body());
        check(ana.send("GET", "/api/me").body().contains("\"receiptNote\":\"Salamat! Call 0917 for delivery.\""), "me has the receipt message");
        check(ana.send("GET", "/api/guide").body().contains("{\"key\":\"look\",\"done\":true}"), "guide: look step done");

        // ---- logo: checked by content and size, stored in the business's own folder
        check(ana.status("GET", "/api/business/logo") == 404, "no logo yet");
        check(ana.status("POST", "/api/business/logo", "image", "data:image/png;base64," + Base64.getEncoder().encodeToString("<svg onload=alert(1)>".getBytes())) == 400, "a non-picture is refused");
        check(ana.status("POST", "/api/business/logo", "image", "data:text/html;base64,PGh0bWw+") == 400, "html is refused");
        check(ana.status("POST", "/api/business/logo", "image", png(200 * 1024 + 1)) == 413, "a logo over 200 KB is refused");
        HttpResponse<String> up = ana.send("POST", "/api/business/logo", "image", png(5000));
        check(up.statusCode() == 200 && up.body().contains("\"logo\":\"/api/business/logo?v="), "logo saved: " + up.body());
        String file = platform.business(biz2).logo();
        check(file.matches("logo-[a-z0-9]+\\.png") && Files.size(tenants.dir(biz2).resolve(file)) == 5000, "logo is a file in the store's folder");
        HttpResponse<String> got = ana.send("GET", "/api/business/logo");
        check(got.statusCode() == 200 && got.headers().firstValue("Content-Type").orElse("").equals("image/png"), "logo served as PNG");
        check(got.headers().firstValue("Cache-Control").orElse("").contains("private"), "logo not cached by shared caches");
        Thread.sleep(5);
        check(ana.status("POST", "/api/business/logo", "image", png(6000)) == 200, "logo replaced");
        check(!Files.exists(tenants.dir(biz2).resolve(file)), "old logo file deleted");
        check(new PlatformCheck.Browser(posUrl).status("GET", "/api/business/logo") == 401, "logo needs sign-in");

        // ---- staff: see the look, can't change it
        check(ana.status("POST", "/api/team", "username", "ben", "fullName", "Ben", "password", "ben-pass-123") == 201, "owner adds staff");
        PlatformCheck.Browser ben = new PlatformCheck.Browser(posUrl);
        check(ben.status("POST", "/api/login", "username", "ben", "password", "ben-pass-123") == 200, "staff signs in");
        check(ben.send("GET", "/api/me").body().contains("\"color\":\"green\""), "staff get the store's color");
        check(ben.status("GET", "/api/business/logo") == 200, "staff see the logo");
        check(ben.status("PUT", "/api/business/brand", "color", "rose", "receiptNote", "") == 403, "staff can't change the color");
        check(ben.status("POST", "/api/business/logo", "image", png(100)) == 403, "staff can't upload a logo");
        check(ben.status("DELETE", "/api/business/logo") == 403, "staff can't remove the logo");
        check(ben.status("GET", "/api/guide") == 403, "staff don't get the guide");
        check(ana.send("GET", "/api/guide").body().contains("{\"key\":\"staff\",\"done\":true}"), "guide: staff step done");

        // ---- another business can't see this one's look or logo
        PlatformCheck.Browser robe = new PlatformCheck.Browser(posUrl);
        check(robe.status("POST", "/api/login", "username", "robe", "password", "robe-pass-1") == 200, "store 1 owner signs in");
        check(robe.status("GET", "/api/business/logo") == 404, "store 1 has no logo of its own (never another store's)");
        check(robe.send("GET", "/api/me").body().contains("\"color\":\"\""), "store 1 keeps the standard color");

        // ---- guide follows the records
        check(ana.status("POST", "/api/products", "brand", "Petron", "price", "950", "weight", "11", "reorderLevel", "3") / 100 == 2, "owner adds a product");
        check(ana.send("GET", "/api/guide").body().contains("{\"key\":\"products\",\"done\":true}"), "guide: products step done");
        check(ana.status("POST", "/api/guide", "hidden", "1") == 200, "owner hides the guide");
        check(ana.send("GET", "/api/guide").body().contains("\"hidden\":true"), "guide stays hidden");
        check(ana.status("POST", "/api/guide", "hidden", "0") == 200 && ana.send("GET", "/api/guide").body().contains("\"hidden\":false"), "guide shown again");

        // ---- billing on: a pay step appears, and the look can still be set while unpaid
        check(admin.status("PUT", "/api/billing", "on", "1", "price", "10.00", "graceDays", "7", "trialDays", "0", "payee", "R", "note", "") == 200, "billing on");
        check(ana.send("GET", "/api/guide").body().contains("{\"key\":\"pay\",\"done\":false}"), "guide: pay step, not done");
        check(ana.status("PUT", "/api/business/brand", "color", "teal", "receiptNote", "") == 200, "look can be changed before paying");
        check(ana.status("DELETE", "/api/business/logo") == 200 && platform.business(biz2).logo().isEmpty(), "logo removed");
        check(ana.status("POST", "/api/business/logo", "image", png(3000)) == 200, "logo uploaded again");

        // ---- backups carry the logo; a forged file name is never served
        Path backup = Backups.run(platform, tenants);
        check(backup != null && Files.exists(backup.resolve("tenants/" + biz2 + "/" + platform.business(biz2).logo())), "backup includes the logo");
        platform.update("UPDATE businesses SET logo = ? WHERE id = ?", "../../platform.db", biz2);
        check(ana.status("GET", "/api/business/logo") == 404, "a stored name outside the store's folder is never served");
        check(platform.auditLog(biz2, 100).stream().anyMatch(a -> a.action().equals("Uploaded the store logo")), "audit log has the logo upload");
    }
}

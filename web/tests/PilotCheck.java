import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/**
 * Pilot readiness (5.5.0), over real HTTP on a throw-away data folder with two made-up stores:
 * who may void, edit and change prices or stock; a sale or payment sent twice is saved once (also 8 at the same
 * moment); stores can't reach each other's records by id; a crash or failed write half-way through a sale leaves no
 * half sale; backups taken while sales are being saved hold whole sales; the health link; and the sign-in limits.
 *
 *   java -cp "/tmp/lpg-check:web/lib/*" PilotCheck
 */
public class PilotCheck {
    static int pass;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
        pass++;
    }

    static String json(HttpResponse<String> r, String key) {
        String b = r.body();
        int i = b.indexOf("\"" + key + "\":");
        if (i < 0) return null;
        int s = i + key.length() + 3, e = s;
        if (b.charAt(s) == '"') return b.substring(s + 1, b.indexOf('"', s + 1));
        while (e < b.length() && ",}".indexOf(b.charAt(e)) < 0) e++;
        return b.substring(s, e);
    }

    static void account(Platform platform, Tenants tenants, long biz, String user, String role, boolean dashboard) {
        platform.importAccount(biz, user, role, "check");
        String salt = Auth.newSalt();
        DataStore store = tenants.get(biz).store();
        store.addUser(user, salt, Auth.hash(user + "-pass-1", salt));
        store.setDashboard(user, dashboard);
    }

    static String[] sale(String customer, String product, String requestId, String paid) {
        List<String> f = new ArrayList<>(List.of("customerId", customer, "items", "1", "productId.0", product, "qty.0", "1",
                "emptyId.0", product, "paid", paid));
        if (requestId != null) f.addAll(List.of("requestId", requestId));
        return f.toArray(String[]::new);
    }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("lpg-pilot-check-");
        Platform platform = new Platform(data.resolve("platform.db"));
        Tenants tenants = new Tenants(data, platform);
        long one = platform.createActive("Gas One", "check"), two = platform.createActive("Gas Two", "check");
        account(platform, tenants, one, "owner1", "owner", true);
        account(platform, tenants, one, "cash1", "staff", false);
        account(platform, tenants, one, "cash2", "staff", false);
        account(platform, tenants, two, "owner2", "owner", true);

        Auth auth = new Auth();
        Path pub = Path.of("web/public");
        PosGate gate = new PosGate(platform, new Billing(platform), tenants, auth, pub.resolve("app"), pub.resolve("shared"), 1, null);
        HttpServer pos = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pos.createContext("/", Http.wrap(gate));
        pos.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        pos.start();
        try {
            run("http://localhost:" + pos.getAddress().getPort(), platform, tenants);
        } finally {
            pos.stop(0);
        }
        crashSafety();
        System.out.println("PASS: " + pass + " pilot checks (permissions, sent twice, isolation, crash safety, backups, health, sign-in limits)");
    }

    static PlatformCheck.Browser signIn(String url, String user) throws Exception {
        PlatformCheck.Browser b = new PlatformCheck.Browser(url);
        check(b.status("POST", "/api/login", "username", user, "password", user + "-pass-1") == 200, user + " signs in");
        return b;
    }

    static void run(String url, Platform platform, Tenants tenants) throws Exception {
        PlatformCheck.Browser owner = signIn(url, "owner1"), cash1 = signIn(url, "cash1"), cash2 = signIn(url, "cash2"), other = signIn(url, "owner2");
        DataStore store = tenants.get(platform.account("owner1").businessId()).store();

        // ---- products, prices and stock: owner (and dashboard staff) only
        check(cash1.status("POST", "/api/products", "brand", "Fake", "weight", "11", "price", "1000", "openingLoaded", "50") == 403, "cashier can't add a product with stock");
        HttpResponse<String> prod = owner.send("POST", "/api/products", "brand", "Fake", "weight", "11", "price", "1000", "refillCost", "800",
                "openingLoaded", "50", "openingEmpty", "0");
        check(prod.statusCode() == 201, "owner adds a product: " + prod.body());
        String pid = json(prod, "id");
        check(cash1.status("PUT", "/api/products/" + pid, "brand", "Fake", "weight", "11", "price", "1") == 403, "cashier can't change the price");
        check(store.product(pid).price() == 1000, "price unchanged");
        check(cash1.status("DELETE", "/api/products/" + pid) == 403, "cashier can't delete a product");
        check(cash1.status("POST", "/api/stock/adjust", "productId", pid, "loaded", "0", "empty", "0", "reason", "damaged", "note", "x") == 403,
                "cashier can't write off stock");
        check(store.stock().get(pid).loaded() == 50, "stock unchanged");
        check(owner.status("PUT", "/api/products/" + pid, "brand", "Fake", "weight", "11", "price", "1000", "refillCost", "800") == 200, "owner edits the product");
        check(owner.status("POST", "/api/suppliers", "name", "Acme", "brand", "Fake") == 201, "supplier added");
        String sid = store.suppliers().get(0).id();
        check(cash1.status("PUT", "/api/suppliers/" + sid, "name", "Other", "brand", "Fake") == 403, "cashier can't change a supplier");
        check(cash1.status("DELETE", "/api/suppliers/" + sid) == 403, "cashier can't delete a supplier");

        HttpResponse<String> cust = cash1.send("POST", "/api/customers", "firstName", "Ana", "lastName", "Cruz");
        check(cust.statusCode() == 201, "cashier adds a customer");
        String cid = json(cust, "id");
        check(cash1.status("DELETE", "/api/customers/" + cid) == 403, "cashier can't delete a customer");

        // ---- a sale sent twice is saved once
        HttpResponse<String> first = cash1.send("POST", "/api/receipts", sale(cid, pid, "req-a", "1000"));
        check(first.statusCode() == 201, "sale saved: " + first.body());
        HttpResponse<String> again = cash1.send("POST", "/api/receipts", sale(cid, pid, "req-a", "1000"));
        check(again.statusCode() == 201 && Objects.equals(json(again, "id"), json(first, "id")), "the same sale sent again gets the first receipt back");
        check(store.receipts().size() == 1 && store.stock().get(pid).loaded() == 49, "one receipt, one tank out");
        check(cash2.send("POST", "/api/receipts", sale(cid, pid, "req-a", "1000")).statusCode() == 201 && store.receipts().size() == 2,
                "another cashier's request with the same id is their own sale");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) ids.add(pool.submit(() -> json(cash1.send("POST", "/api/receipts", sale(cid, pid, "req-b", "1000")), "id")));
        Set<String> got = new HashSet<>();
        for (Future<String> f : ids) got.add(f.get());
        check(got.size() == 1 && store.receipts().size() == 3, "8 taps at once: one receipt");
        check(cash1.send("POST", "/api/receipts", sale(cid, pid, null, "1000")).statusCode() == 201 && store.receipts().size() == 4, "older POS without an id still saves");
        check(cash1.status("POST", "/api/receipts", sale(cid, pid, "x".repeat(65), "1000")) == 400, "an over-long id is refused");

        // A payment sent twice is recorded once.
        HttpResponse<String> later = cash1.send("POST", "/api/receipts", sale(cid, pid, "req-c", "0"));
        String unpaid = json(later, "id");
        String[] pay = {"receiptId", unpaid, "amount", "400", "requestId", "pay-1"};
        check(cash1.status("POST", "/api/payments", pay) == 201 && cash1.status("POST", "/api/payments", pay) == 201, "payment sent twice");
        check(store.payments().stream().filter(p -> p.receiptId().equals(unpaid)).mapToDouble(DataStore.Payment::amount).sum() == 400,
                "paid once: ₱400, not ₱800");

        // ---- voiding and editing sales
        String mine = json(first, "id");
        String[] edit = sale(cid, pid, null, "900");
        check(cash2.status("DELETE", "/api/receipts/" + mine) == 403, "a cashier can't void another cashier's sale");
        check(cash2.status("PUT", "/api/receipts/" + mine, edit) == 403, "a cashier can't edit another cashier's sale");
        check(store.receipt(mine) != null && store.receipt(mine).paidAtSale() == 1000, "that sale is untouched");
        check(cash1.status("PUT", "/api/receipts/" + mine, sale(cid, pid, null, "1000")) == 200, "a cashier edits their own sale right after");
        String fresh = json(cash1.send("POST", "/api/receipts", sale(cid, pid, "req-d", "1000")), "id");
        check(cash1.status("DELETE", "/api/receipts/" + fresh) == 200 && store.receipt(fresh) == null, "a cashier voids their own sale right after");
        DataStore.recordOn(LocalDate.now().minusDays(1));
        DataStore.Receipt old;
        try {
            old = store.createReceipt(cid, "cash1", List.of(new DataStore.LineInput(store.product(pid), 1, 1000, pid, "", 0)), 0, 1000, "", "cash", false);
        } finally {
            DataStore.recordNow();
        }
        check(cash1.status("DELETE", "/api/receipts/" + old.id()) == 403, "a cashier can't void their own sale from yesterday");
        check(cash1.status("PUT", "/api/receipts/" + old.id(), edit) == 403, "nor edit it");
        check(owner.status("DELETE", "/api/receipts/" + old.id()) == 200 && store.receipt(old.id()) == null, "the owner can void it");

        // ---- the other store can't reach these records by id
        DataStore otherStore = tenants.get(platform.account("owner2").businessId()).store();
        check(other.status("DELETE", "/api/receipts/" + mine) == 404 && store.receipt(mine) != null, "another store can't void this store's receipt");
        check(other.status("PUT", "/api/products/" + pid, "brand", "X", "weight", "11", "price", "1") == 404 && store.product(pid).price() == 1000,
                "another store can't change this store's prices");
        check(other.status("POST", "/api/payments", "receiptId", unpaid, "amount", "1") == 404, "another store can't pay this store's receipt");
        String record = other.send("GET", "/api/record?kind=payment&id=" + store.payments().get(0).id()).body();
        check(!record.contains("Ana") && !record.contains("400"), "another store can't read this store's payment: " + record);
        String explain = other.send("GET", "/api/explain?metric=customer-balance&id=" + cid).body();
        check(!explain.contains("Ana"), "another store can't see this store's customer: " + explain);
        check(!other.send("GET", "/api/data").body().contains("Ana") && otherStore.receipts().isEmpty(), "the other store's data stays empty");

        // ---- health link: no sign-in, no store data
        HttpResponse<String> health = new PlatformCheck.Browser(url).send("GET", "/healthz");
        check(health.statusCode() == 200 && health.body().equals("{\"ok\":true}"), "health link answers: " + health.body());

        // ---- sign-in limits: guessing from one place doesn't lock the owner out from another
        for (int i = 0; i < 8; i++) tunnelled(url, "198.51.100.7", "owner1", "wrong-" + i);
        check(tunnelled(url, "198.51.100.7", "owner1", "owner1-pass-1") == 429, "that connection is slowed down");
        check(tunnelled(url, "203.0.113.20", "owner1", "owner1-pass-1") == 200, "the owner still signs in from their own phone");

        // ---- backups taken while sales are saved hold whole sales
        Future<?> selling = pool.submit(() -> {
            for (int i = 0; i < 40; i++) {
                try {
                    cash1.send("POST", "/api/receipts", sale(cid, pid, "bk-" + i, "1000"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return null;
        });
        long biz = platform.account("owner1").businessId();
        int taken = 0;
        try {
            while (!selling.isDone()) {
                Path b = Backups.run(platform, tenants);
                taken++;
                for (DataStore.Receipt rc : new DataStore(b.resolve("tenants/" + biz)).receipts()) {
                    check(!rc.lines().isEmpty(), "backup " + b.getFileName() + ": receipt #" + rc.id() + " has its items");
                }
            }
            selling.get();
        } finally {
            pool.shutdownNow();
        }
        check(taken > 1, "several backups taken during sales (several in the same second): " + taken);
    }

    /** A sign-in as if through the Cloudflare tunnel from the given address. */
    static int tunnelled(String url, String ip, String user, String password) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/api/login")).header("X-LPG", "1").header("CF-Connecting-IP", ip)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=" + password)).build();
        return java.net.http.HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** The rollback journal: an unfinished change is put back at start-up, and a failed write rolls back at once. */
    static void crashSafety() throws Exception {
        Path dir = Files.createTempDirectory("lpg-crash-check-");
        Files.writeString(dir.resolve("Customers.csv"), "1,Ana,Cruz,,\n");
        Files.writeString(dir.resolve("LPGs.csv"), "1,Fake,1000,11,5,0,700,900,0\n");
        DataStore store = new DataStore(dir);
        store.addMovement(store.product("1"), "count", 10, 0, 0, 0, "ana", "start", null);
        DataStore.LineInput one = new DataStore.LineInput(store.product("1"), 1, 1000, "1", "", 0);
        store.createReceipt("1", "ana", List.of(one), 0, 1000, "", "cash", false);
        check(!Files.exists(dir.resolve(".journal/files")), "a finished sale leaves no journal");
        String receipts = Files.readString(dir.resolve("Receipts.csv")), lines = Files.readString(dir.resolve("Transactions.csv"));

        // The server stopped after writing a second sale's receipt row but before its items (and a new file).
        Path j = dir.resolve(".journal");
        Files.createDirectories(j);
        Files.writeString(j.resolve("Receipts.csv"), receipts);
        Files.writeString(j.resolve("files"), "Receipts.csv\n-Payments.csv\n");
        Files.writeString(dir.resolve("Receipts.csv"), receipts + "2,2026-01-01 10:00:00,1,ana,0.00,1000.00,,cash\n");
        Files.writeString(dir.resolve("Payments.csv"), "1,2026-01-01 10:00:00,2,1,5.00,ana,,cash\n");
        DataStore after = new DataStore(dir);
        check(after.receipts().size() == 1 && Files.readString(dir.resolve("Receipts.csv")).equals(receipts), "the half sale is taken out at start-up");
        check(!Files.exists(dir.resolve("Payments.csv")), "a file the change made is removed");
        check(!Files.exists(j.resolve("files")), "the journal is cleared");
        check(after.stock().get("1").loaded() == 9, "stock as after the first sale");

        // A write fails half-way (the items file can't be written): the receipt row is taken back out at once.
        Path blocker = dir.resolve(".Transactions.csv.web.tmp");
        Files.createDirectories(blocker.resolve("x"));
        boolean failed = false;
        try {
            after.createReceipt("1", "ana", List.of(one), 0, 1000, "", "cash", false);
        } catch (RuntimeException e) {
            failed = true;
        }
        check(failed, "the failed write is reported");
        check(Files.readString(dir.resolve("Receipts.csv")).equals(receipts) && Files.readString(dir.resolve("Transactions.csv")).equals(lines),
                "no half sale after a failed write");
        check(after.receipts().size() == 1 && after.stock().get("1").loaded() == 9, "the store still reads one sale");
        Tenants.deleteTree(blocker);
        after.createReceipt("1", "ana", List.of(one), 0, 1000, "", "cash", false);
        check(after.receipts().size() == 2 && new DataStore(dir).receipts().get(1).lines().size() == 1, "selling works again");
    }
}

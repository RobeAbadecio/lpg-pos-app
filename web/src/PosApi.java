import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The staff-facing POS: static web app + JSON API. Every data call needs a signed-in staff session.
 * With autoUser set (demo only, see WebServer), visitors are signed in as that user automatically.
 */
final class PosApi implements Http.Handler {
    private final DataStore store;
    private final Auth auth;
    private final ActivityLog log;
    private final Path appRoot, sharedRoot;
    private final String autoUser;
    /** Per-port name, since browsers share cookies across ports of the same host (e.g. demo vs real POS). */
    private final String COOKIE;

    PosApi(DataStore store, Auth auth, ActivityLog log, Path appRoot, Path sharedRoot, int port, String autoUser) {
        this.store = store;
        this.auth = auth;
        this.log = log;
        this.appRoot = appRoot;
        this.sharedRoot = sharedRoot;
        this.autoUser = autoUser;
        this.COOKIE = "lpg_session_" + port;
    }

    @Override
    public void handle(Http.Req r) throws IOException {
        if (!r.path.startsWith("/api/")) {
            Http.serveStatic(r, appRoot, sharedRoot);
            return;
        }
        Http.requireCsrfHeader(r);
        switch (r.method + " " + r.path) {
            case "POST /api/login" -> {
                login(r);
                return;
            }
            case "POST /api/logout" -> {
                Auth.Session s = auth.get(r.cookie(COOKIE));
                if (s != null) log.add(s.username, r.clientIp(), "Signed out");
                auth.end(r.cookie(COOKIE));
                r.addHeader("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                r.json(200, Json.obj("ok", true));
                return;
            }
            default -> {
            }
        }

        Auth.Session s = auth.get(r.cookie(COOKIE));
        if (s == null && autoUser != null && store.user(autoUser) != null) {
            s = auth.create(autoUser, r.clientIp(), r.via(), r.header("User-Agent"));
            setSessionCookie(r, s);
            log.add(autoUser, r.clientIp(), "Opened the demo POS (no sign-in)");
        }
        if (s == null || store.user(s.username) == null) {
            if (s != null) auth.end(s.token);
            throw new Http.Error(401, "Please sign in");
        }

        String[] seg = r.path.substring("/api/".length()).split("/");
        String id = seg.length > 1 ? seg[1] : null;
        String route = r.method + " " + seg[0] + (id == null ? "" : "/:id");
        switch (route) {
            case "GET me" -> r.json(200, me(s));
            case "POST me/:id" -> changePassword(r, s, id);
            case "GET data" -> r.json(200, data());

            case "POST customers" -> {
                String[] f = customerFields(r);
                DataStore.Customer c = store.addCustomer(f[0], f[1], f[2], f[3]);
                log.add(s.username, r.clientIp(), "Added customer #" + c.id() + " " + c.name());
                r.json(201, Json.obj("id", c.id()));
            }
            case "PUT customers/:id" -> {
                String[] f = customerFields(r);
                if (!store.updateCustomer(id, f[0], f[1], f[2], f[3])) throw new Http.Error(404, "Customer not found");
                log.add(s.username, r.clientIp(), "Edited customer #" + id);
                r.json(200, Json.obj("ok", true));
            }
            case "DELETE customers/:id" -> {
                int result = store.deleteCustomer(id);
                if (result < 0) throw new Http.Error(404, "Customer not found");
                if (result > 0) throw new Http.Error(409, "This customer has " + result + " sale(s). Delete those first.");
                log.add(s.username, r.clientIp(), "Deleted customer #" + id);
                r.json(200, Json.obj("ok", true));
            }

            case "POST products" -> {
                ProductFields f = productFields(r);
                int openFull = (int) wholeNumber(r.param("openingFull"), "Full cylinders on hand", 0, 10_000);
                int openEmpty = (int) wholeNumber(r.param("openingEmpty"), "Empty cylinders on hand", 0, 10_000);
                DataStore.Product p = store.addProduct(f.brand, f.price, f.weight, f.reorderLevel);
                log.add(s.username, r.clientIp(), "Added product #" + p.id() + " " + p.label());
                if (openFull > 0 || openEmpty > 0) {
                    store.addMovement(p, "count", openFull, openEmpty, s.username, "Opening stock");
                    log.add(s.username, r.clientIp(), "Opening stock for " + p.label() + ": " + openFull + " full, " + openEmpty + " empty");
                }
                r.json(201, Json.obj("id", p.id()));
            }
            case "PUT products/:id" -> {
                ProductFields f = productFields(r);
                if (!store.updateProduct(id, f.brand, f.price, f.weight, f.reorderLevel)) throw new Http.Error(404, "Product not found");
                log.add(s.username, r.clientIp(), "Edited product #" + id + " (price " + DataStore.money(f.price) + ")");
                r.json(200, Json.obj("ok", true));
            }
            case "DELETE products/:id" -> {
                int result = store.deleteProduct(id);
                if (result < 0) throw new Http.Error(404, "Product not found");
                if (result > 0) throw new Http.Error(409, "This product appears in " + result + " sale(s). Delete those first.");
                log.add(s.username, r.clientIp(), "Deleted product #" + id);
                r.json(200, Json.obj("ok", true));
            }

            case "POST transactions" -> {
                SaleFields f = saleFields(r);
                DataStore.Txn t = store.addTransaction(f.customer.id(), f.product, f.qty, s.username, f.returnedEmpty);
                log.add(s.username, r.clientIp(), "Sale #" + t.id() + ": " + f.qty + " × " + f.product.label()
                        + (f.returnedEmpty ? "" : " (new tank)")
                        + " to " + f.customer.name() + " (₱" + DataStore.money(f.qty * f.product.price()) + ")");
                r.json(201, Json.obj("id", t.id(), "amount", f.qty * f.product.price()));
            }
            case "PUT transactions/:id" -> {
                SaleFields f = saleFields(r);
                if (!store.updateTransaction(id, f.customer.id(), f.product, f.qty, f.returnedEmpty)) throw new Http.Error(404, "Sale not found");
                log.add(s.username, r.clientIp(), "Edited sale #" + id);
                r.json(200, Json.obj("ok", true));
            }
            case "DELETE transactions/:id" -> {
                if (!store.deleteTransaction(id)) throw new Http.Error(404, "Sale not found");
                log.add(s.username, r.clientIp(), "Deleted sale #" + id);
                r.json(200, Json.obj("ok", true));
            }
            case "POST stock/:id" -> stockChange(r, s, id);
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
    }

    // ---------------------------------------------------------------- inventory

    private void stockChange(Http.Req r, Auth.Session s, String kind) throws IOException {
        String pid = r.param("productId");
        DataStore.Product p = store.products().stream().filter(x -> x.id().equals(pid)).findFirst()
                .orElseThrow(() -> new Http.Error(400, "Choose an LPG product"));
        String note = limit(r.param("note"), 120, "Note");
        switch (kind) {
            case "delivery" -> {
                int full = (int) wholeNumber(r.param("full"), "Full cylinders received", 1, 1_000);
                int returned = (int) wholeNumber(r.param("emptiesReturned"), "Empties returned", 0, 1_000);
                store.addMovement(p, "delivery", full, -returned, s.username, note);
                log.add(s.username, r.clientIp(), "Received " + full + " × " + p.label()
                        + (returned > 0 ? ", returned " + returned + " empties" : "") + (note.isEmpty() ? "" : " (" + note + ")"));
            }
            case "adjust" -> {
                int full = (int) wholeNumber(r.param("full"), "Full cylinders counted", 0, 10_000);
                int empty = (int) wholeNumber(r.param("empty"), "Empty cylinders counted", 0, 10_000);
                String reason = r.param("reason");
                if (!List.of("count", "damaged", "other").contains(reason)) throw new Http.Error(400, "Choose a reason");
                if (!reason.equals("count") && note.isEmpty()) throw new Http.Error(400, "Add a note saying what happened");
                DataStore.Stock now = store.adjustTo(p, reason, full, empty, s.username, note);
                if (now == null) throw new Http.Error(400, "Those are the current counts; nothing to change");
                log.add(s.username, r.clientIp(), switch (reason) {
                    case "count" -> "Stock count";
                    case "damaged" -> "Wrote off damaged stock";
                    default -> "Adjusted stock";
                } + " for " + p.label() + ": full " + now.full() + " → " + full + ", empty " + now.empty() + " → " + empty
                        + (note.isEmpty() ? "" : " (" + note + ")"));
            }
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
        r.json(200, Json.obj("ok", true));
    }

    static String stockStatus(DataStore.Stock st, int reorderLevel) {
        if (st.full() <= 0) return "out";
        return st.full() <= reorderLevel ? "low" : "ok";
    }

    // ---------------------------------------------------------------- auth

    private void login(Http.Req r) throws IOException {
        String ip = r.clientIp();
        String username = r.param("username").toLowerCase(Locale.ROOT);
        String password = r.form().getOrDefault("password", "");
        if (auth.throttled(ip, username)) {
            throw new Http.Error(429, "Too many failed attempts. Wait 15 minutes and try again.");
        }
        DataStore.User user = store.user(username);
        if (!Auth.verify(password, user)) {
            auth.recordFailure(ip, username);
            log.add(username.isEmpty() ? "?" : username, ip, "Failed sign-in (" + r.via() + ")");
            throw new Http.Error(401, "Wrong username or password");
        }
        auth.clearFailures(ip, username);
        Auth.Session s = auth.create(user.username(), ip, r.via(), r.header("User-Agent"));
        setSessionCookie(r, s);
        log.add(user.username(), ip, "Signed in (" + r.via() + ")");
        r.json(200, me(s));
    }

    private void setSessionCookie(Http.Req r, Auth.Session s) {
        r.addHeader("Set-Cookie", COOKIE + "=" + s.token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                + Auth.SESSION_IDLE_MS / 1000 + (r.secure() ? "; Secure" : ""));
    }

    private void changePassword(Http.Req r, Auth.Session s, String what) throws IOException {
        if (!"password".equals(what)) throw new Http.Error(404, "Unknown endpoint");
        DataStore.User user = store.user(s.username);
        if (!Auth.verify(r.form().getOrDefault("current", ""), user)) throw new Http.Error(400, "Current password is wrong");
        String next = r.form().getOrDefault("password", "");
        if (next.length() < 8) throw new Http.Error(400, "New password must be at least 8 characters");
        String salt = Auth.newSalt();
        store.setPassword(s.username, salt, Auth.hash(next, salt));
        auth.revokeUser(s.username, s.token);
        log.add(s.username, r.clientIp(), "Changed own password");
        r.json(200, Json.obj("ok", true));
    }

    private Map<String, Object> me(Auth.Session s) {
        return Json.obj("username", s.username, "demo", autoUser != null);
    }

    // ---------------------------------------------------------------- data

    private Map<String, Object> data() {
        List<DataStore.Customer> customers = store.customers();
        List<DataStore.Product> products = store.products();
        List<DataStore.Txn> txns = store.transactions();
        Map<String, DataStore.Customer> custById = customers.stream()
                .collect(Collectors.toMap(DataStore.Customer::id, Function.identity(), (a, b) -> a));
        Map<String, DataStore.Product> prodById = products.stream()
                .collect(Collectors.toMap(DataStore.Product::id, Function.identity(), (a, b) -> a));

        Map<String, DataStore.Stock> stock = store.stock();
        Map<String, Integer> salesByCustomer = new HashMap<>();
        Map<String, Integer> qtyByProduct = new HashMap<>();
        List<Object> txnOut = new ArrayList<>();
        for (DataStore.Txn t : txns) {
            salesByCustomer.merge(t.customerId(), 1, Integer::sum);
            qtyByProduct.merge(t.productId(), t.qty(), Integer::sum);
            DataStore.Customer c = custById.get(t.customerId());
            DataStore.Product p = prodById.get(t.productId());
            txnOut.add(Json.obj(
                    "id", t.id(),
                    "time", t.rawTime(),
                    "customerId", t.customerId(),
                    "customer", c == null ? "Unknown customer #" + t.customerId() : c.name(),
                    "productId", t.productId(),
                    "product", p == null ? "Unknown product #" + t.productId() : p.label(),
                    "qty", t.qty(),
                    "unitPrice", t.unitPrice() != null ? t.unitPrice() : (p == null ? 0.0 : p.price()),
                    "amount", DataStore.amount(t, prodById),
                    "staff", t.staff().isEmpty() ? "Desktop app" : t.staff(),
                    "returnedEmpty", t.returnedEmpty()));
        }
        Collections.reverse(txnOut); // newest first

        List<Object> custOut = new ArrayList<>();
        for (DataStore.Customer c : customers) {
            custOut.add(Json.obj("id", c.id(), "firstName", c.firstName(), "lastName", c.lastName(),
                    "contact", c.contact(), "address", c.address(), "sales", salesByCustomer.getOrDefault(c.id(), 0)));
        }
        List<Object> prodOut = new ArrayList<>();
        for (DataStore.Product p : products) {
            DataStore.Stock st = stock.getOrDefault(p.id(), new DataStore.Stock(0, 0));
            prodOut.add(Json.obj("id", p.id(), "brand", p.brand(), "price", p.price(), "weight", p.weight(),
                    "label", p.label(), "sold", qtyByProduct.getOrDefault(p.id(), 0),
                    "full", st.full(), "empty", st.empty(), "reorderLevel", p.reorderLevel(),
                    "status", stockStatus(st, p.reorderLevel())));
        }
        List<DataStore.Movement> moves = store.movements();
        List<Object> moveOut = new ArrayList<>();
        for (int i = moves.size() - 1; i >= 0 && moveOut.size() < 40; i--) {
            DataStore.Movement m = moves.get(i);
            DataStore.Product p = prodById.get(m.productId());
            moveOut.add(Json.obj("id", m.id(), "time", m.rawTime(), "productId", m.productId(),
                    "product", p == null ? "Deleted product #" + m.productId() : p.label(),
                    "type", m.type(), "full", m.fullDelta(), "empty", m.emptyDelta(), "staff", m.staff(), "note", m.note()));
        }
        return Json.obj("customers", custOut, "products", prodOut, "transactions", txnOut, "movements", moveOut);
    }

    // ---------------------------------------------------------------- validation

    private static String[] customerFields(Http.Req r) throws IOException {
        String first = limit(r.param("firstName"), 60, "First name");
        String last = limit(r.param("lastName"), 60, "Last name");
        if (first.isEmpty() || last.isEmpty()) throw new Http.Error(400, "First name and last name are required");
        String contact = limit(r.param("contact"), 30, "Contact number");
        String address = limit(r.param("address"), 160, "Address");
        return new String[]{first, last, contact, address};
    }

    private record ProductFields(String brand, double price, double weight, int reorderLevel) {}

    private static ProductFields productFields(Http.Req r) throws IOException {
        String brand = limit(r.param("brand"), 60, "Brand");
        if (brand.isEmpty()) throw new Http.Error(400, "Brand is required");
        double price = number(r.param("price"), "Price", 0.01, 1_000_000);
        double weight = number(r.param("weight"), "Weight", 0.1, 1_000);
        int reorder = (int) wholeNumber(r.param("reorderLevel").isEmpty() ? "5" : r.param("reorderLevel"), "Reorder level", 0, 1_000);
        return new ProductFields(brand, Math.round(price * 100) / 100.0, weight, reorder);
    }

    private record SaleFields(DataStore.Customer customer, DataStore.Product product, int qty, boolean returnedEmpty) {}

    private SaleFields saleFields(Http.Req r) throws IOException {
        String cid = r.param("customerId"), pid = r.param("productId");
        DataStore.Customer c = store.customers().stream().filter(x -> x.id().equals(cid)).findFirst()
                .orElseThrow(() -> new Http.Error(400, "Choose a customer"));
        DataStore.Product p = store.products().stream().filter(x -> x.id().equals(pid)).findFirst()
                .orElseThrow(() -> new Http.Error(400, "Choose an LPG product"));
        double qty = wholeNumber(r.param("qty").isEmpty() ? "1" : r.param("qty"), "Quantity", 1, 100);
        return new SaleFields(c, p, (int) qty, !"0".equals(r.param("returnedEmpty")));
    }

    private static String limit(String v, int max, String label) {
        if (v.length() > max) throw new Http.Error(400, label + " is too long (max " + max + " characters)");
        return v;
    }

    /** Blank counts as 0. */
    private static double wholeNumber(String v, String label, double min, double max) {
        double d = number(v.isEmpty() ? "0" : v, label, min, max);
        if (d != Math.rint(d)) throw new Http.Error(400, label + " must be a whole number");
        return d;
    }

    private static double number(String v, String label, double min, double max) {
        double d;
        try {
            d = Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new Http.Error(400, label + " must be a number");
        }
        if (!Double.isFinite(d) || d < min || d > max) throw new Http.Error(400, label + " must be between " + fmt(min) + " and " + fmt(max));
        return d;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}

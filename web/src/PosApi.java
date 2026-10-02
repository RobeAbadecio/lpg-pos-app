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
    private static final int MAX_ITEMS = 30;

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
        String action = seg.length > 2 ? seg[2] : null;
        String route = r.method + " " + seg[0] + (id == null ? "" : "/:id") + (action == null ? "" : "/" + action);
        String who = s.username, ip = r.clientIp();
        switch (route) {
            case "GET me" -> r.json(200, me(s));
            case "GET summary" -> {
                requireDashboard(who);
                String range = r.query().getOrDefault("range", "7");
                if (!List.of("today", "7", "30", "90", "all").contains(range)) range = "7";
                r.json(200, Json.obj("stats", Stats.compute(store, range), "inventory", Stats.inventory(store),
                        "receivables", Stats.receivables(store), "tanksOut", Stats.tanksOut(store), "staffMoney", Stats.staffMoney(store)));
            }
            case "GET daily" -> {
                requireDashboard(who);
                r.json(200, Stats.daily(store, r.query().getOrDefault("date", ""), 14));
            }
            case "POST me/:id" -> changePassword(r, s, id);
            case "GET data" -> {
                String etag = '"' + store.version() + '"';
                if (r.notModified(etag)) return;
                r.addHeader("ETag", etag);
                r.json(200, data(who));
            }

            // ---- customers
            case "POST customers" -> {
                String[] f = customerFields(r);
                DataStore.Customer c = store.addCustomer(f[0], f[1], f[2], f[3]);
                log.add(who, ip, "Added customer #" + c.id() + " " + c.name());
                r.json(201, Json.obj("id", c.id()));
            }
            case "PUT customers/:id" -> {
                String[] f = customerFields(r);
                if (!store.updateCustomer(id, f[0], f[1], f[2], f[3])) throw new Http.Error(404, "Customer not found");
                log.add(who, ip, "Edited customer #" + id);
                r.json(200, Json.obj("ok", true));
            }
            case "POST customers/:id/returns" -> {
                DataStore.Customer c = store.customer(id);
                if (c == null) throw new Http.Error(404, "Customer not found");
                DataStore.Product p = productParam(r, "productId");
                int qty = whole(r.param("qty"), "Tanks returned", 1, 1_000);
                String remark = limit(r.param("remark"), 120, "Remark");
                store.returnTanks(c, p, qty, who, remark);
                log.add(who, ip, c.name() + " returned " + qty + " empty " + p.label() + (remark.isEmpty() ? "" : " [" + remark + "]"));
                r.json(201, Json.obj("ok", true));
            }
            case "DELETE customers/:id" -> {
                int result = store.deleteCustomer(id);
                if (result < 0) throw new Http.Error(404, "Customer not found");
                if (result > 0) throw new Http.Error(409, "This customer has " + result + " receipt(s), so they can't be deleted.");
                log.add(who, ip, "Deleted customer #" + id);
                r.json(200, Json.obj("ok", true));
            }

            // ---- products
            case "POST products" -> {
                ProductFields f = productFields(r);
                int loaded = whole(r.param("openingLoaded"), "Tanks with load on hand", 0, 10_000);
                int empty = whole(r.param("openingEmpty"), "Empty tanks on hand", 0, 10_000);
                DataStore.Product p = store.addProduct(f.toProduct(null, 0));
                log.add(who, ip, "Added product #" + p.id() + " " + p.label());
                if (loaded > 0 || empty > 0) {
                    store.addMovement(p, "count", loaded, empty, 0, 0, who, "Opening stock", null);
                    log.add(who, ip, "Opening stock for " + p.label() + ": " + loaded + " with load, " + empty + " empty");
                }
                r.json(201, Json.obj("id", p.id()));
            }
            case "PUT products/:id" -> {
                ProductFields f = productFields(r);
                DataStore.Product old = store.product(id);
                if (old == null || !store.updateProduct(f.toProduct(id, old.tankPrice()))) throw new Http.Error(404, "Product not found");
                log.add(who, ip, "Edited product #" + id + " (refill ₱" + DataStore.money(f.price)
                        + (f.refillCost > 0 ? ", refill cost ₱" + DataStore.money(f.refillCost) : "")
                        + (f.swapFee > 0 ? ", swap fee ₱" + DataStore.money(f.swapFee) : "") + ")");
                r.json(200, Json.obj("ok", true));
            }
            case "DELETE products/:id" -> {
                String why = store.deleteProduct(id);
                if (why != null) throw new Http.Error(why.equals("Product not found") ? 404 : 409, why);
                log.add(who, ip, "Deleted product #" + id);
                r.json(200, Json.obj("ok", true));
            }

            // ---- receipts and payments
            case "POST receipts" -> {
                ReceiptFields f = receiptFields(r);
                DataStore.Receipt rc = store.createReceipt(f.customer.id(), who, f.items, f.discount, f.paid, f.note);
                log.add(who, ip, "Receipt #" + rc.id() + " for " + f.customer.name() + ": " + describe(rc) + paymentText(rc, f.paid));
                r.json(201, Json.obj("id", rc.id(), "total", rc.total()));
            }
            case "PUT receipts/:id" -> {
                ReceiptFields f = receiptFields(r);
                DataStore.Receipt rc = store.updateReceipt(id, f.customer.id(), f.items, f.discount, f.paid, f.note);
                if (rc == null) throw new Http.Error(404, "Receipt not found");
                log.add(who, ip, "Edited receipt #" + id + ": " + describe(rc) + ", total ₱" + DataStore.money(rc.total()));
                r.json(200, Json.obj("id", rc.id(), "total", rc.total()));
            }
            case "DELETE receipts/:id" -> {
                DataStore.Receipt rc = store.voidReceipt(id);
                if (rc == null) throw new Http.Error(404, "Receipt not found");
                log.add(who, ip, "Voided receipt #" + id + " (" + describe(rc) + ", ₱" + DataStore.money(rc.total()) + ")");
                r.json(200, Json.obj("ok", true));
            }
            case "POST payments" -> {
                String receiptId = r.param("receiptId").isEmpty() ? null : r.param("receiptId");
                String customerId = r.param("customerId");
                if (receiptId != null) {
                    DataStore.Receipt rc = store.receipt(receiptId);
                    if (rc == null) throw new Http.Error(404, "Receipt not found");
                    customerId = rc.customerId();
                }
                DataStore.Customer c = store.customer(customerId);
                if (c == null) throw new Http.Error(400, "Choose a customer");
                double amount = number(r.param("amount"), "Amount", 0.01, 10_000_000);
                List<DataStore.Payment> parts = store.addPayment(c.id(), receiptId, DataStore.round(amount), who, limit(r.param("note"), 120, "Note"));
                log.add(who, ip, "Payment ₱" + DataStore.money(amount) + " from " + c.name() + " for receipt"
                        + (parts.size() == 1 ? " #" : "s #") + parts.stream().map(DataStore.Payment::receiptId).collect(Collectors.joining(", #")));
                r.json(201, Json.obj("ok", true));
            }

            // ---- stock
            case "POST stock/:id" -> stockChange(r, who, ip, id);
            case "POST suppliers" -> saveSupplier(r, who, ip, null);
            case "PUT suppliers/:id" -> saveSupplier(r, who, ip, id);
            case "DELETE suppliers/:id" -> {
                DataStore.Supplier sup = store.supplier(id);
                String why = store.deleteSupplier(id);
                if (why != null) throw new Http.Error(why.equals("Supplier not found") ? 404 : 409, why);
                log.add(who, ip, "Deleted supplier " + sup.name());
                r.json(200, Json.obj("ok", true));
            }
            case "POST suppliers/:id/pay" -> {
                DataStore.Supplier sup = store.supplier(id);
                if (sup == null) throw new Http.Error(404, "Supplier not found");
                DataStore.Paid paid = paidFields(r, who, 0);
                String note = limit(r.param("note"), 120, "Note");
                List<DataStore.SupplierPayment> parts = store.paySupplier(sup, paid, who, note);
                log.add(who, ip, "Paid ₱" + DataStore.money(paid.total()) + paidText(paid) + " to " + sup.name() + " ("
                        + parts.stream().map(x -> x.refillId().startsWith("P") ? "new tanks" : "trip #" + x.refillId()).distinct().collect(Collectors.joining(", "))
                        + ")" + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(201, Json.obj("ok", true));
            }
            case "POST staff/:id/payback" -> {
                if (store.user(id) == null && !store.owedToStaff().containsKey(id)) throw new Http.Error(404, "No such staff member");
                double amount = DataStore.round(number(r.param("amount"), "Amount", 0.01, 100_000_000));
                String note = limit(r.param("note"), 120, "Note");
                store.payBackStaff(id, amount, who, note);
                log.add(who, ip, "Paid back ₱" + DataStore.money(amount) + " to " + id + " (money they put in for suppliers)" + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(201, Json.obj("ok", true));
            }
            case "POST refills" -> {
                DataStore.Supplier sup = store.supplier(r.param("supplierId"));
                if (sup == null) throw new Http.Error(400, "Choose a refiller");
                Map<DataStore.Product, Integer> items = new LinkedHashMap<>();
                for (int i = 0; i < itemCount(r); i++) {
                    int qty = whole(r.param("qty." + i), "Tanks to send", 0, 1_000);
                    if (qty > 0) items.merge(productParam(r, "productId." + i), qty, Integer::sum);
                }
                if (items.isEmpty()) throw new Http.Error(400, "Enter how many empty tanks you're sending");
                for (DataStore.Product p : items.keySet()) {
                    if (!sup.supplies(p.brand())) {
                        throw new Http.Error(409, sup.name() + " only refills " + String.join(", ", sup.brands()) + ", not " + p.label() + ".");
                    }
                }
                String note = limit(r.param("note"), 120, "Note");
                DataStore.Refill rf = store.sendToRefiller(sup, items, who, note);
                log.add(who, ip, "Sent to " + sup.name() + " (trip #" + rf.id() + "): " + countText(items) + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(201, Json.obj("id", rf.id()));
            }
            case "POST refills/:id/receive" -> {
                DataStore.Refill rf = store.refills().stream().filter(x -> x.id().equals(id)).findFirst()
                        .orElseThrow(() -> new Http.Error(404, "Refill trip not found"));
                Map<DataStore.Product, int[]> items = new LinkedHashMap<>();
                Map<DataStore.Product, Double> costs = new HashMap<>();
                double cost = 0;
                for (int i = 0; i < itemCount(r); i++) {
                    int got = whole(r.param("received." + i), "Tanks back with load", 0, 1_000);
                    int bad = whole(r.param("rejected." + i), "Tanks returned unfilled", 0, 1_000);
                    if (got + bad == 0) continue;
                    DataStore.Product p = productParam(r, "productId." + i);
                    double each = r.param("cost." + i).isEmpty() ? p.refillCost() : DataStore.round(number(r.param("cost." + i), "Refill cost", 0, 1_000_000));
                    items.put(p, new int[]{got, bad});
                    costs.put(p, each);
                    cost += got * each;
                }
                if (items.isEmpty()) throw new Http.Error(400, "Enter how many tanks came back");
                DataStore.Paid paid = paidFields(r, who, DataStore.round(cost));
                String note = limit(r.param("note"), 120, "Note");
                store.receiveFromRefiller(rf, items, costs, paid, who, note);
                DataStore.Supplier sup = store.supplier(rf.supplierId());
                StringBuilder msg = new StringBuilder("Received from " + (sup == null ? "refiller" : sup.name()) + " (trip #" + rf.id() + "): ");
                List<String> parts = new ArrayList<>();
                items.forEach((p, v) -> parts.add(v[0] + " × " + p.label() + (v[1] > 0 ? " (+" + v[1] + " unfilled)" : "")));
                log.add(who, ip, msg + String.join(", ", parts) + " · cost ₱" + DataStore.money(cost) + ", paid ₱" + DataStore.money(paid.total()) + paidText(paid)
                        + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(200, Json.obj("ok", true));
            }
            case "POST refills/:id/pay" -> {
                DataStore.Refill rf = store.refills().stream().filter(x -> x.id().equals(id)).findFirst()
                        .orElseThrow(() -> new Http.Error(404, "Refill trip not found"));
                DataStore.Paid paid = paidFields(r, who, 0);
                String note = limit(r.param("note"), 120, "Note");
                store.payRefiller(rf, paid, who, note);
                DataStore.Supplier sup = store.supplier(rf.supplierId());
                log.add(who, ip, "Paid ₱" + DataStore.money(paid.total()) + paidText(paid) + " to " + (sup == null ? "refiller" : sup.name()) + " for trip #" + rf.id()
                        + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(201, Json.obj("ok", true));
            }
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
    }

    // ---------------------------------------------------------------- stock

    private void stockChange(Http.Req r, String who, String ip, String kind) throws IOException {
        DataStore.Product p = productParam(r, "productId");
        String note = limit(r.param("note"), 120, "Note");
        String suffix = note.isEmpty() ? "" : " (" + note + ")";
        switch (kind) {
            case "purchase" -> {
                int qty = whole(r.param("qty"), "Tanks bought", 1, 1_000);
                double cost = r.param("unitCost").isEmpty() ? p.tankCost() : DataStore.round(number(r.param("unitCost"), "Cost per tank", 0, 1_000_000));
                double bill = DataStore.round(qty * cost);
                DataStore.Paid paid = paidFields(r, who, bill);
                store.purchase(p, qty, cost, paid, who, note);
                log.add(who, ip, "Bought " + qty + " new " + p.label() + " tanks with load"
                        + (cost > 0 ? " for ₱" + DataStore.money(bill) + (paid.total() < bill - 0.005 ? " (paid ₱" + DataStore.money(paid.total()) + ", rest later)" : "") + paidText(paid) : "") + suffix);
            }
            case "adjust" -> {
                int loaded = whole(r.param("loaded"), "Tanks with load", 0, 10_000);
                int empty = whole(r.param("empty"), "Empty tanks", 0, 10_000);
                Integer damaged = r.param("damaged").isEmpty() ? null : whole(r.param("damaged"), "Damaged tanks", 0, 10_000);
                String reason = r.param("reason");
                if (!List.of("count", "damaged", "other").contains(reason)) throw new Http.Error(400, "Choose a reason");
                if (!reason.equals("count") && note.isEmpty()) throw new Http.Error(400, "Add a note saying what happened");
                DataStore.Stock before = store.adjustTo(p, reason, loaded, empty, damaged, who, note);
                if (before == null) throw new Http.Error(400, "Those are the current counts; nothing to change");
                log.add(who, ip, (reason.equals("count") ? "Stock count" : reason.equals("damaged") ? "Wrote off stock" : "Adjusted stock")
                        + " for " + p.label() + ": with load " + before.loaded() + " → " + loaded + ", empty " + before.empty() + " → " + empty + suffix);
            }
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
        r.json(200, Json.obj("ok", true));
    }

    private void saveSupplier(Http.Req r, String who, String ip, String id) throws IOException {
        String name = limit(r.param("name"), 80, "Name");
        if (name.isEmpty()) throw new Http.Error(400, "Supplier name is required");
        // A supplier refills only its own brand (one of the products' brands), and each brand has one supplier.
        String wanted = r.param("brand").trim();
        if (wanted.isEmpty()) throw new Http.Error(400, "Choose the brand this supplier refills");
        String brand = store.products().stream().map(DataStore.Product::brand).filter(b -> b.equalsIgnoreCase(wanted))
                .findFirst().orElseThrow(() -> new Http.Error(400, "No LPG product has the brand " + wanted));
        for (DataStore.Supplier other : store.suppliers()) {
            if (!other.id().equals(id) && other.supplies(brand)) throw new Http.Error(409, brand + " is already assigned to " + other.name() + ".");
        }
        if (id != null) {
            DataStore.Supplier old = store.supplier(id);
            if (old != null && !old.supplies(brand) && store.hasTanksOut(id)) {
                throw new Http.Error(409, "Tanks are still at " + old.name() + ". Receive them before changing its brand.");
            }
        }
        DataStore.Supplier s = store.saveSupplier(id, name, limit(r.param("contact"), 60, "Contact"), List.of(brand), limit(r.param("note"), 120, "Note"));
        if (s == null) throw new Http.Error(404, "Supplier not found");
        log.add(who, ip, (id == null ? "Added supplier " : "Edited supplier ") + s.name() + " (" + String.join(", ", s.brands()) + ")");
        r.json(id == null ? 201 : 200, Json.obj("id", s.id()));
    }

    private void requireDashboard(String who) {
        // Only staff the admin has chosen may see the business summary.
        DataStore.User u = store.user(who);
        if (u == null || !u.dashboard()) throw new Http.Error(403, "Ask the admin for access to the dashboard");
    }

    /**
     * Money handed to a supplier, by source: paidSales (store money) and paidStaff (a staff member's own
     * money, paidBy; the store owes it back). Older clients send one "amount" or "paid", taken as sales money.
     * With nothing sent, the whole bill (fallback) counts as paid from sales.
     */
    private DataStore.Paid paidFields(Http.Req r, String who, double fallback) throws IOException {
        boolean split = !r.param("paidSales").isEmpty() || !r.param("paidStaff").isEmpty();
        String single = !r.param("amount").isEmpty() ? r.param("amount") : r.param("paid");
        double sales = split ? (r.param("paidSales").isEmpty() ? 0 : number(r.param("paidSales"), "Paid from sales", 0, 100_000_000))
                : single.isEmpty() ? fallback : number(single, "Amount paid", 0, 100_000_000);
        double staff = r.param("paidStaff").isEmpty() ? 0 : number(r.param("paidStaff"), "Paid from staff money", 0, 100_000_000);
        String by = r.param("paidBy").isEmpty() ? who : r.param("paidBy");
        if (staff > 0 && store.user(by) == null) throw new Http.Error(400, "Choose whose money it was");
        return new DataStore.Paid(DataStore.round(sales), DataStore.round(staff), staff > 0 ? by : "");
    }

    private static String paidText(DataStore.Paid p) {
        if (p.staff() <= 0) return "";
        return " (₱" + DataStore.money(p.sales()) + " from sales, ₱" + DataStore.money(p.staff()) + " from " + p.byStaff() + "'s own money)";
    }

    static String stockStatus(DataStore.Stock st, int reorderLevel) {
        if (st.loaded() <= 0) return "out";
        return st.loaded() <= reorderLevel ? "low" : "ok";
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
        DataStore.User u = store.user(s.username);
        return Json.obj("username", s.username, "demo", autoUser != null, "dashboard", u != null && u.dashboard());
    }

    // ---------------------------------------------------------------- data

    private Map<String, Object> data() {
        List<DataStore.Customer> customers = store.customers();
        List<DataStore.Product> products = store.products();
        List<DataStore.Receipt> receipts = store.receipts();
        List<DataStore.Payment> payments = store.payments();
        List<DataStore.Supplier> suppliers = store.suppliers();
        Map<String, DataStore.Customer> custById = customers.stream()
                .collect(Collectors.toMap(DataStore.Customer::id, Function.identity(), (a, b) -> a));
        Map<String, DataStore.Product> prodById = products.stream()
                .collect(Collectors.toMap(DataStore.Product::id, Function.identity(), (a, b) -> a));
        Map<String, DataStore.Stock> stock = store.stock();
        Map<String, Double> paid = store.paidByReceipt(receipts);
        Map<String, List<DataStore.Payment>> payByReceipt = payments.stream().collect(Collectors.groupingBy(DataStore.Payment::receiptId));
        Function<String, String> productName = pid -> {
            DataStore.Product p = prodById.get(pid);
            return p == null ? "Unknown product #" + pid : p.label();
        };

        Map<String, Integer> receiptsByCustomer = new HashMap<>();
        Map<String, String> lastByCustomer = new HashMap<>();
        Map<String, Double> owedByCustomer = new HashMap<>();
        Map<String, Integer> soldByProduct = new HashMap<>();
        List<Object> receiptOut = new ArrayList<>();
        for (int i = receipts.size() - 1; i >= 0; i--) { // newest first
            DataStore.Receipt rc = receipts.get(i);
            double bal = DataStore.balance(rc, paid);
            double got = DataStore.round(paid.getOrDefault(rc.id(), 0.0));
            receiptsByCustomer.merge(rc.customerId(), 1, Integer::sum);
            lastByCustomer.putIfAbsent(rc.customerId(), rc.rawTime()); // newest first, so the first one seen is the latest
            if (bal > 0) owedByCustomer.merge(rc.customerId(), bal, Double::sum);
            List<Object> lineOut = new ArrayList<>();
            for (DataStore.Line l : rc.lines()) {
                soldByProduct.merge(l.productId(), l.qty(), Integer::sum);
                lineOut.add(Json.obj("productId", l.productId(), "product", productName.apply(l.productId()), "qty", l.qty(),
                        "unitPrice", l.unitPrice(), "amount", DataStore.round(l.amount()),
                        "emptyProductId", l.emptyProductId(), "emptyProduct", l.returnedEmpty() ? productName.apply(l.emptyProductId()) : null,
                        "owesTank", l.owesTank(), "newTank", l.newTank(), "swapFee", l.swapFee(), "remark", l.remark()));
            }
            List<Object> payOut = new ArrayList<>();
            for (DataStore.Payment p : payByReceipt.getOrDefault(rc.id(), List.of())) {
                payOut.add(Json.obj("time", p.rawTime(), "amount", p.amount(), "staff", p.staff(), "note", p.note()));
            }
            DataStore.Customer c = custById.get(rc.customerId());
            receiptOut.add(Json.obj(
                    "id", rc.id(), "time", rc.rawTime(), "customerId", rc.customerId(),
                    "customer", c == null ? "Unknown customer #" + rc.customerId() : c.name(),
                    "staff", rc.staff().isEmpty() ? "Desktop app" : rc.staff(), "lines", lineOut,
                    "subtotal", rc.subtotal(), "discount", rc.discount(), "total", rc.total(),
                    "paidAtSale", rc.paidAtSale(), "paid", got, "balance", bal,
                    "status", bal <= 0 ? "paid" : got <= 0.005 ? "unpaid" : "partial",
                    "note", rc.note(), "payments", payOut));
        }

        Map<String, List<DataStore.OwedTank>> owedTanks = store.owedTanks();
        List<Object> custOut = new ArrayList<>();
        for (DataStore.Customer c : customers) {
            List<Object> owedOut = new ArrayList<>();
            int tanks = 0;
            for (DataStore.OwedTank o : owedTanks.getOrDefault(c.id(), List.of())) {
                tanks += o.qty();
                owedOut.add(Json.obj("productId", o.productId(), "product", productName.apply(o.productId()), "qty", o.qty(),
                        "receiptId", o.receiptId(), "time", o.rawTime()));
            }
            custOut.add(Json.obj("id", c.id(), "firstName", c.firstName(), "lastName", c.lastName(),
                    "contact", c.contact(), "address", c.address(), "receipts", receiptsByCustomer.getOrDefault(c.id(), 0),
                    "balance", DataStore.round(owedByCustomer.getOrDefault(c.id(), 0.0)),
                    "lastPurchase", lastByCustomer.get(c.id()), "tanksOwed", tanks, "owedTanks", owedOut));
        }
        List<Object> prodOut = new ArrayList<>();
        for (DataStore.Product p : products) {
            DataStore.Stock st = stock.getOrDefault(p.id(), DataStore.Stock.NONE);
            DataStore.Supplier sup = suppliers.stream().filter(x -> x.supplies(p.brand())).findFirst().orElse(null);
            prodOut.add(Json.obj("id", p.id(), "brand", p.brand(), "weight", p.weight(), "label", p.label(),
                    "price", p.price(), "swapFee", p.swapFee(), "refillCost", p.refillCost(), "tankCost", p.tankCost(),
                    "reorderLevel", p.reorderLevel(), "sold", soldByProduct.getOrDefault(p.id(), 0),
                    "loaded", st.loaded(), "empty", st.empty(), "damaged", st.damaged(), "atRefiller", st.atRefiller(),
                    "status", stockStatus(st, p.reorderLevel()),
                    "supplierId", sup == null ? null : sup.id(), "supplier", sup == null ? null : sup.name()));
        }

        List<DataStore.Movement> moves = store.movements();
        List<Object> moveOut = new ArrayList<>();
        for (int i = moves.size() - 1; i >= 0 && moveOut.size() < 60; i--) {
            DataStore.Movement m = moves.get(i);
            moveOut.add(Json.obj("id", m.id(), "time", m.rawTime(), "productId", m.productId(), "product", productName.apply(m.productId()),
                    "type", m.type(), "loaded", m.loaded(), "empty", m.empty(), "damaged", m.damaged(), "atRefiller", m.atRefiller(),
                    "staff", m.staff(), "note", m.note(), "refillId", m.refillId(), "unitCost", m.unitCost()));
        }

        Map<String, DataStore.Supplier> supById = suppliers.stream()
                .collect(Collectors.toMap(DataStore.Supplier::id, Function.identity(), (a, b) -> a));
        List<DataStore.Bill> bills = store.bills();
        List<Object> supOut = new ArrayList<>();
        for (DataStore.Supplier sup : suppliers) {
            List<Object> unpaid = new ArrayList<>();
            double owed = 0;
            for (DataStore.Bill b : bills) {
                if (!b.supplierId().equals(sup.id()) || b.owed() <= 0) continue;
                owed += b.owed();
                unpaid.add(Json.obj("label", b.label(), "time", b.rawTime(), "cost", b.cost(), "paid", b.paid(), "owed", b.owed()));
            }
            supOut.add(Json.obj("id", sup.id(), "name", sup.name(), "contact", sup.contact(), "brands", sup.brands(), "note", sup.note(),
                    "owed", DataStore.round(owed), "bills", unpaid));
        }
        List<DataStore.TankReturn> returns = store.tankReturns();
        List<Object> returnOut = new ArrayList<>();
        for (int i = returns.size() - 1; i >= 0 && returnOut.size() < 30; i--) {
            DataStore.TankReturn t = returns.get(i);
            DataStore.Customer c = custById.get(t.customerId());
            returnOut.add(Json.obj("time", t.rawTime(), "customerId", t.customerId(), "customer", c == null ? "Deleted customer #" + t.customerId() : c.name(),
                    "productId", t.productId(), "product", productName.apply(t.productId()), "qty", t.qty(), "staff", t.staff(), "remark", t.remark()));
        }
        List<DataStore.SupplierPayment> supplierPays = store.supplierPayments();
        List<DataStore.Refill> refills = store.refills();
        List<Object> refillOut = new ArrayList<>();
        int closedShown = 0;
        for (int i = refills.size() - 1; i >= 0; i--) {
            DataStore.Refill rf = refills.get(i);
            List<DataStore.RefillItem> items = store.refillItems(rf.id());
            boolean open = items.stream().anyMatch(x -> x.outstanding() > 0);
            double cost = store.refillCost(rf.id()), paidOut = store.refillPaid(rf.id());
            double paidStaff = DataStore.round(supplierPays.stream().filter(p -> rf.id().equals(p.refillId()) && p.fromStaff()).mapToDouble(DataStore.SupplierPayment::amount).sum());
            double owed = DataStore.round(Math.max(0, cost - paidOut));
            if (!open && owed <= 0 && closedShown++ >= 10) continue;
            List<Object> itemOut = new ArrayList<>();
            for (DataStore.RefillItem it : items) {
                itemOut.add(Json.obj("productId", it.productId(), "product", productName.apply(it.productId()),
                        "sent", it.sent(), "received", it.received(), "rejected", it.rejected(), "outstanding", it.outstanding()));
            }
            DataStore.Supplier sup = supById.get(rf.supplierId());
            refillOut.add(Json.obj("id", rf.id(), "time", rf.rawTime(), "supplierId", rf.supplierId(),
                    "supplier", sup == null ? "Deleted supplier" : sup.name(), "staff", rf.staff(), "note", rf.note(),
                    "open", open, "items", itemOut, "cost", cost, "paid", paidOut, "paidStaff", paidStaff, "owed", owed));
        }
        List<String> brands = products.stream().map(DataStore.Product::brand).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        return Json.obj("customers", custOut, "products", prodOut, "receipts", receiptOut, "movements", moveOut,
                "suppliers", supOut, "refills", refillOut, "brands", brands, "returns", returnOut,
                "staff", store.users().stream().map(DataStore.User::username).toList(), "staffMoney", Stats.staffMoney(store));
    }

    /** data() for one viewer: adds their own permissions, which the ETag also covers (WebUsers.csv is in it). */
    private Map<String, Object> data(String username) {
        Map<String, Object> d = data();
        DataStore.User u = store.user(username);
        d.put("me", Json.obj("dashboard", u != null && u.dashboard()));
        return d;
    }

    // ---------------------------------------------------------------- describing receipts

    private String describe(DataStore.Receipt rc) {
        Map<String, DataStore.Product> prods = store.productMap();
        List<String> parts = new ArrayList<>();
        for (DataStore.Line l : rc.lines()) {
            DataStore.Product p = prods.get(l.productId());
            String label = p == null ? "#" + l.productId() : p.label();
            String swap = l.owesTank() ? " (no empty, owes the tank)" : l.newTank() ? " (new tank)"
                    : !l.emptyProductId().equals(l.productId()) ? " (empty " + Optional.ofNullable(prods.get(l.emptyProductId())).map(DataStore.Product::label).orElse("?")
                    + (l.swapFee() > 0 ? ", swap fee ₱" + DataStore.money(l.swapFee()) + " each" : "") + ")"
                    : "";
            parts.add(l.qty() + " × " + label + swap + (l.remark().isEmpty() ? "" : " [" + l.remark() + "]"));
        }
        return String.join(", ", parts);
    }

    private static String paymentText(DataStore.Receipt rc, double paid) {
        double total = rc.total();
        if (paid >= total - 0.005) return ", ₱" + DataStore.money(total) + " paid";
        if (paid <= 0.005) return ", ₱" + DataStore.money(total) + " to pay later";
        return ", ₱" + DataStore.money(total) + " (paid ₱" + DataStore.money(paid) + ", ₱" + DataStore.money(total - paid) + " to pay later)";
    }

    private static String countText(Map<DataStore.Product, Integer> items) {
        return items.entrySet().stream().map(e -> e.getValue() + " × " + e.getKey().label()).collect(Collectors.joining(", "));
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

    private record ProductFields(String brand, double price, double weight, int reorderLevel,
                                 double refillCost, double tankCost, double swapFee) {
        DataStore.Product toProduct(String id, double tankPrice) {
            return new DataStore.Product(id, brand, price, weight, reorderLevel, tankPrice, refillCost, tankCost, swapFee);
        }
    }

    private static ProductFields productFields(Http.Req r) throws IOException {
        String brand = limit(r.param("brand"), 60, "Brand");
        if (brand.isEmpty()) throw new Http.Error(400, "Brand is required");
        if (brand.contains(";")) throw new Http.Error(400, "Brand can't contain a semicolon");
        double price = number(r.param("price"), "Refill price", 0.01, 1_000_000);
        double weight = number(r.param("weight"), "Weight", 0.1, 1_000);
        int reorder = whole(r.param("reorderLevel").isEmpty() ? "5" : r.param("reorderLevel"), "Reorder level", 0, 1_000);
        double refillCost = r.param("refillCost").isEmpty() ? 0 : number(r.param("refillCost"), "Refill cost", 0, 1_000_000);
        double tankCost = r.param("tankCost").isEmpty() ? 0 : number(r.param("tankCost"), "New tank cost", 0, 1_000_000);
        double swapFee = r.param("swapFee").isEmpty() ? 0 : number(r.param("swapFee"), "Swap fee", 0, 1_000_000);
        return new ProductFields(brand, DataStore.round(price), weight, reorder,
                DataStore.round(refillCost), DataStore.round(tankCost), DataStore.round(swapFee));
    }

    private record ReceiptFields(DataStore.Customer customer, List<DataStore.LineInput> items, double discount, double paid, String note) {}

    private ReceiptFields receiptFields(Http.Req r) throws IOException {
        DataStore.Customer c = store.customer(r.param("customerId"));
        if (c == null) throw new Http.Error(400, "Choose a customer");
        int n = itemCount(r);
        if (n == 0) throw new Http.Error(400, "Add at least one LPG tank to the receipt");
        List<DataStore.LineInput> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            DataStore.Product p = productParam(r, "productId." + i);
            int qty = whole(r.param("qty." + i), "Quantity", 1, 100);
            double unit = r.param("unitPrice." + i).isEmpty() ? -1 : number(r.param("unitPrice." + i), "Price", 0, 1_000_000);
            // The empty that came in: the same product, another brand of the same size (a swap), or
            // none, in which case the customer owes the tank and brings it back later.
            String emptyId = r.param("emptyId." + i);
            double fee = 0;
            if (!emptyId.isEmpty()) {
                DataStore.Product e = store.product(emptyId);
                if (e == null) throw new Http.Error(400, "Unknown empty tank type");
                if (e.weight() != p.weight()) throw new Http.Error(400, "The empty for " + p.label() + " must be the same size (" + DataStore.fmtKg(p.weight()) + " kg)");
                if (!e.brand().equalsIgnoreCase(p.brand())) {
                    fee = r.param("swapFee." + i).isEmpty() ? p.swapFee() : number(r.param("swapFee." + i), "Swap fee", 0, 1_000_000);
                }
            }
            String remark = limit(r.param("remark." + i), 80, "Remark");
            if (unit < 0) unit = p.price();
            items.add(new DataStore.LineInput(p, qty, DataStore.round(unit), emptyId, remark, DataStore.round(fee)));
        }
        double discount = r.param("discount").isEmpty() ? 0 : number(r.param("discount"), "Discount", 0, 10_000_000);
        double subtotal = items.stream().mapToDouble(DataStore.LineInput::amount).sum();
        double total = DataStore.round(Math.max(0, subtotal - discount));
        double paid = r.param("paid").isEmpty() ? total : number(r.param("paid"), "Amount paid", 0, 10_000_000);
        return new ReceiptFields(c, items, DataStore.round(discount), DataStore.round(paid), limit(r.param("note"), 160, "Note"));
    }

    private static int itemCount(Http.Req r) throws IOException {
        return whole(r.param("items").isEmpty() ? "0" : r.param("items"), "Number of items", 0, MAX_ITEMS);
    }

    private DataStore.Product productParam(Http.Req r, String name) throws IOException {
        DataStore.Product p = store.product(r.param(name));
        if (p == null) throw new Http.Error(400, "Choose an LPG product");
        return p;
    }

    private static String limit(String v, int max, String label) {
        if (v.length() > max) throw new Http.Error(400, label + " is too long (max " + max + " characters)");
        return v;
    }

    /** Blank counts as 0. */
    private static int whole(String v, String label, double min, double max) {
        double d = number(v.isEmpty() ? "0" : v, label, min, max);
        if (d != Math.rint(d)) throw new Http.Error(400, label + " must be a whole number");
        return (int) d;
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

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One store's POS JSON API. PosGate signs people in and hands each call to the PosApi of the caller's own business.
 */
final class PosApi {
    private static final int MAX_ITEMS = 30;
    /** How long staff without dashboard access can undo what they entered themselves. */
    static final int UNDO_MINUTES = 30;

    private final DataStore store;
    private final Auth auth;
    private final ActivityLog log;

    PosApi(DataStore store, Auth auth, ActivityLog log) {
        this.store = store;
        this.auth = auth;
        this.log = log;
    }

    /** One API call for this store, from session s (PosGate has checked the account and business are active). */
    void handle(Http.Req r, Auth.Session s) throws IOException {
        if (store.user(s.username) == null) {
            auth.end(s.token);
            throw new Http.Error(401, "Please sign in");
        }

        String[] seg = r.path.substring("/api/".length()).split("/");
        String id = seg.length > 1 ? seg[1] : null;
        String action = seg.length > 2 ? seg[2] : null;
        String route = r.method + " " + seg[0] + (id == null ? "" : "/:id") + (action == null ? "" : "/" + action);
        String who = s.username, ip = r.clientIp();
        // Entries made later for an earlier day (only staff with dashboard access): dated that day.
        LocalDate day = recordDate(r, who);
        if (day != null) {
            DataStore.recordOn(day);
            log.add(who, ip, "Entering a record dated " + day + " (" + route.replace(":id", id == null ? "" : id) + ")");
        }
        try {
            String replaceKind = r.method.equals("POST") ? r.param("replaceKind") : "";
            if (replaceKind.isEmpty()) route(r, route, id, s, who, ip);
            else edit(r, route, id, s, who, ip, day, replaceKind, r.param("replaceId"));
        } finally {
            DataStore.recordNow();
        }
    }

    /** The day an entry is for, when it isn't today: recordDate "YYYY-MM-DD" on a change, up to a year back. */
    private LocalDate recordDate(Http.Req r, String who) throws IOException {
        if (r.method.equals("GET")) return null;
        String v = r.param("recordDate").trim();
        if (v.isEmpty()) return null;
        LocalDate day, today = LocalDate.now();
        try {
            day = LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new Http.Error(400, "The date must look like " + today);
        }
        if (day.equals(today)) return null;
        if (day.isAfter(today)) throw new Http.Error(400, "The date can't be in the future");
        if (day.isBefore(today.minusYears(1))) throw new Http.Error(400, "The date can't be more than a year ago");
        DataStore.User u = store.user(who);
        if (u == null || !u.dashboard()) throw new Http.Error(403, "Only staff with dashboard access can enter records for another day");
        return day;
    }

    /**
     * A corrected date for an existing record ("date", YYYY-MM-DD): null when not sent or unchanged. Only staff with
     * dashboard access; not in the future or more than a year back.
     */
    private LocalDate newDate(Http.Req r, String who, LocalDateTime current) throws IOException {
        String v = r.param("date").trim();
        if (v.isEmpty()) return null;
        LocalDate day, today = LocalDate.now();
        try {
            day = LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new Http.Error(400, "The date must look like " + today);
        }
        if (current != null && day.equals(current.toLocalDate())) return null;
        if (day.isAfter(today)) throw new Http.Error(400, "The date can't be in the future");
        if (day.isBefore(today.minusYears(1))) throw new Http.Error(400, "The date can't be more than a year ago");
        DataStore.User u = store.user(who);
        if (u == null || !u.dashboard()) throw new Http.Error(403, "Only staff with dashboard access can change a record's date");
        return day;
    }

    private void route(Http.Req r, String route, String id, Auth.Session s, String who, String ip) throws IOException {
        switch (route) {
            case "GET summary" -> {
                requireDashboard(who);
                String range = r.query().getOrDefault("range", "7");
                if (!List.of("today", "7", "30", "90", "all").contains(range)) range = "7";
                r.json(200, Json.obj("stats", Stats.compute(store, range), "inventory", Stats.inventory(store),
                        "receivables", Stats.receivables(store), "tanksOut", Stats.tanksOut(store), "staffMoney", Stats.staffMoney(store)));
            }
            case "GET explain" -> {
                // Staff see how the dashboard's range figures are worked out (not the admin-only supplier summary).
                // Balances every staff member already sees in the POS (a customer's, a supplier's, stock) need no dashboard access.
                String metric = r.query().getOrDefault("metric", "");
                if (!Set.of("customer-balance", "receivables", "supplier-owed", "stock").contains(metric)) requireDashboard(who);
                if (!Set.of("revenue", "count", "qty", "collected", "supplierPaid", "profit", "cashLeft", "drawer", "expenses", "salaries",
                        "customer-balance", "receivables", "supplier-owed", "stock", "salary-owed").contains(metric)) {
                    throw new Http.Error(400, "Unknown figure: " + metric);
                }
                r.json(200, Explain.explain(store, Map.of("metric", metric, "range", r.query().getOrDefault("range", "7"),
                        "date", r.query().getOrDefault("date", ""), "id", r.query().getOrDefault("id", ""))));
            }
            case "GET daily" -> {
                requireDashboard(who);
                r.json(200, Stats.daily(store, r.query().getOrDefault("date", ""), 14));
            }
            case "GET reports/:id" -> {
                requireDashboard(who);
                Map<String, String> q = r.query();
                String period = q.getOrDefault("period", "day"), from = q.getOrDefault("from", ""), to = q.getOrDefault("to", "");
                LocalDateTime now = LocalDateTime.now();
                r.json(200, switch (id) {
                    case "sales" -> Reports.sales(store, period, from, to, now);
                    case "suppliers" -> Reports.suppliers(store, period, q.getOrDefault("supplier", ""), from, to, now);
                    case "money" -> Reports.money(store, period, from, to, now);
                    default -> throw new Http.Error(404, "Unknown report");
                });
            }
            case "POST expenses" -> {
                requireDashboard(who);
                String category = r.param("category");
                if (!DataStore.EXPENSE_CATEGORIES.contains(category)) throw new Http.Error(400, "Choose what the expense was for");
                double amount = r.param("amount").isEmpty() ? 0 : DataStore.round(number(r.param("amount"), "Amount", 0, 100_000_000));
                double deducted = r.param("advanceDeducted").isEmpty() ? 0 : DataStore.round(number(r.param("advanceDeducted"), "Advance taken off", 0, 100_000_000));
                String method = r.param("method").isEmpty() ? "cash" : r.param("method");
                if (!List.of("cash", "online", "check").contains(method)) throw new Http.Error(400, "Choose cash, online or check");
                String employee = limit(r.param("employee").trim(), 60, "Employee");
                String note = limit(r.param("note"), 120, "Note");
                LocalDate payday = null;
                if (!r.param("payday").isBlank()) {
                    try { payday = LocalDate.parse(r.param("payday").trim()); } catch (java.time.format.DateTimeParseException x) { throw new Http.Error(400, "Choose the pay day"); }
                }
                DataStore.Expense e = store.addExpense(category, amount, employee, method, who, note, deducted, payday);
                log.add(who, ip, "Expense #" + e.id() + ": " + Explain.EXPENSE_LABEL.get(category) + (employee.isEmpty() || e.employee().isEmpty() ? "" : " for " + e.employee())
                        + " ₱" + DataStore.money(amount) + (method.equals("cash") ? "" : " by " + method)
                        + (e.advanceDeducted() > 0 ? " (₱" + DataStore.money(e.advanceDeducted()) + " advance taken off)" : "")
                        + (e.payday().isEmpty() ? "" : " for pay day " + e.payday()) + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(201, Json.obj("id", e.id()));
            }
            case "PUT expenses/:id" -> {
                requireDashboard(who);
                DataStore.Expense before = store.expenses().stream().filter(x -> x.id().equals(id)).findFirst()
                        .orElseThrow(() -> new Http.Error(404, "Expense not found"));
                String category = r.param("category");
                if (!DataStore.EXPENSE_CATEGORIES.contains(category)) throw new Http.Error(400, "Choose what the expense was for");
                double amount = r.param("amount").isEmpty() ? 0 : DataStore.round(number(r.param("amount"), "Amount", 0, 100_000_000));
                double deducted = r.param("advanceDeducted").isEmpty() ? 0 : DataStore.round(number(r.param("advanceDeducted"), "Advance taken off", 0, 100_000_000));
                String method = r.param("method").isEmpty() ? "cash" : r.param("method");
                if (!List.of("cash", "online", "check").contains(method)) throw new Http.Error(400, "Choose cash, online or check");
                LocalDate day = newDate(r, who, before.time());
                store.updateExpense(id, category, amount, limit(r.param("employee").trim(), 60, "Employee"), method, limit(r.param("note"), 120, "Note"), deducted, day);
                log.add(who, ip, "Edited expense #" + id + " (was " + Explain.EXPENSE_LABEL.getOrDefault(before.category(), before.category())
                        + (before.employee().isEmpty() ? "" : " for " + before.employee()) + " ₱" + DataStore.money(before.amount()) + " on " + before.rawTime()
                        + "; now " + Explain.EXPENSE_LABEL.get(category) + " ₱" + DataStore.money(amount) + (day == null ? "" : " on " + day) + ")");
                r.json(200, Json.obj("ok", true));
            }
            case "DELETE expenses/:id" -> {
                requireDashboard(who);
                DataStore.Expense e = store.deleteExpense(id);
                if (e == null) throw new Http.Error(404, "Expense not found");
                log.add(who, ip, "Deleted expense #" + id + " (" + Explain.EXPENSE_LABEL.getOrDefault(e.category(), e.category())
                        + (e.employee().isEmpty() ? "" : " for " + e.employee()) + " ₱" + DataStore.money(e.amount()) + ", dated " + e.rawTime() + ")");
                r.json(200, Json.obj("ok", true));
            }
            case "POST undo" -> {
                // Staff can undo what they entered in the last UNDO_MINUTES; staff with dashboard access, anything.
                String kind = r.param("kind"), recordId = r.param("id");
                if (!DataStore.UNDO_KINDS.contains(kind)) throw new Http.Error(400, "That can't be undone here");
                mayChange(kind, recordId, who, "undo");
                DataStore.Undone done = store.undo(kind, recordId);
                log.add(who, ip, "Undid: " + done.what() + " (entered by " + (done.staff().isEmpty() ? "?" : done.staff()) + ", "
                        + (done.time() == null ? "?" : done.time().format(DataStore.TIME)) + "). Removed " + done.rows());
                r.json(200, Json.obj("ok", true, "what", done.what()));
            }
            case "GET record" -> {
                // What a record holds, to fill in its form for editing.
                String kind = r.query().getOrDefault("kind", ""), recordId = r.query().getOrDefault("id", "");
                mayChange(kind, recordId, who, "change");
                r.json(200, recordDetails(kind, recordId));
            }
            case "GET payroll" -> {
                requireDashboard(who);
                r.json(200, store.payroll(LocalDate.now()));
            }
            case "POST employees", "PUT employees/:id" -> {
                requireDashboard(who);
                boolean add = r.method.equals("POST");
                String name = limit(r.param("name").trim(), 60, "Name");
                double salary = DataStore.round(number(r.param("salary"), "Salary", 0, 10_000_000));
                String schedule = DataStore.schedule(r.param("schedule"));
                int day1 = whole(r.param("day1"), "Pay day", 1, 31);
                int day2 = r.param("day2").isEmpty() ? 0 : whole(r.param("day2"), "Second pay day", 1, 31);
                DataStore.Employee e = store.saveEmployee(add ? null : id, name, salary, schedule, day1, day2, limit(r.param("note"), 120, "Note"),
                        "1".equals(r.param("salaryAll")));
                if (e == null) throw new Http.Error(404, "Employee not found");
                log.add(who, ip, (add ? "Added " : "Edited ") + "employee " + e.name() + ": salary ₱" + DataStore.money(e.salary()) + ", " + e.schedule());
                r.json(add ? 201 : 200, Json.obj("id", e.id()));
            }
            case "DELETE employees/:id" -> {
                requireDashboard(who);
                DataStore.Employee e = store.deleteEmployee(id);
                if (e == null) throw new Http.Error(404, "Employee not found");
                log.add(who, ip, "Removed employee " + e.name() + " from the payroll");
                r.json(200, Json.obj("ok", true));
            }
            case "GET opening" -> {
                requireDashboard(who);
                r.json(200, openingJson());
            }
            case "POST opening" -> {
                requireDashboard(who);
                DataStore.Opening before = store.opening();
                DataStore.Opening o = openingFields(r);
                store.saveOpening(o, who);
                log.add(who, ip, (before == null ? "Entered" : "Changed") + " the starting balances for " + o.start() + ": cash ₱" + DataStore.money(o.cash())
                        + ", " + o.stock().values().stream().mapToInt(v -> v[0]).sum() + " tanks with load, " + o.stock().values().stream().mapToInt(v -> v[1]).sum() + " empty, "
                        + tankCount(o.atRefiller()) + " at refillers, customers owe ₱" + DataStore.money(sum(o.customerMoney())) + " and " + tankCount(o.customerTanks())
                        + " tanks, " + tankCount(o.deposits()) + " customer tanks kept, owed to suppliers ₱" + DataStore.money(sum(o.suppliers()))
                        + ", advances ₱" + DataStore.money(sum(o.advances()))
                        + (before == null ? "" : " (was " + before.start() + ", cash ₱" + DataStore.money(before.cash()) + ")"));
                r.json(200, Json.obj("ok", true));
            }
            case "POST me/:id" -> changePassword(r, s, id);
            case "GET data" -> {
                String etag = "\"" + store.dir().getFileName() + "-" + store.version() + '"';
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
                String kind = DataStore.emptyKind(r.param("emptyKind"));
                String brand = kind.equals("other") ? limit(r.param("emptyBrand").trim(), 40, "Brand") : "";
                if (kind.equals("other") && brand.isEmpty()) throw new Http.Error(400, "Type the brand of the empty");
                DataStore.TankReturn t = store.returnTanks(c, p, qty, who, remark, kind, brand);
                log.add(who, ip, c.name() + " returned " + qty + " empty " + (kind.isEmpty() ? p.label() : DataStore.looseLabel(kind, brand) + " " + DataStore.fmtKg(p.weight()) + " kg")
                        + (remark.isEmpty() ? "" : " [" + remark + "]"));
                r.json(201, Json.obj("ok", true, "undo", undo("return", t.id())));
            }
            case "POST customers/:id/deposits" -> {
                DataStore.Customer c = store.customer(id);
                if (c == null) throw new Http.Error(404, "Customer not found");
                DataStore.Product p = productParam(r, "productId");
                int qty = whole(r.param("qty"), "Tanks", 1, 1_000);
                String kind = r.param("kind");
                String note = limit(r.param("note"), 120, "Note");
                DataStore.Deposit d = store.addDeposit(c, p, qty, kind, who, note);
                log.add(who, ip, (kind.equals("back") ? "Gave back " + qty + " of " + c.name() + "'s own " + p.label() + " tanks"
                        : c.name() + " left " + qty + " own " + p.label() + " tank" + (qty == 1 ? "" : "s") + " with the store") + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(201, Json.obj("ok", true, "undo", undo("deposit", d.id())));
            }
            case "DELETE customers/:id" -> {
                requireManager(who, "delete a customer");
                int result = store.deleteCustomer(id);
                if (result == -2) throw new Http.Error(409, "This customer is in the starting balances or has tanks kept at the store, so they can't be deleted.");
                if (result < 0) throw new Http.Error(404, "Customer not found");
                if (result > 0) throw new Http.Error(409, "This customer has " + result + " receipt(s), so they can't be deleted.");
                log.add(who, ip, "Deleted customer #" + id);
                r.json(200, Json.obj("ok", true));
            }

            // ---- products
            case "POST products" -> {
                requireManager(who, "add LPG products");
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
                requireManager(who, "change prices and products");
                ProductFields f = productFields(r);
                DataStore.Product old = store.product(id);
                if (old == null || !store.updateProduct(f.toProduct(id, old.tankPrice()))) throw new Http.Error(404, "Product not found");
                log.add(who, ip, "Edited product #" + id + " (refill ₱" + DataStore.money(f.price)
                        + (f.refillCost > 0 ? ", refill cost ₱" + DataStore.money(f.refillCost) : "")
                        + (f.swapFee > 0 ? ", swap fee ₱" + DataStore.money(f.swapFee) : "") + ")");
                r.json(200, Json.obj("ok", true));
            }
            case "DELETE products/:id" -> {
                requireManager(who, "delete a product");
                String why = store.deleteProduct(id);
                if (why != null) throw new Http.Error(why.equals("Product not found") ? 404 : 409, why);
                log.add(who, ip, "Deleted product #" + id);
                r.json(200, Json.obj("ok", true));
            }

            // ---- receipts and payments
            case "POST receipts" -> once(r, who, () -> {
                ReceiptFields f = receiptFields(r);
                boolean fromKept = "1".equals(r.param("fromKept"));
                DataStore.Receipt rc = store.createReceipt(f.customer.id(), who, f.items, f.discount, f.paid, f.note, f.payMethod, fromKept);
                log.add(who, ip, "Receipt #" + rc.id() + " for " + f.customer.name() + ": " + describe(rc) + paymentText(rc, f.paid)
                        + (fromKept ? "; empties from the customer's tanks kept at the store" : ""));
                return Json.obj("id", rc.id(), "total", rc.total());
            });
            case "PUT receipts/:id" -> {
                ReceiptFields f = receiptFields(r);
                DataStore.Receipt before = store.receipt(id);
                if (before == null) throw new Http.Error(404, "Receipt not found");
                mayChangeReceipt(before, who, "change");
                LocalDate day = newDate(r, who, before.time());
                DataStore.Receipt rc = store.updateReceipt(id, f.customer.id(), f.items, f.discount, f.paid, f.note, f.payMethod, day);
                if (rc == null) throw new Http.Error(404, "Receipt not found");
                log.add(who, ip, "Edited receipt #" + id + ": " + describe(rc) + ", total ₱" + DataStore.money(rc.total())
                        + (day == null ? "" : "; date changed from " + before.time().toLocalDate() + " to " + day));
                r.json(200, Json.obj("id", rc.id(), "total", rc.total()));
            }
            case "DELETE receipts/:id" -> {
                DataStore.Receipt before = store.receipt(id);
                if (before == null) throw new Http.Error(404, "Receipt not found");
                mayChangeReceipt(before, who, "void");
                DataStore.Receipt rc = store.voidReceipt(id);
                if (rc == null) throw new Http.Error(404, "Receipt not found");
                log.add(who, ip, "Voided receipt #" + id + " (" + describe(rc) + ", ₱" + DataStore.money(rc.total()) + ")");
                r.json(200, Json.obj("ok", true));
            }
            case "POST payments" -> once(r, who, () -> {
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
                String method = customerMethod(r);
                List<DataStore.Payment> parts = store.addPayment(c.id(), receiptId, DataStore.round(amount), who, limit(r.param("note"), 120, "Note"), method);
                log.add(who, ip, "Payment ₱" + DataStore.money(amount) + (method.equals("gcash") ? " by GCash" : "") + " from " + c.name() + " for "
                        + parts.stream().map(x -> x.receiptId().startsWith("O") ? "the starting balance" : "receipt #" + x.receiptId()).collect(Collectors.joining(", ")));
                return Json.obj("ok", true, "undo", undo("payment", parts.get(0).id()));
            });

            // ---- stock
            case "POST stock/:id" -> stockChange(r, who, ip, id);
            case "POST suppliers" -> saveSupplier(r, who, ip, null);
            case "PUT suppliers/:id" -> {
                requireManager(who, "change a supplier");
                saveSupplier(r, who, ip, id);
            }
            case "DELETE suppliers/:id" -> {
                requireManager(who, "delete a supplier");
                DataStore.Supplier sup = store.supplier(id);
                String why = store.deleteSupplier(id);
                if (why != null) throw new Http.Error(why.equals("Supplier not found") ? 404 : 409, why);
                log.add(who, ip, "Deleted supplier " + sup.name());
                r.json(200, Json.obj("ok", true));
            }
            case "GET suppliers/:id/history" -> {
                requireDashboard(who);
                DataStore.Supplier sup = store.supplier(id);
                if (sup == null) throw new Http.Error(404, "Supplier not found");
                r.json(200, supplierHistory(sup));
            }
            case "POST suppliers/:id/pay" -> {
                DataStore.Supplier sup = store.supplier(id);
                if (sup == null) throw new Http.Error(404, "Supplier not found");
                DataStore.Paid paid = paidFields(r, who, 0);
                String note = limit(r.param("note"), 120, "Note");
                List<DataStore.SupplierPayment> parts = store.paySupplier(sup, paid, who, note);
                log.add(who, ip, "Paid ₱" + DataStore.money(paid.total()) + paidText(paid) + " to " + sup.name() + " ("
                        + parts.stream().map(x -> x.refillId().startsWith("P") ? "new tanks" : x.refillId().startsWith("O") ? "owed on the start date" : "trip #" + x.refillId()).distinct().collect(Collectors.joining(", "))
                        + ")" + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(201, Json.obj("ok", true, "undo", parts.isEmpty() ? null : undo("supplier-payment", parts.get(0).id())));
            }
            case "POST staff/:id/payback" -> {
                if (store.user(id) == null && !store.owedToStaff().containsKey(id)) throw new Http.Error(404, "No such staff member");
                double amount = DataStore.round(number(r.param("amount"), "Amount", 0.01, 100_000_000));
                String note = limit(r.param("note"), 120, "Note");
                DataStore.Payback pb = store.payBackStaff(id, amount, who, note);
                log.add(who, ip, "Paid back ₱" + DataStore.money(amount) + " to " + id + " (money they put in for suppliers)" + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(201, Json.obj("ok", true, "undo", undo("payback", pb.id())));
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
                r.json(201, Json.obj("id", rf.id(), "undo", undo("refill", rf.id())));
            }
            case "POST refills/:id" -> {
                // A refill done in one go: empties out and back filled on the same trip.
                if (!"record".equals(id)) throw new Http.Error(404, "Unknown endpoint");
                DataStore.Supplier sup = store.supplier(r.param("supplierId"));
                if (sup == null) throw new Http.Error(400, "Choose a supplier");
                Map<DataStore.Product, Integer> items = new LinkedHashMap<>();
                Map<DataStore.Product, Double> costs = new HashMap<>();
                double cost = 0;
                for (int i = 0; i < itemCount(r); i++) {
                    int qty = whole(r.param("qty." + i), "Tanks refilled", 0, 1_000);
                    if (qty == 0) continue;
                    DataStore.Product p = productParam(r, "productId." + i);
                    if (!sup.supplies(p.brand())) {
                        throw new Http.Error(409, sup.name() + " only refills " + String.join(", ", sup.brands()) + ", not " + p.label() + ".");
                    }
                    double each = r.param("cost." + i).isEmpty() ? p.refillCost() : DataStore.round(number(r.param("cost." + i), "Refill cost", 0, 1_000_000));
                    items.merge(p, qty, Integer::sum);
                    costs.put(p, each);
                    cost += qty * each;
                }
                if (items.isEmpty()) throw new Http.Error(400, "Enter how many tanks were refilled");
                DataStore.Paid paid = paidFields(r, who, DataStore.round(cost));
                String note = limit(r.param("note"), 120, "Note");
                DataStore.Refill rf = store.recordRefill(sup, items, costs, paid, who, note);
                log.add(who, ip, "Refill from " + sup.name() + " (trip #" + rf.id() + "): " + countText(items) + " · cost ₱" + DataStore.money(cost)
                        + ", paid ₱" + DataStore.money(paid.total()) + paidText(paid) + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(201, Json.obj("id", rf.id(), "undo", undo("refill", rf.id())));
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
                DataStore.Movement first = store.receiveFromRefiller(rf, items, costs, paid, who, note);
                DataStore.Supplier sup = store.supplier(rf.supplierId());
                StringBuilder msg = new StringBuilder("Received from " + (sup == null ? "refiller" : sup.name()) + " (trip #" + rf.id() + "): ");
                List<String> parts = new ArrayList<>();
                items.forEach((p, v) -> parts.add(v[0] + " × " + p.label() + (v[1] > 0 ? " (+" + v[1] + " unfilled)" : "")));
                log.add(who, ip, msg + String.join(", ", parts) + " · cost ₱" + DataStore.money(cost) + ", paid ₱" + DataStore.money(paid.total()) + paidText(paid)
                        + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(200, Json.obj("ok", true, "undo", first == null ? null : undo("receive", first.id())));
            }
            case "POST refills/:id/pay" -> {
                DataStore.Refill rf = store.refills().stream().filter(x -> x.id().equals(id)).findFirst()
                        .orElseThrow(() -> new Http.Error(404, "Refill trip not found"));
                DataStore.Paid paid = paidFields(r, who, 0);
                String note = limit(r.param("note"), 120, "Note");
                DataStore.SupplierPayment sp = store.payRefiller(rf, paid, who, note);
                DataStore.Supplier sup = store.supplier(rf.supplierId());
                log.add(who, ip, "Paid ₱" + DataStore.money(paid.total()) + paidText(paid) + " to " + (sup == null ? "refiller" : sup.name()) + " for trip #" + rf.id()
                        + (note.isEmpty() ? "" : " (" + note + ")"));
                r.json(201, Json.obj("ok", true, "undo", sp == null ? null : undo("supplier-payment", sp.id())));
            }
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
    }

    // ---------------------------------------------------------------- stock

    private void stockChange(Http.Req r, String who, String ip, String kind) throws IOException {
        DataStore.Product p = productParam(r, "productId");
        String note = limit(r.param("note"), 120, "Note");
        String suffix = note.isEmpty() ? "" : " (" + note + ")";
        String made;
        switch (kind) {
            case "purchase" -> {
                int qty = whole(r.param("qty"), "Tanks bought", 1, 1_000);
                double cost = r.param("unitCost").isEmpty() ? p.tankCost() : DataStore.round(number(r.param("unitCost"), "Cost per tank", 0, 1_000_000));
                double bill = DataStore.round(qty * cost);
                DataStore.Paid paid = paidFields(r, who, bill);
                made = store.purchase(p, qty, cost, paid, who, note).id();
                log.add(who, ip, "Bought " + qty + " new " + p.label() + " tanks with load"
                        + (cost > 0 ? " for ₱" + DataStore.money(bill) + (paid.total() < bill - 0.005 ? " (paid ₱" + DataStore.money(paid.total()) + ", rest later)" : "") + paidText(paid) : "") + suffix);
            }
            case "adjust" -> {
                requireManager(who, "count, adjust or write off stock");
                int loaded = whole(r.param("loaded"), "Tanks with load", 0, 10_000);
                int empty = whole(r.param("empty"), "Empty tanks", 0, 10_000);
                Integer damaged = r.param("damaged").isEmpty() ? null : whole(r.param("damaged"), "Damaged tanks", 0, 10_000);
                String reason = r.param("reason");
                if (!List.of("count", "damaged", "other").contains(reason)) throw new Http.Error(400, "Choose a reason");
                if (!reason.equals("count") && note.isEmpty()) throw new Http.Error(400, "Add a note saying what happened");
                List<DataStore.Movement> change = new ArrayList<>();
                DataStore.Stock before = store.adjustTo(p, reason, loaded, empty, damaged, who, note, change);
                if (before == null) throw new Http.Error(400, "Those are the current counts; nothing to change");
                log.add(who, ip, (reason.equals("count") ? "Stock count" : reason.equals("damaged") ? "Wrote off stock" : "Adjusted stock")
                        + " for " + p.label() + ": with load " + before.loaded() + " → " + loaded + ", empty " + before.empty() + " → " + empty + suffix);
                made = change.get(0).id();
            }
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
        r.json(200, Json.obj("ok", true, "undo", undo("movement", made)));
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

    /** Everything done with one supplier, newest first: refill trips, new tanks bought, and payments. Read-only. */
    private Map<String, Object> supplierHistory(DataStore.Supplier sup) {
        Map<String, DataStore.Product> prods = store.productMap();
        Function<String, String> productName = pid -> {
            DataStore.Product p = prods.get(pid);
            return p == null ? "Deleted product #" + pid : p.label();
        };
        Map<String, DataStore.Bill> bills = new HashMap<>();
        for (DataStore.Bill b : store.bills()) if (b.supplierId().equals(sup.id())) bills.put(b.key(), b);
        Map<String, DataStore.Movement> moves = new HashMap<>();
        for (DataStore.Movement m : store.movements()) moves.put(m.id(), m);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (DataStore.Refill rf : store.refills()) {
            if (!rf.supplierId().equals(sup.id())) continue;
            DataStore.Bill b = bills.get(rf.id());
            List<Object> items = new ArrayList<>();
            for (DataStore.RefillItem i : store.refillItems(rf.id())) {
                items.add(Json.obj("product", productName.apply(i.productId()), "sent", i.sent(), "received", i.received(), "rejected", i.rejected()));
            }
            entries.add(Json.obj("kind", "refill", "time", rf.rawTime(), "ref", rf.id(), "label", rf.start() ? "Already at the refiller on the start date" : "Refill trip #" + rf.id(),
                    "staff", rf.staff(), "note", rf.note(), "items", items,
                    "cost", b == null ? 0 : b.cost(), "paid", b == null ? 0 : b.paid(), "owed", b == null ? 0 : b.owed()));
        }
        for (DataStore.Bill b : bills.values()) {
            if ("start".equals(b.kind())) {
                entries.add(Json.obj("kind", "start", "time", b.rawTime(), "ref", b.key(), "label", b.label(), "staff", "", "note", "",
                        "cost", b.cost(), "paid", b.paid(), "owed", b.owed()));
                continue;
            }
            if (!"purchase".equals(b.kind())) continue;
            DataStore.Movement m = moves.get(b.key().substring(1));
            entries.add(Json.obj("kind", "purchase", "time", b.rawTime(), "ref", b.key(), "label", b.label(),
                    "staff", m == null ? "" : m.staff(), "note", m == null ? "" : m.note(),
                    "cost", b.cost(), "paid", b.paid(), "owed", b.owed()));
        }
        double paidSales = 0, paidStaff = 0, paidOwner = 0, paidCheck = 0, paidOnline = 0;
        for (DataStore.SupplierPayment sp : store.supplierPayments()) {
            if (!sp.supplierId().equals(sup.id())) continue;
            if (sp.fromStaff()) paidStaff += sp.amount(); else if (sp.fromOwner()) paidOwner += sp.amount(); else paidSales += sp.amount();
            if (sp.byCheck()) paidCheck += sp.amount();
            if (sp.online()) paidOnline += sp.amount();
            DataStore.Bill b = sp.refillId().isEmpty() ? null : bills.get(sp.refillId());
            String forWhat = b != null ? b.label() : sp.refillId().isEmpty() ? "New tanks (older payment)" : sp.refillId().startsWith("O") ? "Owed on the start date" : sp.refillId().startsWith("P") ? "New tanks" : "Refill trip #" + sp.refillId();
            entries.add(Json.obj("kind", "payment", "time", sp.rawTime(), "ref", sp.id(), "label", forWhat,
                    "amount", sp.amount(), "source", sp.source(), "paidBy", sp.paidBy(), "method", sp.method(), "staff", sp.staff(), "note", sp.note()));
        }
        // Newest first; same-time entries keep bills before their payments.
        entries.sort(Comparator.comparing((Map<String, Object> e) -> (String) e.get("time")).reversed());
        double billed = 0, owed = 0;
        for (DataStore.Bill b : bills.values()) { billed += b.cost(); owed += b.owed(); }
        return Json.obj("supplier", Json.obj("id", sup.id(), "name", sup.name(), "contact", sup.contact(), "brands", sup.brands()),
                "totals", Json.obj("billed", DataStore.round(billed), "paid", DataStore.round(paidSales + paidOwner + paidStaff),
                        "paidSales", DataStore.round(paidSales), "paidOwner", DataStore.round(paidOwner), "paidStaff", DataStore.round(paidStaff),
                        "paidCheck", DataStore.round(paidCheck), "paidOnline", DataStore.round(paidOnline), "owed", DataStore.round(owed)),
                "entries", entries);
    }

    /** The form each kind of record is corrected with (its route), so an edit can't turn one kind into another. */
    private static final Map<String, String> EDIT_ROUTES = Map.of(
            "POST refills", "refill", "POST refills/:id", "refill", "POST refills/:id/receive", "receive",
            "POST refills/:id/pay", "supplier-payment", "POST suppliers/:id/pay", "supplier-payment", "POST payments", "payment",
            "POST customers/:id/returns", "return", "POST customers/:id/deposits", "deposit", "POST stock/:id", "movement",
            "POST staff/:id/payback", "payback");

    /**
     * Who may undo or change a record: staff with dashboard access, anything; other staff, what they entered
     * themselves in the last UNDO_MINUTES. Returns who entered it and when.
     */
    private DataStore.Undone mayChange(String kind, String recordId, String who, String verb) {
        if (!DataStore.UNDO_KINDS.contains(kind)) throw new Http.Error(400, "That can't be " + (verb.equals("undo") ? "undone" : "changed") + " here");
        DataStore.Undone target = store.undoTarget(kind, recordId);
        if (target == null) throw new Http.Error(404, "That record is no longer there. Someone may have undone or changed it already.");
        DataStore.User u = store.user(who);
        if (u == null || !u.dashboard()) {
            boolean own = target.staff().equals(who);
            boolean recent = target.time() != null && !target.time().isBefore(LocalDateTime.now().minusMinutes(UNDO_MINUTES));
            if (!own) throw new Http.Error(403, (target.staff().isEmpty() ? "Someone else" : target.staff()) + " entered this. Ask them, or someone with dashboard access, to " + verb + " it.");
            if (!recent) throw new Http.Error(403, "This was entered more than " + UNDO_MINUTES + " minutes ago. Ask someone with dashboard access to " + verb + " it.");
        }
        return target;
    }

    /**
     * Saves a corrected record: the form's usual request plus replaceKind / replaceId. The old record comes out and
     * the new one goes in as one change (nothing changes if either is refused). It keeps the old record's day unless
     * staff with dashboard access chose another.
     */
    private void edit(Http.Req r, String route, String id, Auth.Session s, String who, String ip, LocalDate day,
                      String kind, String recordId) throws IOException {
        if (!kind.equals(EDIT_ROUTES.get(route))) throw new Http.Error(400, "That record can't be changed with this form");
        DataStore.Undone target = mayChange(kind, recordId, who, "change");
        if (route.equals("POST refills") && store.refillItems(recordId).stream().anyMatch(i -> i.received() + i.rejected() > 0)) {
            throw new Http.Error(409, "Some tanks on this trip already came back. Change what was received instead, or undo the trip.");
        }
        if (day == null && r.param("recordDate").isEmpty() && target.time() != null && !target.time().toLocalDate().equals(LocalDate.now())) DataStore.recordOn(target.time().toLocalDate());
        r.hold();
        try {
            store.replacing(kind, recordId, () -> {
                route(r, route, id, s, who, ip);
                return null;
            });
        } catch (Exception e) {
            r.discardHeld();
            log.add(who, ip, "Change to " + kind + " #" + recordId + " not saved (" + e.getMessage() + "); nothing changed");
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
        log.add(who, ip, "That corrects " + kind + " #" + recordId + " (entered by " + (target.staff().isEmpty() ? "?" : target.staff()) + ", "
                + (target.time() == null ? "?" : target.time().format(DataStore.TIME)) + "), which was taken out");
        r.release();
    }

    /** A record's details for its edit form (see mayChange for who may ask). */
    private Map<String, Object> recordDetails(String kind, String recordId) {
        List<DataStore.SupplierPayment> sps = store.supplierPayments();
        Function<List<DataStore.SupplierPayment>, Map<String, Object>> paid = list -> {
            double sales = 0, owner = 0, staff = 0;
            String by = "", method = "cash";
            for (DataStore.SupplierPayment p : list) {
                if (p.fromStaff()) { staff += p.amount(); by = p.paidBy(); } else if (p.fromOwner()) owner += p.amount(); else sales += p.amount();
                method = p.method();
            }
            return Json.obj("sales", DataStore.round(sales), "owner", DataStore.round(owner), "staff", DataStore.round(staff), "paidBy", by, "method", method);
        };
        switch (kind) {
            case "refill" -> {
                DataStore.Refill rf = store.refills().stream().filter(x -> x.id().equals(recordId)).findFirst().orElseThrow();
                Map<String, Double> cost = new HashMap<>();
                boolean oneGo = false;
                for (DataStore.Movement m : store.movements()) {
                    if (!recordId.equals(m.refillId())) continue;
                    if ("refill-receive".equals(m.type())) {
                        cost.put(m.productId(), m.unitCost());
                        if (m.rawTime().equals(rf.rawTime())) oneGo = true;
                    }
                }
                List<Object> items = new ArrayList<>();
                for (DataStore.RefillItem i : store.refillItems(recordId)) {
                    items.add(Json.obj("productId", i.productId(), "sent", i.sent(), "received", i.received(), "rejected", i.rejected(), "cost", cost.getOrDefault(i.productId(), 0.0)));
                }
                return Json.obj("kind", kind, "id", recordId, "time", rf.rawTime(), "supplierId", rf.supplierId(), "note", rf.note(), "oneGo", oneGo, "items", items,
                        "paid", paid.apply(sps.stream().filter(p -> recordId.equals(p.refillId())).toList()));
            }
            case "receive" -> {
                DataStore.Movement mv = store.movements().stream().filter(x -> x.id().equals(recordId)).findFirst().orElseThrow();
                Map<String, int[]> got = new LinkedHashMap<>();
                Map<String, Double> cost = new HashMap<>();
                for (DataStore.Movement m : store.movements()) {
                    if (!mv.refillId().equals(m.refillId()) || !m.rawTime().equals(mv.rawTime())) continue;
                    if ("refill-receive".equals(m.type())) {
                        got.computeIfAbsent(m.productId(), k -> new int[2])[0] += m.loaded();
                        cost.put(m.productId(), m.unitCost());
                    }
                    if ("refill-reject".equals(m.type())) got.computeIfAbsent(m.productId(), k -> new int[2])[1] += m.empty() + m.damaged();
                }
                List<Object> items = new ArrayList<>();
                got.forEach((pid, v) -> items.add(Json.obj("productId", pid, "received", v[0], "rejected", v[1], "cost", cost.getOrDefault(pid, 0.0))));
                return Json.obj("kind", kind, "id", recordId, "time", mv.rawTime(), "refillId", mv.refillId(), "note", mv.note(), "items", items,
                        "paid", paid.apply(sps.stream().filter(p -> mv.refillId().equals(p.refillId()) && p.rawTime().equals(mv.rawTime()) && p.staff().equals(mv.staff())).toList()));
            }
            case "movement" -> {
                DataStore.Movement m = store.movements().stream().filter(x -> x.id().equals(recordId)).findFirst().orElseThrow();
                return Json.obj("kind", kind, "id", recordId, "time", m.rawTime(), "type", m.type(), "productId", m.productId(), "note", m.note(),
                        "loaded", m.loaded(), "empty", m.empty(), "unitCost", m.unitCost(),
                        "paid", paid.apply(sps.stream().filter(p -> ("P" + recordId).equals(p.refillId())).toList()));
            }
            case "payment" -> {
                DataStore.Payment p = store.payments().stream().filter(x -> x.id().equals(recordId)).findFirst().orElseThrow();
                List<DataStore.Payment> group = store.payments().stream().filter(x -> x.rawTime().equals(p.rawTime()) && x.customerId().equals(p.customerId())
                        && x.staff().equals(p.staff()) && x.note().equals(p.note()) && x.method().equals(p.method())).toList();
                boolean one = group.stream().map(DataStore.Payment::receiptId).distinct().count() == 1;
                return Json.obj("kind", kind, "id", recordId, "time", p.rawTime(), "customerId", p.customerId(), "receiptId", one && !p.receiptId().startsWith("O") ? p.receiptId() : "",
                        "amount", DataStore.round(group.stream().mapToDouble(DataStore.Payment::amount).sum()), "method", p.method(), "note", p.note());
            }
            case "return" -> {
                DataStore.TankReturn t = store.tankReturns().stream().filter(x -> x.id().equals(recordId)).findFirst().orElseThrow();
                return Json.obj("kind", kind, "id", recordId, "time", t.rawTime(), "customerId", t.customerId(), "productId", t.productId(), "qty", t.qty(), "remark", t.remark(),
                        "emptyKind", t.emptyKind(), "emptyBrand", t.emptyBrand());
            }
            case "deposit" -> {
                DataStore.Deposit d = store.deposits().stream().filter(x -> x.id().equals(recordId)).findFirst().orElseThrow();
                return Json.obj("kind", kind, "id", recordId, "time", d.rawTime(), "customerId", d.customerId(), "productId", d.productId(), "qty", d.qty(), "type", d.kind(), "note", d.note());
            }
            case "supplier-payment" -> {
                DataStore.SupplierPayment p = sps.stream().filter(x -> x.id().equals(recordId)).findFirst().orElseThrow();
                List<DataStore.SupplierPayment> group = sps.stream().filter(x -> x.rawTime().equals(p.rawTime()) && x.supplierId().equals(p.supplierId())
                        && x.staff().equals(p.staff()) && x.note().equals(p.note()) && x.method().equals(p.method())).toList();
                List<String> bills = group.stream().map(DataStore.SupplierPayment::refillId).distinct().toList();
                String trip = bills.size() == 1 && !bills.get(0).isEmpty() && !bills.get(0).startsWith("P") && !bills.get(0).startsWith("O") ? bills.get(0) : "";
                return Json.obj("kind", kind, "id", recordId, "time", p.rawTime(), "supplierId", p.supplierId(), "refillId", trip, "note", p.note(), "paid", paid.apply(group));
            }
            case "payback" -> {
                DataStore.Payback p = store.paybacks().stream().filter(x -> x.id().equals(recordId)).findFirst().orElseThrow();
                return Json.obj("kind", kind, "id", recordId, "time", p.rawTime(), "staff", p.staff(), "amount", p.amount(), "note", p.note());
            }
            default -> throw new Http.Error(400, "Unknown record");
        }
    }

    /** Tells the app what to send to POST undo to take a record back out (its Undo button). */
    private static Map<String, Object> undo(String kind, String id) {
        return Json.obj("kind", kind, "id", id);
    }

    /** "Generic 11.0 kg" or "Shine (other brand) 11.0 kg": a loose empty of productId's size. */
    private String looseName(String kind, String brand, String productId) {
        DataStore.Product p = store.product(productId);
        String size = p == null ? "" : " " + DataStore.fmtKg(p.weight()) + " kg";
        return "generic".equals(kind) ? "Generic" + size : DataStore.looseLabel(kind, brand) + " (other brand)" + size;
    }

    /** Changes that move money or stock without a sale (prices, write-offs, deletes): staff with dashboard access only. */
    private void requireManager(String who, String what) {
        DataStore.User u = store.user(who);
        if (u == null || !u.dashboard()) throw new Http.Error(403, "Only the owner or staff with dashboard access can " + what + ".");
    }

    /** A receipt: staff with dashboard access may change or void any; other staff, their own from the last UNDO_MINUTES. */
    private void mayChangeReceipt(DataStore.Receipt rc, String who, String verb) {
        DataStore.User u = store.user(who);
        if (u != null && u.dashboard()) return;
        if (!rc.staff().equals(who)) throw new Http.Error(403, (rc.staff().isEmpty() ? "Someone else" : rc.staff()) + " made this sale. Ask the owner or someone with dashboard access to " + verb + " it.");
        if (rc.time() == null || rc.time().isBefore(LocalDateTime.now().minusMinutes(UNDO_MINUTES))) {
            throw new Http.Error(403, "This sale was made more than " + UNDO_MINUTES + " minutes ago. Ask the owner or someone with dashboard access to " + verb + " it.");
        }
    }

    interface Work {
        Map<String, Object> run() throws IOException;
    }

    /** Recent sales and payments by requestId, with the answer each got (oldest first). */
    private final LinkedHashMap<String, Map<String, Object>> recent = new LinkedHashMap<>();
    private static final int RECENT_MAX = 2000;

    /**
     * Saves a new sale or payment once per requestId (sent by the POS with each new one): the same request sent again,
     * after a dropped connection or a second tap, gets the first answer back instead of being saved twice.
     */
    private void once(Http.Req r, String who, Work work) throws IOException {
        String key = r.param("requestId");
        if (key.isEmpty() || !r.param("replaceKind").isEmpty()) {
            r.json(201, work.run());
            return;
        }
        if (key.length() > 64) throw new Http.Error(400, "Bad request id");
        String k = who + "\n" + key;
        synchronized (recent) {
            Map<String, Object> first = recent.get(k);
            if (first != null) {
                log.add(who, r.clientIp(), "Same request sent again (" + r.path + "); not saved twice");
                r.json(201, first);
                return;
            }
            Map<String, Object> out = work.run();
            recent.put(k, out);
            if (recent.size() > RECENT_MAX) recent.remove(recent.keySet().iterator().next());
            r.json(201, out);
        }
    }

    private void requireDashboard(String who) {
        // Only staff the admin has chosen may see the business summary.
        DataStore.User u = store.user(who);
        if (u == null || !u.dashboard()) throw new Http.Error(403, "Ask the admin for access to the dashboard");
    }

    /**
     * Money handed to a supplier, by source: paidSales (store money), paidOwner (the owner's personal
     * money) and paidStaff (a staff member's own money, paidBy; the store owes it back), handed over as
     * method "cash" (the default), "check" or "online". Older clients send one "amount" or "paid", taken as sales
     * money. With nothing sent, the whole bill (fallback) counts as paid from sales.
     */
    private DataStore.Paid paidFields(Http.Req r, String who, double fallback) throws IOException {
        boolean split = !r.param("paidSales").isEmpty() || !r.param("paidStaff").isEmpty() || !r.param("paidOwner").isEmpty();
        String single = !r.param("amount").isEmpty() ? r.param("amount") : r.param("paid");
        double sales = split ? (r.param("paidSales").isEmpty() ? 0 : number(r.param("paidSales"), "Paid from sales", 0, 100_000_000))
                : single.isEmpty() ? fallback : number(single, "Amount paid", 0, 100_000_000);
        double staff = r.param("paidStaff").isEmpty() ? 0 : number(r.param("paidStaff"), "Paid from staff money", 0, 100_000_000);
        String by = r.param("paidBy").isEmpty() ? who : r.param("paidBy");
        double owner = r.param("paidOwner").isEmpty() ? 0 : number(r.param("paidOwner"), "Paid from the owner's money", 0, 100_000_000);
        if (staff > 0 && store.user(by) == null) throw new Http.Error(400, "Choose whose money it was");
        String method = r.param("method").isEmpty() ? "cash" : r.param("method");
        if (!List.of("cash", "check", "online").contains(method)) throw new Http.Error(400, "Choose cash, check or online");
        return new DataStore.Paid(DataStore.round(sales), DataStore.round(staff), staff > 0 ? by : "", DataStore.round(owner), method);
    }

    private static String paidText(DataStore.Paid p) {
        if (p.total() <= 0) return "";
        List<String> parts = new ArrayList<>();
        if (p.sales() > 0) parts.add("₱" + DataStore.money(p.sales()) + " from sales");
        if (p.owner() > 0) parts.add("₱" + DataStore.money(p.owner()) + " owner's money");
        if (p.staff() > 0) parts.add("₱" + DataStore.money(p.staff()) + " from " + p.byStaff() + "'s own money");
        return " (by " + p.method() + ": " + String.join(", ", parts) + ")";
    }

    static String stockStatus(DataStore.Stock st, int reorderLevel) {
        if (st.loaded() <= 0) return "out";
        return st.loaded() <= reorderLevel ? "low" : "ok";
    }

    // ---------------------------------------------------------------- starting balances

    private static int tankCount(Map<String, Map<String, Integer>> m) {
        return m.values().stream().flatMap(x -> x.values().stream()).mapToInt(Integer::intValue).sum();
    }

    private static double sum(Map<String, Double> m) {
        return m.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /** The starting balances for the form (start null before they're entered), with what's still unpaid. */
    private Map<String, Object> openingJson() {
        DataStore.Opening o = store.opening();
        LocalDateTime first = store.firstRecord();
        Set<String> employees = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (DataStore.User u : store.users()) employees.add(u.username());
        for (DataStore.Expense e : store.expenses()) if (!e.employee().isEmpty()) employees.add(e.employee());
        if (o != null) employees.addAll(o.advances().keySet());
        Map<String, Object> out = Json.obj("firstRecord", first == null ? null : first.toLocalDate().toString(), "employees", new ArrayList<>(employees),
                "start", o == null ? null : o.start().toString());
        if (o == null) return out;
        Map<String, Double> owedNow = store.openingOwed();
        Map<String, Double> supOwed = new HashMap<>();
        for (DataStore.Bill b : store.bills()) if ("start".equals(b.kind())) supOwed.put(b.supplierId(), b.owed());
        List<Object> stock = new ArrayList<>(), refiller = new ArrayList<>(), owes = new ArrayList<>(), tanks = new ArrayList<>(),
                kept = new ArrayList<>(), sup = new ArrayList<>(), adv = new ArrayList<>();
        o.stock().forEach((pid, v) -> stock.add(Json.obj("productId", pid, "loaded", v[0], "empty", v[1])));
        o.atRefiller().forEach((sid, m) -> m.forEach((pid, n) -> refiller.add(Json.obj("supplierId", sid, "productId", pid, "qty", n))));
        o.customerMoney().forEach((cid, v) -> owes.add(Json.obj("customerId", cid, "amount", v, "left", owedNow.getOrDefault(cid, 0.0))));
        o.customerTanks().forEach((cid, m) -> m.forEach((pid, n) -> tanks.add(Json.obj("customerId", cid, "productId", pid, "qty", n))));
        o.deposits().forEach((cid, m) -> m.forEach((pid, n) -> kept.add(Json.obj("customerId", cid, "productId", pid, "qty", n))));
        o.suppliers().forEach((sid, v) -> sup.add(Json.obj("supplierId", sid, "amount", v, "left", supOwed.getOrDefault(sid, 0.0))));
        o.advances().forEach((name, v) -> adv.add(Json.obj("employee", name, "amount", v)));
        out.putAll(Json.obj("cash", o.cash(), "stock", stock, "refiller", refiller, "owes", owes, "tanks", tanks,
                "kept", kept, "suppliers", sup, "advances", adv, "staff", o.staff(), "savedAt", o.savedAt()));
        return out;
    }

    /**
     * The starting balances sent from the form: start (YYYY-MM-DD, up to a year back, not in the future), cash, and
     * numbered rows per list (stock, refiller, owes, tanks, kept, sup, adv; the row count is sent under the list's name).
     */
    private DataStore.Opening openingFields(Http.Req r) throws IOException {
        LocalDate today = LocalDate.now(), start;
        try {
            start = LocalDate.parse(r.param("start"));
        } catch (DateTimeParseException e) {
            throw new Http.Error(400, "Choose the start date");
        }
        if (start.isAfter(today)) throw new Http.Error(400, "The start date can't be in the future");
        if (start.isBefore(today.minusYears(1))) throw new Http.Error(400, "The start date can't be more than a year ago");
        double cash = r.param("cash").isEmpty() ? 0 : DataStore.round(number(r.param("cash"), "Cash on hand", 0, 100_000_000));
        Map<String, int[]> stock = new LinkedHashMap<>();
        for (int i = 0; i < rows(r, "stock"); i++) {
            DataStore.Product p = productParam(r, "stock." + i + ".productId");
            int[] v = stock.computeIfAbsent(p.id(), k -> new int[2]);
            v[0] += whole(r.param("stock." + i + ".loaded"), p.label() + " with load", 0, 10_000);
            v[1] += whole(r.param("stock." + i + ".empty"), "Empty " + p.label(), 0, 10_000);
        }
        Map<String, Map<String, Integer>> refiller = new LinkedHashMap<>();
        for (int i = 0; i < rows(r, "refiller"); i++) {
            DataStore.Supplier sup = store.supplier(r.param("refiller." + i + ".supplierId"));
            if (sup == null) throw new Http.Error(400, "Choose the refiller");
            DataStore.Product p = productParam(r, "refiller." + i + ".productId");
            if (!sup.supplies(p.brand())) throw new Http.Error(400, sup.name() + " doesn't refill " + p.brand());
            int n = whole(r.param("refiller." + i + ".qty"), "Tanks at " + sup.name(), 0, 10_000);
            if (n > 0) refiller.computeIfAbsent(sup.id(), k -> new LinkedHashMap<>()).merge(p.id(), n, Integer::sum);
        }
        Map<String, Double> owes = new LinkedHashMap<>();
        for (int i = 0; i < rows(r, "owes"); i++) {
            DataStore.Customer c = customerParam(r, "owes." + i + ".customerId");
            double v = DataStore.round(number(r.param("owes." + i + ".amount"), "What " + c.name() + " owes", 0, 100_000_000));
            if (v > 0) owes.merge(c.id(), v, Double::sum);
        }
        Map<String, Map<String, Integer>> tanks = tankRows(r, "tanks"), kept = tankRows(r, "kept");
        Map<String, Double> sup = new LinkedHashMap<>();
        for (int i = 0; i < rows(r, "sup"); i++) {
            DataStore.Supplier s = store.supplier(r.param("sup." + i + ".supplierId"));
            if (s == null) throw new Http.Error(400, "Choose the supplier");
            double v = DataStore.round(number(r.param("sup." + i + ".amount"), "What's owed to " + s.name(), 0, 100_000_000));
            if (v > 0) sup.merge(s.id(), v, Double::sum);
        }
        Map<String, Double> adv = new LinkedHashMap<>();
        for (int i = 0; i < rows(r, "adv"); i++) {
            String name = limit(r.param("adv." + i + ".employee").trim(), 60, "Employee");
            if (name.isEmpty()) throw new Http.Error(400, "Enter the employee's name for each advance");
            double v = DataStore.round(number(r.param("adv." + i + ".amount"), name + "'s advance", 0, 100_000_000));
            String same = adv.keySet().stream().filter(k -> k.equalsIgnoreCase(name)).findFirst().orElse(name);
            if (v > 0) adv.merge(same, v, Double::sum);
        }
        return new DataStore.Opening(start, cash, stock, refiller, owes, tanks, kept, sup, adv, "", "");
    }

    private static int rows(Http.Req r, String list) throws IOException {
        return whole(r.param(list).isEmpty() ? "0" : r.param(list), "Number of rows", 0, 2_000);
    }

    private Map<String, Map<String, Integer>> tankRows(Http.Req r, String list) throws IOException {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (int i = 0; i < rows(r, list); i++) {
            DataStore.Customer c = customerParam(r, list + "." + i + ".customerId");
            DataStore.Product p = productParam(r, list + "." + i + ".productId");
            int n = whole(r.param(list + "." + i + ".qty"), "Tanks for " + c.name(), 0, 1_000);
            if (n > 0) out.computeIfAbsent(c.id(), k -> new LinkedHashMap<>()).merge(p.id(), n, Integer::sum);
        }
        return out;
    }

    private DataStore.Customer customerParam(Http.Req r, String name) throws IOException {
        DataStore.Customer c = store.customer(r.param(name));
        if (c == null) throw new Http.Error(400, "Choose a customer for each row");
        return c;
    }

    // ---------------------------------------------------------------- auth

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
                        "emptyProductId", l.emptyProductId(), "emptyProduct", l.looseEmpty() ? looseName(l.emptyKind(), l.emptyBrand(), l.productId())
                                : l.returnedEmpty() ? productName.apply(l.emptyProductId()) : null,
                        "emptyKind", l.emptyKind(), "emptyBrand", l.emptyBrand(),
                        "owesTank", l.owesTank(), "newTank", l.newTank(), "swapFee", l.swapFee(), "remark", l.remark()));
            }
            List<Object> payOut = new ArrayList<>();
            for (DataStore.Payment p : payByReceipt.getOrDefault(rc.id(), List.of())) {
                payOut.add(Json.obj("id", p.id(), "time", p.rawTime(), "amount", p.amount(), "staff", p.staff(), "note", p.note(), "method", p.method()));
            }
            DataStore.Customer c = custById.get(rc.customerId());
            receiptOut.add(Json.obj(
                    "id", rc.id(), "time", rc.rawTime(), "customerId", rc.customerId(),
                    "customer", c == null ? "Unknown customer #" + rc.customerId() : c.name(),
                    "staff", rc.staff().isEmpty() ? "Desktop app" : rc.staff(), "lines", lineOut,
                    "subtotal", rc.subtotal(), "discount", rc.discount(), "total", rc.total(),
                    "paidAtSale", rc.paidAtSale(), "payMethod", rc.payMethod(), "paid", got, "balance", bal,
                    "status", bal <= 0 ? "paid" : got <= 0.005 ? "unpaid" : "partial",
                    "note", rc.note(), "payments", payOut));
        }

        Map<String, List<DataStore.OwedTank>> owedTanks = store.owedTanks();
        Map<String, Double> startOwed = store.openingOwed();
        startOwed.forEach((cid, v) -> owedByCustomer.merge(cid, v, Double::sum));
        Map<String, Map<String, Integer>> kept = store.depositsHeld();
        List<Object> custOut = new ArrayList<>();
        for (DataStore.Customer c : customers) {
            List<Object> keptOut = new ArrayList<>();
            int keptTanks = 0;
            for (var e : kept.getOrDefault(c.id(), Map.of()).entrySet()) {
                keptTanks += e.getValue();
                keptOut.add(Json.obj("productId", e.getKey(), "product", productName.apply(e.getKey()), "qty", e.getValue()));
            }
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
                    "lastPurchase", lastByCustomer.get(c.id()), "tanksOwed", tanks, "owedTanks", owedOut,
                    "startOwed", startOwed.getOrDefault(c.id(), 0.0), "keptTanks", keptTanks, "kept", keptOut));
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
            returnOut.add(Json.obj("id", t.id(), "time", t.rawTime(), "customerId", t.customerId(), "customer", c == null ? "Deleted customer #" + t.customerId() : c.name(),
                    "productId", t.productId(), "product", t.looseEmpty() ? looseName(t.emptyKind(), t.emptyBrand(), t.productId()) : productName.apply(t.productId()),
                    "emptyKind", t.emptyKind(), "emptyBrand", t.emptyBrand(), "qty", t.qty(), "staff", t.staff(), "remark", t.remark()));
        }
        List<DataStore.Deposit> deps = store.deposits();
        List<Object> depositOut = new ArrayList<>();
        for (int i = deps.size() - 1; i >= 0 && depositOut.size() < 30; i--) {
            DataStore.Deposit d = deps.get(i);
            if ("used".equals(d.kind())) continue;
            depositOut.add(Json.obj("id", d.id(), "time", d.rawTime(), "customerId", d.customerId(), "productId", d.productId(),
                    "product", productName.apply(d.productId()), "qty", d.qty(), "kind", d.kind(), "staff", d.staff(), "note", d.note()));
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
                    "open", open, "items", itemOut, "cost", cost, "paid", paidOut, "paidStaff", paidStaff, "owed", owed, "start", rf.start()));
        }
        List<String> brands = products.stream().map(DataStore.Product::brand).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        LocalDate startDate = store.startDate();
        return Json.obj("startDate", startDate == null ? null : startDate.toString(), "customers", custOut, "products", prodOut, "receipts", receiptOut, "movements", moveOut,
                "suppliers", supOut, "refills", refillOut, "brands", brands, "returns", returnOut, "deposits", depositOut, "looseEmpties", store.looseEmpties(), "undoMinutes", UNDO_MINUTES,
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
                    : l.looseEmpty() ? " (empty " + DataStore.looseLabel(l.emptyKind(), l.emptyBrand())
                    + (l.swapFee() > 0 ? ", swap fee ₱" + DataStore.money(l.swapFee()) + " each" : "") + ")"
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

    private record ReceiptFields(DataStore.Customer customer, List<DataStore.LineInput> items, double discount, double paid, String note,
                                 String payMethod) {}

    /** How a customer paid: "cash" (the default) or "gcash". */
    private static String customerMethod(Http.Req r) throws IOException {
        String m = r.param("method").isEmpty() ? r.param("payMethod") : r.param("method");
        if (m.isEmpty()) return "cash";
        if (!m.equals("cash") && !m.equals("gcash")) throw new Http.Error(400, "Choose cash or GCash");
        return m;
    }

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
            // Generic (no brand) or another brand the store doesn't sell: an empty of the sold tank's size that
            // goes into no product's stock. It's another brand, so the swap fee applies.
            String kind = DataStore.emptyKind(emptyId), brand = "";
            if (!kind.isEmpty()) {
                emptyId = p.id();
                brand = kind.equals("other") ? limit(r.param("emptyBrand." + i).trim(), 40, "Brand") : "";
                if (kind.equals("other") && brand.isEmpty()) throw new Http.Error(400, "Type the brand of the empty for " + p.label());
                fee = r.param("swapFee." + i).isEmpty() ? p.swapFee() : number(r.param("swapFee." + i), "Swap fee", 0, 1_000_000);
            } else if (!emptyId.isEmpty()) {
                DataStore.Product e = store.product(emptyId);
                if (e == null) throw new Http.Error(400, "Unknown empty tank type");
                if (e.weight() != p.weight()) throw new Http.Error(400, "The empty for " + p.label() + " must be the same size (" + DataStore.fmtKg(p.weight()) + " kg)");
                if (!e.brand().equalsIgnoreCase(p.brand())) {
                    fee = r.param("swapFee." + i).isEmpty() ? p.swapFee() : number(r.param("swapFee." + i), "Swap fee", 0, 1_000_000);
                }
            }
            String remark = limit(r.param("remark." + i), 80, "Remark");
            if (unit < 0) unit = p.price();
            items.add(new DataStore.LineInput(p, qty, DataStore.round(unit), emptyId, remark, DataStore.round(fee), kind, brand));
        }
        double discount = r.param("discount").isEmpty() ? 0 : number(r.param("discount"), "Discount", 0, 10_000_000);
        double subtotal = items.stream().mapToDouble(DataStore.LineInput::amount).sum();
        double total = DataStore.round(Math.max(0, subtotal - discount));
        double paid = r.param("paid").isEmpty() ? total : number(r.param("paid"), "Amount paid", 0, 10_000_000);
        return new ReceiptFields(c, items, DataStore.round(discount), DataStore.round(paid), limit(r.param("note"), 160, "Note"), customerMethod(r));
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

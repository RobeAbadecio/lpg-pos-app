import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;

/**
 * "Where does this number come from?" for the dashboard figures: the records behind a total and a
 * short working that adds up to it. Uses the same windows and rules as {@link Stats}, so the total
 * here always equals the figure on the dashboard (checked by ExplainCheck).
 *
 * Scopes: range metrics use the date-range chips (today, 7, 30, 90, all); period metrics use the
 * Daily / Monthly / Quarterly switch for the current period so far; sup-* metrics also take a supplier.
 */
final class Explain {
    private Explain() {}

    static final int MAX_ROWS = 300;

    /** Balances as they stand now, for one record (id) or all of them: these take no date range. */
    static final Set<String> NOW = Set.of("customer-balance", "receivables", "supplier-owed", "salary-owed", "stock");

    static Map<String, Object> explain(DataStore store, Map<String, String> q) {
        String metric = q.getOrDefault("metric", "");
        if (NOW.contains(metric)) return balance(store, metric, q.getOrDefault("id", ""), LocalDate.now());
        String date = q.getOrDefault("date", "");
        if (!date.isBlank()) return explainDay(store, q.getOrDefault("metric", ""), date);
        return explain(store, q.getOrDefault("metric", ""), q.getOrDefault("range", "7"),
                q.getOrDefault("period", "day"), q.getOrDefault("supplier", ""), LocalDateTime.now());
    }

    static Map<String, Object> explain(DataStore store, String metric, String range, String period, String supplier, LocalDateTime now) {
        Ctx c = new Ctx(store);
        if (metric.startsWith("sup-")) return supplierMetric(c, metric, new Stats.Periods(period, now), supplier == null ? "" : supplier);
        if (metric.startsWith("period-")) {
            Stats.Periods w = new Stats.Periods(period, now);
            String when = switch (w.period) { case "week" -> "this week"; case "month" -> "this month"; case "quarter" -> "this quarter"; default -> "today"; };
            return salesMetric(c, metric.substring("period-".length()), w::isCurrent, when);
        }
        Window win = rangeWindow(range, now, c.receipts);
        return salesMetric(c, metric, win.test, win.label);
    }

    /** One day of the daily summary: the same figures, plus "cash left from sales". */
    static Map<String, Object> explainDay(DataStore store, String metric, String dateText) {
        LocalDate day;
        try {
            day = LocalDate.parse(dateText.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new Http.Error(400, "The date must look like " + LocalDate.now());
        }
        Ctx c = new Ctx(store);
        Predicate<LocalDateTime> in = at -> at != null && at.toLocalDate().equals(day);
        String when = "on " + day.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH));
        if (metric.equals("cashLeft")) return cashLeft(c, in, when);
        if (metric.equals("drawer")) return drawer(store, day);
        if (metric.equals("expenses") || metric.equals("salaries")) return spending(c, in, when, metric.equals("salaries"));
        return salesMetric(c, metric, in, when);
    }

    /**
     * Cash on hand for the day: cash in (not GCash) − cash paid to suppliers from sales (not checks or online)
     * − paybacks to staff − expenses paid in cash.
     */
    private static Map<String, Object> cashLeft(Ctx c, Predicate<LocalDateTime> in, String when) {
        record Move(String rawTime, LocalDateTime time, String what, String who, double amount) {}
        List<Move> moves = new ArrayList<>();
        double cashIn = 0, paidOut = 0, paidBack = 0, spent = 0, skippedGcash = 0, skippedCheck = 0, skippedOnline = 0;
        for (DataStore.Receipt r : c.receipts) {
            if (!in.test(r.time()) || r.paidAtSale() <= 0) continue;
            if (r.gcash()) { skippedGcash += r.paidAtSale(); continue; }
            cashIn += r.paidAtSale();
            moves.add(new Move(r.rawTime(), r.time(), "Cash paid at sale, receipt #" + r.id(), c.customer(r.customerId()), r.paidAtSale()));
        }
        for (DataStore.Payment p : c.store.payments()) {
            if (!in.test(p.time())) continue;
            if (p.gcash()) { skippedGcash += p.amount(); continue; }
            cashIn += p.amount();
            moves.add(new Move(p.rawTime(), p.time(), (p.receiptId().startsWith("O") ? "Cash payment on the starting balance" : "Cash balance payment, receipt #" + p.receiptId()), c.customer(p.customerId()), p.amount()));
        }
        for (DataStore.SupplierPayment sp : c.store.supplierPayments()) {
            if (!in.test(sp.time()) || sp.fromStaff() || sp.fromOwner()) continue;
            if (sp.byCheck()) { skippedCheck += sp.amount(); continue; }
            if (sp.online()) { skippedOnline += sp.amount(); continue; }
            paidOut += sp.amount();
            moves.add(new Move(sp.rawTime(), sp.time(), "Cash from sales paid to supplier", c.supplier(sp.supplierId()), -sp.amount()));
        }
        for (DataStore.Payback pb : c.store.paybacks()) {
            if (!in.test(pb.time())) continue;
            paidBack += pb.amount();
            moves.add(new Move(pb.rawTime(), pb.time(), "Paid back to staff", pb.staff(), -pb.amount()));
        }
        double skippedExpenses = 0;
        for (DataStore.Expense e : c.store.expenses()) {
            if (!in.test(e.time())) continue;
            if (!e.cash()) { skippedExpenses += e.outflow(); continue; }
            spent += e.outflow();
            moves.add(new Move(e.rawTime(), e.time(), (e.payroll() ? "" : "Expense: ") + EXPENSE_LABEL.getOrDefault(e.category(), e.category()),
                    e.employee().isEmpty() ? e.note() : e.employee(), -e.outflow()));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Move m : newest(moves, Move::time)) rows.add(List.of(m.rawTime(), m.what(), m.who(), DataStore.round(m.amount())));
        List<String> left = new ArrayList<>();
        if (skippedGcash > 0.005) left.add(peso(skippedGcash) + " received by GCash (it goes to the e-wallet)");
        if (skippedCheck > 0.005) left.add(peso(skippedCheck) + " paid to suppliers by check (it comes from the bank)");
        if (skippedOnline > 0.005) left.add(peso(skippedOnline) + " paid to suppliers online");
        if (Math.abs(skippedExpenses) > 0.005) left.add(peso(skippedExpenses) + " of expenses and salaries paid online or by check");
        return result("Cash on hand " + when, cashIn - paidOut - paidBack - spent, "money",
                "The cash that should be in the drawer from " + when.substring(3) + ": cash received from customers, minus cash from sales paid to suppliers, "
                        + "minus money paid back to staff, minus expenses and salaries paid in cash (advances paid back count as cash in). Payments with the owner’s or staff’s own money don’t come out of the drawer.",
                List.of(part("Cash received from customers", cashIn, "money"), part("Cash from sales paid to suppliers", -paidOut, "money"),
                        part("Paid back to staff", -paidBack, "money"), part("Expenses and salaries paid in cash", -spent, "money")),
                List.of(col("Time", "date"), col("What", "text"), col("Who", "text"), col("In / out", "money")), rows,
                left.isEmpty() ? null : "Not counted: " + String.join("; ", left) + ".");
    }

    // ------------------------------------------------------------------ shared lookups

    private static final class Ctx {
        final DataStore store;
        final List<DataStore.Receipt> receipts;
        final Map<String, DataStore.Product> products;
        final Map<String, String> customers = new HashMap<>(), suppliers = new HashMap<>(), byBrand = new HashMap<>(), tripSupplier = new HashMap<>();

        Ctx(DataStore store) {
            this.store = store;
            receipts = store.receipts();
            products = store.productMap();
            for (DataStore.Customer cu : store.customers()) customers.put(cu.id(), cu.name());
            for (DataStore.Supplier s : store.suppliers()) {
                suppliers.put(s.id(), s.name());
                for (String b : s.brands()) byBrand.putIfAbsent(b.toLowerCase(Locale.ROOT), s.id());
            }
            for (DataStore.Refill rf : store.refills()) tripSupplier.put(rf.id(), rf.supplierId());
        }

        String customer(String id) { String n = customers.get(id); return n == null ? "Unknown customer #" + id : n; }

        String supplier(String id) {
            if (id == null || id.isEmpty()) return "No supplier set";
            String n = suppliers.get(id);
            return n == null ? "Deleted supplier #" + id : n;
        }

        String product(String id) { DataStore.Product p = products.get(id); return p == null ? "Deleted product #" + id : p.label(); }

        /** The supplier a stock change belongs to: the trip's supplier, or for new tanks the supplier of that brand. */
        String movementSupplier(DataStore.Movement m) {
            if (m.type().equals("purchase")) {
                DataStore.Product p = products.get(m.productId());
                return p == null ? "" : byBrand.getOrDefault(p.brand().toLowerCase(Locale.ROOT), "");
            }
            return tripSupplier.getOrDefault(m.refillId(), "");
        }
    }

    private record Window(Predicate<LocalDateTime> test, String label) {}

    /** The same windows as Stats.compute for the date-range chips. */
    private static Window rangeWindow(String range, LocalDateTime now, List<DataStore.Receipt> receipts) {
        LocalDate today = now.toLocalDate();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        switch (range) {
            case "today" -> {
                LocalDateTime from = today.atStartOfDay();
                return new Window(at -> at != null && !at.isBefore(from) && at.isBefore(end), "today");
            }
            case "month", "quarter" -> {
                LocalDateTime from = new Stats.Periods(range, now).curStart;
                return new Window(at -> at != null && !at.isBefore(from) && at.isBefore(end), "this " + range);
            }
            case "7", "30", "90" -> {
                int n = Integer.parseInt(range);
                LocalDateTime from = today.minusDays(n - 1).atStartOfDay();
                return new Window(at -> at != null && !at.isBefore(from) && at.isBefore(end), "in the last " + n + " days");
            }
            default -> {
                LocalDate first = receipts.stream().map(DataStore.Receipt::time).filter(Objects::nonNull)
                        .map(LocalDateTime::toLocalDate).min(Comparator.naturalOrder()).orElse(today);
                if (first.isAfter(today)) first = today;
                LocalDateTime from = first.atStartOfDay();
                return new Window(at -> at == null || !at.isBefore(from), "of all time");
            }
        }
    }

    // ------------------------------------------------------------------ output helpers

    private static Map<String, Object> col(String label, String type) { return Json.obj("label", label, "type", type); }

    private static Map<String, Object> part(String label, double amount, String type) {
        return Json.obj("label", label, "amount", DataStore.round(amount), "type", type);
    }

    private static Map<String, Object> result(String title, double total, String type, String how, List<Object> parts,
                                              List<Object> columns, List<List<Object>> rows, String note) {
        int all = rows.size();
        return Json.obj("title", title, "total", DataStore.round(total), "type", type, "how", how, "parts", parts,
                "columns", columns, "rows", all > MAX_ROWS ? new ArrayList<>(rows.subList(0, MAX_ROWS)) : rows,
                "rowCount", all, "note", note);
    }

    private static String plural(int n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    private static String peso(double v) { return String.format(Locale.ROOT, "₱%,.2f", DataStore.round(v)); }

    /** Newest first. */
    private static <T> List<T> newest(List<T> items, java.util.function.Function<T, LocalDateTime> time) {
        List<T> out = new ArrayList<>(items);
        out.sort(Comparator.comparing((T x) -> time.apply(x) == null ? LocalDateTime.MIN : time.apply(x)).reversed());
        return out;
    }

    // ------------------------------------------------------------------ sales figures

    private static Map<String, Object> salesMetric(Ctx c, String metric, Predicate<LocalDateTime> in, String when) {
        List<DataStore.Receipt> rs = newest(c.receipts.stream().filter(r -> in.test(r.time())).toList(), DataStore.Receipt::time);
        double subtotal = 0, discounts = 0, revenue = 0;
        int qty = 0;
        for (DataStore.Receipt r : rs) {
            subtotal += r.subtotal();
            discounts += r.subtotal() - r.total();
            revenue += r.total();
            qty += r.qty();
        }
        List<Object> receiptCols = List.of(col("Date", "date"), col("Receipt", "text"), col("Customer", "text"), col("Items", "text"),
                col("Qty", "int"), col("Before discount", "money"), col("Discount", "money"), col("Total", "money"));
        List<List<Object>> receiptRows = new ArrayList<>();
        for (DataStore.Receipt r : rs) {
            receiptRows.add(List.of(r.rawTime(), "#" + r.id(), c.customer(r.customerId()), Stats.itemsText(r, c.products), r.qty(),
                    r.subtotal(), DataStore.round(r.subtotal() - r.total()), r.total()));
        }
        List<Object> revenueParts = new ArrayList<>(List.of(part("Items sold, before discounts", subtotal, "money")));
        if (discounts > 0.005) revenueParts.add(part("Discounts given", -discounts, "money"));
        String receiptsText = plural(rs.size(), "receipt", "receipts");
        switch (metric) {
            case "revenue" -> {
                return result("Revenue " + when, revenue, "money",
                        "The total of every receipt " + when + " (" + receiptsText + "), after discounts. Pay-later sales count in full on the day of the sale.",
                        revenueParts, receiptCols, receiptRows, null);
            }
            case "count" -> {
                return result("Receipts " + when, rs.size(), "int", "Every sale recorded " + when + ". Each receipt counts once, however many items it has.",
                        List.of(), receiptCols, receiptRows, null);
            }
            case "qty" -> {
                return result("Cylinders sold " + when, qty, "int", "The quantity on every receipt " + when + ", added up.",
                        List.of(), receiptCols, receiptRows, null);
            }
            case "avg" -> {
                double avg = rs.isEmpty() ? 0 : revenue / rs.size();
                return result("Average sale " + when, avg, "money",
                        "Revenue " + peso(revenue) + " ÷ " + receiptsText + ".",
                        List.of(part("Revenue", revenue, "money"), part("Receipts", rs.size(), "int")), receiptCols, receiptRows, null);
            }
            case "customers" -> {
                Map<String, double[]> per = new LinkedHashMap<>(); // receipts, revenue
                for (DataStore.Receipt r : rs) {
                    double[] v = per.computeIfAbsent(r.customerId(), k -> new double[2]);
                    v[0]++;
                    v[1] += r.total();
                }
                List<Map.Entry<String, double[]>> es = new ArrayList<>(per.entrySet());
                es.sort((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]));
                List<List<Object>> rows = new ArrayList<>();
                for (var e : es) rows.add(List.of(c.customer(e.getKey()), (int) e.getValue()[0], DataStore.round(e.getValue()[1])));
                return result("Customers served " + when, per.size(), "int",
                        "Different customers with at least one receipt " + when + ". A customer who bought more than once counts once.",
                        List.of(), List.of(col("Customer", "text"), col("Receipts", "int"), col("Revenue", "money")), rows, null);
            }
            case "collected", "money" -> {
                return collected(c, in, when, metric.equals("money"));
            }
            case "supplierPaid" -> {
                return supplierPayments(c, in, when, "", null);
            }
            case "profit" -> {
                return profit(c, rs, when);
            }
            default -> throw new Http.Error(400, "Unknown figure: " + metric);
        }
    }

    /** Money that came in: paid at the sale, plus balance payments made in this time. */
    private static Map<String, Object> collected(Ctx c, Predicate<LocalDateTime> in, String when, boolean periodCard) {
        record In(String rawTime, LocalDateTime time, String what, String customer, boolean gcash, double amount) {}
        List<In> items = new ArrayList<>();
        for (DataStore.Receipt r : c.receipts) {
            if (in.test(r.time()) && r.paidAtSale() > 0) {
                items.add(new In(r.rawTime(), r.time(), "Paid at sale, receipt #" + r.id(), c.customer(r.customerId()), r.gcash(), r.paidAtSale()));
            }
        }
        for (DataStore.Payment p : c.store.payments()) {
            if (in.test(p.time())) {
                items.add(new In(p.rawTime(), p.time(), (p.receiptId().startsWith("O") ? "Payment on the starting balance" : "Balance payment on receipt #" + p.receiptId()), c.customer(p.customerId()), p.gcash(), p.amount()));
            }
        }
        double atSale = 0, later = 0, cash = 0, gcash = 0;
        for (In i : items) {
            if (i.what().startsWith("Paid at sale")) atSale += i.amount();
            else later += i.amount();
            if (i.gcash()) gcash += i.amount();
            else cash += i.amount();
        }
        List<List<Object>> rows = new ArrayList<>();
        for (In i : newest(items, In::time)) rows.add(List.of(i.rawTime(), i.what(), i.customer(), i.gcash() ? "GCash" : "Cash", DataStore.round(i.amount())));
        List<Object> parts = new ArrayList<>(List.of(part("Paid at the sale", atSale, "money"), part("Paid later on balances", later, "money")));
        String how = "Money actually received " + when + ": what customers paid at the sale, plus payments on older unpaid balances. "
                + "Unpaid (pay-later) amounts are not counted until they're paid.";
        return result((periodCard ? "Money in " : "Cash collected ") + when, atSale + later, "money",
                how + " Of this, " + peso(cash) + " was cash and " + peso(gcash) + " GCash.",
                parts, List.of(col("Date", "date"), col("What", "text"), col("Customer", "text"), col("Paid with", "text"), col("Amount", "money")), rows, null);
    }

    /** Estimated gross profit: sales (after each receipt's discount) minus each tank's refill or new-tank cost. */
    private static Map<String, Object> profit(Ctx c, List<DataStore.Receipt> rs, String when) {
        Map<String, double[]> per = new LinkedHashMap<>(); // qty, sales, cost
        Set<String> uncosted = new TreeSet<>();
        double sales = 0, cost = 0;
        for (DataStore.Receipt r : rs) {
            double sub = r.subtotal(), share = sub > 0 ? r.total() / sub : 0;
            for (DataStore.Line l : r.lines()) {
                double lc = Stats.lineCost(l, c.products, uncosted);
                if (lc < 0) continue;
                double[] v = per.computeIfAbsent(l.productId(), k -> new double[3]);
                v[0] += l.qty();
                v[1] += l.amount() * share;
                v[2] += lc;
                sales += l.amount() * share;
                cost += lc;
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        for (var e : per.entrySet()) {
            double[] v = e.getValue();
            rows.add(List.of(c.product(e.getKey()), (int) v[0], DataStore.round(v[1]), DataStore.round(v[2]), DataStore.round(v[1] - v[2])));
        }
        rows.sort((a, b) -> Double.compare(((Number) b.get(4)).doubleValue(), ((Number) a.get(4)).doubleValue()));
        String note = uncosted.isEmpty() ? null : "Left out because no refill cost is set in Products: " + String.join(", ", uncosted) + ".";
        return result("Gross profit (est.) " + when, sales - cost, "money",
                "Sales " + when + " minus what those tanks cost: the product's refill cost (or new-tank cost for a new tank). "
                        + "It's an estimate because it uses today's costs, and it doesn't include other expenses.",
                List.of(part("Sales (after discounts)", sales, "money"), part("Cost of the tanks sold", -cost, "money")),
                List.of(col("Product", "text"), col("Sold", "int"), col("Sales", "money"), col("Cost", "money"), col("Profit", "money")), rows, note);
    }

    private static final Map<String, String> SOURCE_LABEL = Map.of("cash", "Cash", "check", "Check", "online", "Online",
            "owner", "Owner’s personal money", "staff", "Staff’s own money");

    static final Map<String, String> EXPENSE_LABEL = Map.of("gasoline", "Delivery gasoline", "salary", "Salary", "advance", "Salary advance",
            "repaid", "Advance paid back", "other", "Other");

    /** How a supplier payment was paid: cash, check, online, the owner's money or staff money (the five add up to Paid). */
    private static String payWay(DataStore.SupplierPayment sp) {
        return sp.fromOwner() ? "owner" : sp.fromStaff() ? "staff" : sp.byCheck() ? "check" : sp.online() ? "online" : "cash";
    }

    /** Supplier payments in this time, optionally for one supplier and one way of paying. */
    private static Map<String, Object> supplierPayments(Ctx c, Predicate<LocalDateTime> in, String when, String only, String way) {
        List<DataStore.SupplierPayment> ps = newest(c.store.supplierPayments().stream()
                .filter(sp -> in.test(sp.time()) && (only.isEmpty() || only.equals(sp.supplierId())) && (way == null || way.equals(payWay(sp))))
                .toList(), DataStore.SupplierPayment::time);
        Map<String, Double> byWay = new LinkedHashMap<>();
        for (String k : List.of("cash", "check", "online", "owner", "staff")) byWay.put(k, 0.0);
        double total = 0;
        List<List<Object>> rows = new ArrayList<>();
        for (DataStore.SupplierPayment sp : ps) {
            total += sp.amount();
            byWay.merge(payWay(sp), sp.amount(), Double::sum);
            String bill = sp.refillId().isEmpty() ? ("purchase".equals(sp.kind()) ? "New tanks" : "")
                    : sp.refillId().startsWith("P") ? "New tanks (stock change #" + sp.refillId().substring(1) + ")"
                    : sp.refillId().startsWith("O") ? "Owed on the start date" : "Refill trip #" + sp.refillId();
            String from = sp.fromStaff() ? "Staff: " + sp.paidBy() : SOURCE_LABEL.get(payWay(sp));
            rows.add(List.of(sp.rawTime(), c.supplier(sp.supplierId()), bill, from, sp.staff(), DataStore.round(sp.amount())));
        }
        List<Object> parts = new ArrayList<>();
        if (way == null) byWay.forEach((k, v) -> { if (v > 0.005) parts.add(part(SOURCE_LABEL.get(k), v, "money")); });
        String who = only.isEmpty() ? "suppliers" : c.supplier(only);
        String title = way == null ? "Paid to " + who + " " + when : "Paid to " + who + " by " + SOURCE_LABEL.get(way).toLowerCase(Locale.ROOT) + " " + when;
        String how = way == null
                ? "Every payment to " + who + " " + when + " (" + plural(ps.size(), "payment", "payments") + "), counted on the day it was paid, whatever bill it was for."
                : "Payments to " + who + " " + when + " made with " + SOURCE_LABEL.get(way).toLowerCase(Locale.ROOT) + ".";
        return result(title, total, "money", how, parts,
                List.of(col("Date", "date"), col("Supplier", "text"), col("For", "text"), col("Paid with", "text"), col("Recorded by", "text"), col("Amount", "money")),
                rows, null);
    }

    // ------------------------------------------------------------------ supplier figures

    private static Map<String, Object> supplierMetric(Ctx c, String metric, Stats.Periods w, String only) {
        String when = switch (w.period) { case "week" -> "this week"; case "month" -> "this month"; case "quarter" -> "this quarter"; default -> "today"; };
        String who = only.isEmpty() ? "all suppliers" : c.supplier(only);
        Predicate<LocalDateTime> in = w::isCurrent;
        switch (metric) {
            case "sup-paid" -> { return supplierPayments(c, in, when, only, null); }
            case "sup-cash" -> { return supplierPayments(c, in, when, only, "cash"); }
            case "sup-check" -> { return supplierPayments(c, in, when, only, "check"); }
            case "sup-online" -> { return supplierPayments(c, in, when, only, "online"); }
            case "sup-owner" -> { return supplierPayments(c, in, when, only, "owner"); }
            case "sup-staff" -> { return supplierPayments(c, in, when, only, "staff"); }
            case "sup-trips" -> {
                List<List<Object>> rows = new ArrayList<>();
                for (DataStore.Refill rf : newest(c.store.refills(), DataStore.Refill::time)) {
                    if (rf.start() || !in.test(rf.time()) || (!only.isEmpty() && !only.equals(rf.supplierId()))) continue;
                    rows.add(List.of(rf.rawTime(), "#" + rf.id(), c.supplier(rf.supplierId()), rf.staff(), rf.note()));
                }
                return result("Refill trips " + when, rows.size(), "int", "Each time empties were sent to " + who + " " + when + ", counted on the day they were sent.",
                        List.of(), List.of(col("Sent", "date"), col("Trip", "text"), col("Supplier", "text"), col("Staff", "text"), col("Note", "text")), rows, null);
            }
            case "sup-billed", "sup-refilled", "sup-bought" -> {
                return stockMetric(c, metric, in, when, who, only);
            }
            case "sup-unpaid" -> {
                List<List<Object>> rows = new ArrayList<>();
                double total = 0;
                for (DataStore.Bill b : newest(c.store.bills(), DataStore.Bill::time)) {
                    if (b.owed() <= 0 || !in.test(b.time()) || (!only.isEmpty() && !only.equals(b.supplierId()))) continue;
                    total += b.owed();
                    rows.add(List.of(b.rawTime(), c.supplier(b.supplierId()), b.label(), DataStore.round(b.cost()), DataStore.round(b.paid()), b.owed()));
                }
                return result("Pay later: still unpaid " + when, total, "money",
                        "Bills from " + who + " dated " + when + " that still aren't fully paid, as of now. A refill bill is dated when the trip was sent.",
                        List.of(), List.of(col("Bill date", "date"), col("Supplier", "text"), col("Bill", "text"), col("Cost", "money"), col("Paid so far", "money"), col("Still owed", "money")),
                        rows, null);
            }
            default -> throw new Http.Error(400, "Unknown figure: " + metric);
        }
    }

    /** Tanks back from the refiller and new tanks bought, with what they cost. */
    private static Map<String, Object> stockMetric(Ctx c, String metric, Predicate<LocalDateTime> in, String when, String who, String only) {
        List<List<Object>> rows = new ArrayList<>();
        double cost = 0, refillCost = 0, newCost = 0;
        int refilled = 0, bought = 0;
        for (DataStore.Movement m : newest(c.store.movements(), DataStore.Movement::time)) {
            boolean receive = m.type().equals("refill-receive"), purchase = m.type().equals("purchase");
            if (!(receive || purchase) || !in.test(m.time())) continue;
            String sid = c.movementSupplier(m);
            if (!only.isEmpty() && !only.equals(sid)) continue;
            if (metric.equals("sup-refilled") && !receive) continue;
            if (metric.equals("sup-bought") && !purchase) continue;
            double amount = receive || m.unitCost() > 0 ? m.loaded() * m.unitCost() : 0;
            cost += amount;
            if (receive) { refilled += m.loaded(); refillCost += amount; } else { bought += m.loaded(); newCost += amount; }
            rows.add(List.of(m.rawTime(), c.supplier(sid), receive ? "Back from refill trip #" + m.refillId() : "New tanks bought",
                    c.product(m.productId()), m.loaded(), DataStore.round(m.unitCost()), DataStore.round(amount)));
        }
        List<Object> cols = List.of(col("Date", "date"), col("Supplier", "text"), col("What", "text"), col("Product", "text"),
                col("Tanks", "int"), col("Cost each", "money"), col("Cost", "money"));
        return switch (metric) {
            case "sup-refilled" -> result("Tanks back with load " + when, refilled, "int",
                    "Tanks that came back filled from " + who + " " + when + ", counted on the day they came back.", List.of(), cols, rows, null);
            case "sup-bought" -> result("New tanks bought " + when, bought, "int",
                    "New tanks bought from " + who + " " + when + ".", List.of(), cols, rows, null);
            default -> result("Cost of refills and new tanks " + when, cost, "money",
                    "What " + who + " charged for tanks that came back filled or were bought new " + when
                            + ": tanks × the cost each recorded at the time. This is the bill, whether or not it has been paid.",
                    List.of(part("Refills (" + plural(refilled, "tank", "tanks") + ")", refillCost, "money"), part("New tanks (" + plural(bought, "tank", "tanks") + ")", newCost, "money")),
                    cols, rows, null);
        };
    }

    // ------------------------------------------------------------------ the day's spending and the drawer

    /** Expenses (gasoline, other) or salaries (salaries and advances, less advances paid back) on one day, as the daily summary. */
    private static Map<String, Object> spending(Ctx c, Predicate<LocalDateTime> in, String when, boolean payroll) {
        double total = 0, cash = 0;
        List<List<Object>> rows = new ArrayList<>();
        Map<String, Double> byKind = new LinkedHashMap<>();
        for (DataStore.Expense e : newest(c.store.expenses(), DataStore.Expense::time)) {
            if (!in.test(e.time()) || e.payroll() != payroll) continue;
            double v = payroll ? e.outflow() : e.amount();
            total += v;
            if (e.cash()) cash += v;
            byKind.merge(EXPENSE_LABEL.getOrDefault(e.category(), e.category()), v, Double::sum);
            String what = EXPENSE_LABEL.getOrDefault(e.category(), e.category())
                    + (e.advanceDeducted() > 0 ? " (" + peso(e.advanceDeducted()) + " advance taken off, not counted again)" : "");
            rows.add(List.of(e.rawTime(), what, e.employee().isEmpty() ? e.note() : e.employee(), e.method(), DataStore.round(v)));
        }
        List<Object> parts = new ArrayList<>();
        byKind.forEach((k, v) -> parts.add(part(k.equals("Advance paid back") ? k + " (money in)" : k, v, "money")));
        return result((payroll ? "Salaries " : "Expenses ") + when, total, "money",
                payroll ? "Salaries and salary advances paid " + when + ", minus advances the employee paid back in cash. A salary counts what was handed over: "
                        + "an advance taken off it was already counted on the day the advance was given."
                        : "Money spent " + when + " besides suppliers and salaries: delivery gasoline and other costs.",
                parts, List.of(col("Time", "date"), col("What", "text"), col("Who / note", "text"), col("Paid by", "text"), col("Amount", "money")), rows,
                Math.abs(total - cash) > 0.005 ? peso(cash) + " of it was cash from the drawer; the rest was paid online or by check." : null);
    }

    /** Cash in the drawer at the end of a day: the starting cash plus each day's cash on hand since the start date. */
    private static Map<String, Object> drawer(DataStore store, LocalDate day) {
        DataStore.Opening o = store.opening();
        if (o == null) throw new Http.Error(400, "Cash in the drawer is counted from the starting balances, which aren't entered yet.");
        if (day.isBefore(o.start())) throw new Http.Error(400, "Records start on " + o.start() + ".");
        TreeMap<LocalDate, Double> cash = Stats.cashByDay(store);
        List<List<Object>> rows = new ArrayList<>();
        double sum = o.cash(), moved = 0;
        rows.add(List.of(o.start().toString(), "Starting cash", DataStore.round(o.cash()), DataStore.round(sum)));
        for (var e : cash.subMap(o.start(), true, day, true).entrySet()) {
            sum += e.getValue();
            moved += e.getValue();
            rows.add(List.of(e.getKey().toString(), "Cash on hand that day", DataStore.round(e.getValue()), DataStore.round(sum)));
        }
        Collections.reverse(rows);
        String when = day.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH));
        return result("Cash in the drawer at the end of " + when, sum, "money",
                "The starting cash on " + o.start() + " plus the cash on hand of every day since (cash in − cash out), up to " + when + ". "
                        + "Days without any cash in or out are left out.",
                List.of(part("Starting cash", o.cash(), "money"), part("Cash on hand of the days since", moved, "money")),
                List.of(col("Day", "text"), col("What", "text"), col("Cash", "money"), col("In the drawer", "money")), rows, null);
    }

    // ------------------------------------------------------------------ balances right now

    private static Map<String, Object> balance(DataStore store, String metric, String id, LocalDate today) {
        Ctx c = new Ctx(store);
        return switch (metric) {
            case "customer-balance", "receivables" -> customerBalance(c, metric.equals("receivables") ? "" : need(id));
            case "supplier-owed" -> supplierOwed(c, id);
            case "salary-owed" -> salaryOwed(c, id, today);
            default -> stock(c, id);
        };
    }

    private static String need(String id) {
        if (id == null || id.isBlank()) throw new Http.Error(400, "Choose the record to explain");
        return id;
    }

    /** What a customer owes now (or all customers, id ""): the receipts not fully paid, less what was paid on them, plus the starting balance left. */
    private static Map<String, Object> customerBalance(Ctx c, String id) {
        if (!id.isEmpty() && !c.customers.containsKey(id)) throw new Http.Error(404, "Customer not found");
        Map<String, Double> paid = c.store.paidByReceipt(c.receipts);
        double totals = 0, paidOn = 0, owed = 0;
        int unpaid = 0;
        List<List<Object>> rows = new ArrayList<>();
        for (DataStore.Receipt r : newest(c.receipts, DataStore.Receipt::time)) {
            if (!id.isEmpty() && !r.customerId().equals(id)) continue;
            double bal = DataStore.balance(r, paid);
            if (bal <= 0) continue;
            double got = DataStore.round(paid.getOrDefault(r.id(), 0.0));
            totals += r.total();
            paidOn += got;
            owed += bal;
            unpaid++;
            rows.add(List.of(r.rawTime(), "Receipt #" + r.id(), c.customer(r.customerId()), r.total(), got, bal));
        }
        Map<String, Double> start = c.store.openingOwed();
        DataStore.Opening o = c.store.opening();
        double startLeft = 0;
        for (var e : start.entrySet()) {
            if (!id.isEmpty() && !e.getKey().equals(id)) continue;
            double had = o == null ? e.getValue() : o.customerMoney().getOrDefault(e.getKey(), e.getValue());
            startLeft += e.getValue();
            owed += e.getValue();
            rows.add(List.of(o == null ? "" : o.time().format(DataStore.TIME), "Owed on the start date", c.customer(e.getKey()),
                    DataStore.round(had), DataStore.round(had - e.getValue()), e.getValue()));
        }
        List<Object> parts = new ArrayList<>();
        if (unpaid > 0) {
            parts.add(part("Unpaid receipts (" + unpaid + "): their totals", totals, "money"));
            if (paidOn > 0.005) parts.add(part("Already paid on them", -paidOn, "money"));
        }
        if (startLeft > 0.005) parts.add(part("Starting balance still unpaid", startLeft, "money"));
        String who = id.isEmpty() ? "all customers" : c.customer(id);
        return result(id.isEmpty() ? "Owed by customers" : "Balance of " + who, owed, "money",
                (id.isEmpty() ? "What customers still owe, today." : "What " + who + " still owes, today.")
                        + " Each receipt not fully paid counts its total after discount, minus what was paid at the sale and paid later. "
                        + "Fully paid receipts count zero. Payments go to the oldest balance first.",
                parts, List.of(col("Date", "date"), col("What", "text"), col("Customer", "text"), col("Total", "money"), col("Paid so far", "money"),
                        col("Still owed", "money")), rows, unpaid == 0 && startLeft <= 0.005 ? "Nothing is owed: every receipt is paid in full." : null);
    }

    /** What the store owes a supplier now (or all suppliers, id ""): every bill not fully paid, less what was paid on it. */
    private static Map<String, Object> supplierOwed(Ctx c, String id) {
        if (!id.isEmpty() && !c.suppliers.containsKey(id)) throw new Http.Error(404, "Supplier not found");
        double cost = 0, paid = 0, owed = 0;
        int n = 0;
        List<List<Object>> rows = new ArrayList<>();
        for (DataStore.Bill b : newest(c.store.bills(), DataStore.Bill::time)) {
            if (b.owed() <= 0 || (!id.isEmpty() && !id.equals(b.supplierId()))) continue;
            cost += b.cost();
            paid += b.paid();
            owed += b.owed();
            n++;
            rows.add(List.of(b.rawTime(), c.supplier(b.supplierId()), b.label(), DataStore.round(b.cost()), DataStore.round(b.paid()), b.owed()));
        }
        List<Object> parts = new ArrayList<>();
        if (n > 0) {
            parts.add(part("Unpaid bills (" + n + "): what they cost", cost, "money"));
            if (paid > 0.005) parts.add(part("Already paid on them", -paid, "money"));
        }
        String who = id.isEmpty() ? "suppliers" : c.supplier(id);
        return result("Owed to " + who, owed, "money",
                "What the store still owes " + who + ", today: each refill trip (tanks back with load × the cost each), new tanks bought and "
                        + "what was owed on the start date, minus what was paid on it. Fully paid bills count zero. Payments go to the oldest bill first.",
                parts, List.of(col("Bill date", "date"), col("Supplier", "text"), col("Bill", "text"), col("Cost", "money"), col("Paid so far", "money"),
                        col("Still owed", "money")), rows, n == 0 ? "Nothing is owed: every bill is paid in full." : null);
    }

    /** Salary still owed now to one employee (or everyone on the payroll, id ""): each pay day since they were put on the payroll. */
    private static Map<String, Object> salaryOwed(Ctx c, String id, LocalDate today) {
        List<DataStore.Employee> staff = c.store.employees().stream().filter(e -> id.isEmpty() || e.id().equals(id)).toList();
        if (!id.isEmpty() && staff.isEmpty()) throw new Http.Error(404, "Employee not found");
        double salaries = 0, paidFor = 0, owed = 0;
        int days = 0;
        List<List<Object>> rows = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (DataStore.Employee e : staff) {
            Map<LocalDate, Double> paid = c.store.paidPerPayday(e, null);
            for (LocalDate d : DataStore.duePaydays(e, today)) {
                double salary = e.salaryOn(d), got = paid.getOrDefault(d, 0.0), left = Math.max(0, salary - got);
                salaries += salary;
                paidFor += Math.min(got, salary);
                owed += left;
                days++;
                rows.add(List.of(d.toString(), e.name(), DataStore.round(salary), DataStore.round(got), DataStore.round(left)));
            }
            e.rateList().forEach((until, v) -> changed.add(e.name() + "’s salary was " + peso(v) + " for pay days before " + until));
        }
        rows.sort((a, b) -> ((String) b.get(0)).compareTo((String) a.get(0)));
        Map<String, Double> adv = c.store.advancesOwed();
        double advanced = staff.stream().mapToDouble(e -> adv.getOrDefault(e.name(), 0.0)).sum();
        List<String> notes = new ArrayList<>(changed);
        if (advanced > 0.005) notes.add("Unpaid advances (" + peso(advanced) + ") are kept apart: they are taken off when the salary is paid");
        String who = id.isEmpty() ? "staff" : staff.get(0).name();
        return result("Salary still owed to " + who, owed, "money",
                "For every pay day from the day " + (id.isEmpty() ? "each employee" : who) + " was put on the payroll up to today (" + plural(days, "pay day", "pay days")
                        + "): the salary for that pay day minus what was paid for it (handed over + any advance taken off). Fully paid pay days count zero. "
                        + "Each salary counts for the pay day it was paid for, early or late.",
                List.of(part("Salary for those pay days", salaries, "money"), part("Paid for them", -paidFor, "money")),
                List.of(col("Pay day", "text"), col("Employee", "text"), col("Salary", "money"), col("Paid for it", "money"), col("Still owed", "money")), rows,
                notes.isEmpty() ? null : String.join(". ", notes) + ".");
    }

    /**
     * The store's own tanks of one product (or all, id ""): with load + empty + at the refiller, worked out from the starting
     * balances, every stock change and every sale. Customers' tanks (owed or kept) aren't the store's stock.
     */
    private static Map<String, Object> stock(Ctx c, String id) {
        if (!id.isEmpty() && !c.products.containsKey(id)) throw new Http.Error(404, "Product not found");
        Map<String, int[]> by = new LinkedHashMap<>(); // source -> with load, empty, at refiller
        java.util.function.BiConsumer<String, int[]> add = (k, v) -> {
            int[] t = by.computeIfAbsent(k, x -> new int[3]);
            for (int i = 0; i < 3; i++) t[i] += v[i];
        };
        Predicate<String> mine = pid -> id.isEmpty() ? c.products.containsKey(pid) : id.equals(pid);
        DataStore.Opening o = c.store.opening();
        if (o != null) {
            o.stock().forEach((pid, v) -> { if (mine.test(pid)) add.accept("On the start date", new int[]{v[0], v[1], 0}); });
            o.atRefiller().values().forEach(m -> m.forEach((pid, n) -> { if (mine.test(pid)) add.accept("On the start date", new int[]{0, 0, n}); }));
        }
        Map<String, String> type = Map.of("count", "Stock counts and corrections", "damaged", "Stock counts and corrections", "other", "Stock counts and corrections",
                "condition", "Stock counts and corrections", "delivery", "New tanks bought", "purchase", "New tanks bought",
                "refill-send", "Sent to the refiller", "refill-receive", "Back from the refiller with load", "refill-reject", "Back from the refiller unfilled");
        int damaged = 0;
        for (DataStore.Movement m : c.store.movements()) {
            if (!mine.test(m.productId())) continue;
            damaged += m.damaged();
            add.accept(type.getOrDefault(m.type(), "Stock counts and corrections"), new int[]{m.loaded(), m.empty(), m.atRefiller()});
        }
        for (DataStore.Line l : c.store.lines()) {
            if (mine.test(l.productId())) add.accept("Sold (tanks with load out)", new int[]{-l.qty(), 0, 0});
            if (l.returnedEmpty() && !l.looseEmpty() && mine.test(l.emptyProductId())) add.accept("Empties taken in on sales", new int[]{0, l.qty(), 0});
        }
        for (DataStore.TankReturn t : c.store.tankReturns()) if (!t.looseEmpty() && mine.test(t.productId())) add.accept("Owed tanks brought back", new int[]{0, t.qty(), 0});
        int[] sum = new int[3];
        List<List<Object>> rows = new ArrayList<>();
        by.forEach((k, v) -> {
            for (int i = 0; i < 3; i++) sum[i] += v[i];
            rows.add(List.of(k, v[0], v[1], v[2]));
        });
        // The dashboard counts what's on hand, never below zero per product.
        int loaded = 0, empty = 0, out = 0;
        Map<String, DataStore.Stock> now = c.store.stock();
        for (String pid : c.products.keySet()) {
            if (!mine.test(pid)) continue;
            DataStore.Stock st = now.getOrDefault(pid, DataStore.Stock.NONE);
            loaded += Math.max(0, st.loaded());
            empty += Math.max(0, st.empty());
            out += Math.max(0, st.atRefiller());
        }
        rows.add(List.of("Now", loaded, empty, out));
        String what = id.isEmpty() ? "all products" : c.product(id);
        List<String> notes = new ArrayList<>();
        if (loaded != sum[0] || empty != sum[1] || out != sum[2]) notes.add("A product below zero counts as zero in the total");
        if (damaged != 0) notes.add(plural(damaged, "tank is", "tanks are") + " marked damaged (from an older version) and not counted");
        List<Object> looseRows = c.store.looseEmpties();
        if (id.isEmpty() && !looseRows.isEmpty()) notes.add("Generic and other-brand empties taken in are listed apart and not counted here");
        return result("Tanks of " + what, loaded + empty + out, "int",
                "The store’s own tanks of " + what + ": with load + empty + at the refiller. Worked out from the starting balances, every stock change "
                        + "and every sale: a sale takes a tank with load out and its empty comes in. Tanks customers still owe and customers’ own tanks kept "
                        + "at the store are not counted.",
                List.of(part("With load", loaded, "int"), part("Empty", empty, "int"), part("At the refiller", out, "int")),
                List.of(col("From", "text"), col("With load", "int"), col("Empty", "int"), col("At the refiller", "int")), rows,
                notes.isEmpty() ? null : String.join(". ", notes) + ".");
    }
}

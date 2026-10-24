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

    static Map<String, Object> explain(DataStore store, Map<String, String> q) {
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
            if (!e.cash()) { skippedExpenses += e.amount(); continue; }
            spent += e.amount();
            moves.add(new Move(e.rawTime(), e.time(), "Expense: " + EXPENSE_LABEL.getOrDefault(e.category(), e.category()), e.employee().isEmpty() ? e.note() : e.employee(), -e.amount()));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Move m : newest(moves, Move::time)) rows.add(List.of(m.rawTime(), m.what(), m.who(), DataStore.round(m.amount())));
        List<String> left = new ArrayList<>();
        if (skippedGcash > 0.005) left.add(peso(skippedGcash) + " received by GCash (it goes to the e-wallet)");
        if (skippedCheck > 0.005) left.add(peso(skippedCheck) + " paid to suppliers by check (it comes from the bank)");
        if (skippedOnline > 0.005) left.add(peso(skippedOnline) + " paid to suppliers online");
        if (skippedExpenses > 0.005) left.add(peso(skippedExpenses) + " of expenses paid online or by check");
        return result("Cash on hand " + when, cashIn - paidOut - paidBack - spent, "money",
                "The cash that should be in the drawer from " + when.substring(3) + ": cash received from customers, minus cash from sales paid to suppliers, "
                        + "minus money paid back to staff, minus expenses paid in cash. Payments with the owner’s or staff’s own money don’t come out of the drawer.",
                List.of(part("Cash received from customers", cashIn, "money"), part("Cash from sales paid to suppliers", -paidOut, "money"),
                        part("Paid back to staff", -paidBack, "money"), part("Expenses paid in cash", -spent, "money")),
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

    static final Map<String, String> EXPENSE_LABEL = Map.of("gasoline", "Delivery gasoline", "salary", "Salary", "advance", "Salary advance", "other", "Other");

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
}

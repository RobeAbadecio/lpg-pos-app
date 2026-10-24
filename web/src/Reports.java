import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;

/**
 * The dashboard's Sales, Suppliers and Money views: one row per day, week, month or quarter, newest
 * first. A row holds everything that view shows for that period, so opening a row needs no new request.
 * With from/to (period "day" only), the rows are the days in between, to open a longer period day by day.
 */
final class Reports {
    private Reports() {}

    static final List<String> PERIODS = List.of("day", "week", "month", "quarter");

    static Stats.Periods window(String period, String from, String to, LocalDateTime now) {
        if (!from.isBlank() && !to.isBlank()) {
            try {
                return new Stats.Periods(LocalDate.parse(from.trim()), LocalDate.parse(to.trim()), now);
            } catch (DateTimeParseException e) {
                throw new Http.Error(400, "Dates must look like " + now.toLocalDate());
            }
        }
        return new Stats.Periods(PERIODS.contains(period) ? period : "day", now);
    }

    /** One row per period, newest first, with the period's key and its first and last day. */
    private static <A> List<Object> rows(Stats.Periods w, Map<String, A> byKey, Function<A, Map<String, Object>> json) {
        List<Object> out = new ArrayList<>();
        for (int i = w.keys.size() - 1; i >= 0; i--) {
            String k = w.keys.get(i);
            Map<String, Object> o = json.apply(byKey.get(k));
            LocalDate[] d = w.days.get(k);
            o.put("key", k);
            o.put("start", d[0].toString());
            o.put("end", d[1].toString());
            out.add(o);
        }
        return out;
    }

    private static <A> Map<String, A> buckets(Stats.Periods w, java.util.function.Supplier<A> make) {
        Map<String, A> m = new LinkedHashMap<>();
        for (String k : w.keys) m.put(k, make.get());
        return m;
    }

    private static double r(double v) { return DataStore.round(v); }

    // ------------------------------------------------------------------ sales

    private static final class Sales {
        double revenue, discounts, atSale, later, laterCash, cash, online, unpaid;
        int receipts, tanks;
        final Set<String> customers = new HashSet<>();
        final Map<String, double[]> products = new LinkedHashMap<>(); // label -> tanks, sales
        final Map<String, Double> owing = new HashMap<>(); // customer -> still unpaid from this period's sales

        Map<String, Object> json() {
            List<Object> prods = new ArrayList<>();
            products.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]))
                    .forEach(e -> prods.add(Json.obj("label", e.getKey(), "qty", (int) e.getValue()[0], "revenue", r(e.getValue()[1]))));
            List<Object> owed = new ArrayList<>();
            owing.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(10)
                    .forEach(e -> owed.add(Json.obj("customer", e.getKey(), "balance", r(e.getValue()))));
            return Json.obj("revenue", r(revenue), "receipts", receipts, "tanks", tanks, "customers", customers.size(),
                    "discounts", r(discounts), "collected", r(cash + online), "cash", r(cash), "online", r(online),
                    "atSale", r(atSale), "later", r(later), "laterCash", r(laterCash), "unpaid", r(unpaid), "unpaidCustomers", owing.size(),
                    "products", prods, "owing", owed);
        }
    }

    /**
     * Per period: sales (after discounts), receipts and tanks; money received from customers in it, in cash
     * and online (GCash), at the sale or paid later on a balance; and how much of the period's sales is
     * still unpaid today, with who owes it.
     */
    static Map<String, Object> sales(DataStore store, String period, String from, String to, LocalDateTime now) {
        Stats.Periods w = window(period, from, to, now);
        Map<String, Sales> by = buckets(w, Sales::new);
        Map<String, DataStore.Product> products = store.productMap();
        Map<String, String> names = new HashMap<>();
        for (DataStore.Customer c : store.customers()) names.put(c.id(), c.name());
        List<DataStore.Receipt> receipts = store.receipts();
        Map<String, Double> paid = store.paidByReceipt(receipts);
        for (DataStore.Receipt rc : receipts) {
            Sales a = by.get(w.key(rc.time()));
            if (a == null) continue;
            a.revenue += rc.total();
            a.receipts++;
            a.tanks += rc.qty();
            a.discounts += rc.discount();
            a.customers.add(rc.customerId());
            a.atSale += rc.paidAtSale();
            if (rc.gcash()) a.online += rc.paidAtSale(); else a.cash += rc.paidAtSale();
            double bal = DataStore.balance(rc, paid);
            if (bal > 0) {
                a.unpaid += bal;
                a.owing.merge(names.getOrDefault(rc.customerId(), "Unknown customer #" + rc.customerId()), bal, Double::sum);
            }
            double sub = rc.subtotal(), share = sub > 0 ? rc.total() / sub : 0;
            for (DataStore.Line l : rc.lines()) {
                DataStore.Product p = products.get(l.productId());
                double[] v = a.products.computeIfAbsent(p == null ? "Deleted product #" + l.productId() : p.label(), k -> new double[2]);
                v[0] += l.qty();
                v[1] += l.amount() * share;
            }
        }
        for (DataStore.Payment p : store.payments()) {
            Sales a = by.get(w.key(p.time()));
            if (a == null) continue;
            a.later += p.amount();
            if (p.gcash()) a.online += p.amount();
            else {
                a.cash += p.amount();
                a.laterCash += p.amount();
            }
        }
        return Json.obj("period", w.period, "rows", rows(w, by, Sales::json));
    }

    // ------------------------------------------------------------------ suppliers

    private static final class Sup {
        double refillCost, purchaseCost, paid, paidCash, paidCheck, paidOnline, paidOwner, paidStaff, unpaid;
        int trips, refilled, bought;
        final Map<String, double[]> products = new TreeMap<>(); // label -> refilled, bought, cost

        boolean isEmpty() { return trips + refilled + bought == 0 && paid == 0 && unpaid == 0 && refillCost + purchaseCost == 0; }

        void pay(DataStore.SupplierPayment sp) {
            paid += sp.amount();
            if (sp.byCheck()) paidCheck += sp.amount(); else if (sp.online()) paidOnline += sp.amount(); else paidCash += sp.amount();
            if (sp.fromOwner()) paidOwner += sp.amount();
            if (sp.fromStaff()) paidStaff += sp.amount();
        }

        Map<String, Object> json() {
            List<Object> prods = new ArrayList<>();
            products.forEach((label, v) -> prods.add(Json.obj("label", label, "refilled", (int) v[0], "bought", (int) v[1], "amount", r(v[2]))));
            return Json.obj("trips", trips, "refilled", refilled, "bought", bought, "refillCost", r(refillCost), "purchaseCost", r(purchaseCost),
                    "billed", r(refillCost + purchaseCost), "paid", r(paid), "paidCash", r(paidCash), "paidCheck", r(paidCheck),
                    "paidOnline", r(paidOnline), "paidOwner", r(paidOwner), "paidStaff", r(paidStaff), "unpaid", r(unpaid), "products", prods);
        }
    }

    /** One period: all suppliers together, and each supplier that did something in it. */
    private static final class SupRow {
        final Sup total = new Sup();
        final Map<String, Sup> each = new LinkedHashMap<>();

        List<Sup> targets(String supplierId) {
            return List.of(total, each.computeIfAbsent(supplierId == null ? "" : supplierId, k -> new Sup()));
        }
    }

    /**
     * Per period and supplier: refill trips; tanks back filled and new tanks bought, per LPG product, with
     * what they cost (counted when they came in); what was paid (when it was paid) by cash, check or online,
     * and how much of that was the owner's or staff's own money; and what's still unpaid of that period's
     * bills. supplierId: one supplier only, or blank for all. Also what each supplier is owed today.
     */
    static Map<String, Object> suppliers(DataStore store, String period, String supplierId, String from, String to, LocalDateTime now) {
        String only = supplierId == null ? "" : supplierId;
        Stats.Periods w = window(period, from, to, now);
        Map<String, SupRow> by = buckets(w, SupRow::new);
        Map<String, String> names = new LinkedHashMap<>(), byBrand = new HashMap<>(), tripSupplier = new HashMap<>();
        List<Object> list = new ArrayList<>();
        Map<String, Double> owedNow = new HashMap<>();
        for (DataStore.Bill b : store.bills()) if (b.owed() > 0) owedNow.merge(b.supplierId(), b.owed(), Double::sum);
        for (DataStore.Supplier s : store.suppliers()) {
            names.put(s.id(), s.name());
            for (String b : s.brands()) byBrand.putIfAbsent(b.toLowerCase(Locale.ROOT), s.id());
            list.add(Json.obj("id", s.id(), "name", s.name(), "brands", s.brands(), "owed", r(owedNow.getOrDefault(s.id(), 0.0))));
        }
        Map<String, DataStore.Product> products = store.productMap();
        for (DataStore.Refill rf : store.refills()) {
            tripSupplier.put(rf.id(), rf.supplierId());
            SupRow row = by.get(w.key(rf.time()));
            if (rf.start() || row == null || (!only.isEmpty() && !only.equals(rf.supplierId()))) continue;
            for (Sup a : row.targets(rf.supplierId())) a.trips++;
        }
        for (DataStore.Movement m : store.movements()) {
            boolean receive = m.type().equals("refill-receive"), purchase = m.type().equals("purchase");
            if (!receive && !purchase) continue;
            DataStore.Product p = products.get(m.productId());
            String sid = receive ? tripSupplier.getOrDefault(m.refillId(), "")
                    : p == null ? "" : byBrand.getOrDefault(p.brand().toLowerCase(Locale.ROOT), "");
            SupRow row = by.get(w.key(m.time()));
            if (row == null || (!only.isEmpty() && !only.equals(sid))) continue;
            double cost = receive || m.unitCost() > 0 ? m.loaded() * m.unitCost() : 0;
            String label = p == null ? "Deleted product #" + m.productId() : p.label();
            for (Sup a : row.targets(sid)) {
                double[] v = a.products.computeIfAbsent(label, k -> new double[3]);
                if (receive) { a.refilled += m.loaded(); a.refillCost += cost; v[0] += m.loaded(); }
                else { a.bought += m.loaded(); a.purchaseCost += cost; v[1] += m.loaded(); }
                v[2] += cost;
            }
        }
        for (DataStore.SupplierPayment sp : store.supplierPayments()) {
            SupRow row = by.get(w.key(sp.time()));
            if (row == null || (!only.isEmpty() && !only.equals(sp.supplierId()))) continue;
            for (Sup a : row.targets(sp.supplierId())) a.pay(sp);
        }
        for (DataStore.Bill b : store.bills()) {
            SupRow row = by.get(w.key(b.time()));
            if (b.owed() <= 0 || row == null || (!only.isEmpty() && !only.equals(b.supplierId()))) continue;
            for (Sup a : row.targets(b.supplierId())) a.unpaid += b.owed();
        }
        List<Object> rows = rows(w, by, row -> {
            Map<String, Object> o = row.total.json();
            List<Object> each = new ArrayList<>();
            row.each.forEach((id, a) -> {
                if (a.isEmpty()) return;
                Map<String, Object> x = a.json();
                x.put("id", id);
                x.put("name", id.isEmpty() ? "No supplier set" : names.getOrDefault(id, "Deleted supplier #" + id));
                each.add(x);
            });
            o.put("suppliers", each);
            return o;
        });
        return Json.obj("period", w.period, "supplierId", only, "suppliers", list,
                "owedNow", r(owedNow.values().stream().mapToDouble(Double::doubleValue).sum()), "rows", rows);
    }

    // ------------------------------------------------------------------ money

    private static final class Money {
        double cashIn, onlineIn, supplierPaid, supplierCash, supplierCheck, supplierOnline, supplierOwnStaff, paidBack, expenses, expensesCash,
                salaries, salariesCash;
        final Map<String, Double> byCategory = new LinkedHashMap<>();
        final List<DataStore.Expense> entries = new ArrayList<>();

        Money() { for (String c : DataStore.EXPENSE_CATEGORIES) byCategory.put(c, 0.0); }

        double cashOut() { return supplierCash + expensesCash + salariesCash + paidBack; }

        Map<String, Object> json() {
            Map<String, Object> cats = new LinkedHashMap<>();
            byCategory.forEach((k, v) -> cats.put(k, r(v)));
            List<Object> list = new ArrayList<>();
            for (int i = entries.size() - 1; i >= 0; i--) list.add(expenseJson(entries.get(i)));
            return Json.obj("cashIn", r(cashIn), "onlineIn", r(onlineIn), "supplierPaid", r(supplierPaid), "supplierCash", r(supplierCash),
                    "supplierCheck", r(supplierCheck), "supplierOnline", r(supplierOnline), "supplierOwnMoney", r(supplierOwnStaff),
                    "paidBack", r(paidBack), "expenses", r(expenses), "expensesCash", r(expensesCash), "byCategory", cats,
                    "salaries", r(salaries), "salariesCash", r(salariesCash),
                    "spent", r(supplierPaid + expenses + salaries), "cashOut", r(cashOut()), "cashOnHand", r(cashIn - cashOut()), "entries", list);
        }
    }

    static Map<String, Object> expenseJson(DataStore.Expense e) {
        return Json.obj("id", e.id(), "time", e.rawTime(), "category", e.category(), "amount", r(e.amount()), "employee", e.employee(),
                "method", e.method(), "recordedBy", e.recordedBy(), "note", e.note(), "advanceDeducted", r(e.advanceDeducted()));
    }

    /**
     * Per period: money in from customers (cash, online); money out to suppliers (by cash, check or online,
     * and how much was the owner's or staff's own money); expenses by category; paybacks to staff; and cash
     * on hand = cash in − cash out (cash paid to suppliers from sales, cash expenses, paybacks). Also the
     * salary advances not yet taken off a salary, and the names used before (for the expense form).
     */
    static Map<String, Object> money(DataStore store, String period, String from, String to, LocalDateTime now) {
        Stats.Periods w = window(period, from, to, now);
        Map<String, Money> by = buckets(w, Money::new);
        for (DataStore.Receipt rc : store.receipts()) {
            Money a = by.get(w.key(rc.time()));
            if (a == null) continue;
            if (rc.gcash()) a.onlineIn += rc.paidAtSale(); else a.cashIn += rc.paidAtSale();
        }
        for (DataStore.Payment p : store.payments()) {
            Money a = by.get(w.key(p.time()));
            if (a == null) continue;
            if (p.gcash()) a.onlineIn += p.amount(); else a.cashIn += p.amount();
        }
        for (DataStore.SupplierPayment sp : store.supplierPayments()) {
            Money a = by.get(w.key(sp.time()));
            if (a == null) continue;
            a.supplierPaid += sp.amount();
            if (sp.fromOwner() || sp.fromStaff()) a.supplierOwnStaff += sp.amount();
            if (sp.byCheck()) a.supplierCheck += sp.amount();
            else if (sp.online()) a.supplierOnline += sp.amount();
            if (sp.cashFromDrawer()) a.supplierCash += sp.amount();
        }
        for (DataStore.Payback pb : store.paybacks()) {
            Money a = by.get(w.key(pb.time()));
            if (a != null) a.paidBack += pb.amount();
        }
        Set<String> employees = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (DataStore.User u : store.users()) employees.add(u.username());
        for (DataStore.Expense e : store.expenses()) {
            if (!e.employee().isEmpty()) employees.add(e.employee());
            Money a = by.get(w.key(e.time()));
            if (a == null) continue;
            a.byCategory.merge(e.category(), e.amount(), Double::sum);
            // Salaries, advances and advances paid back are shown under Salaries, not as expenses.
            if (e.payroll()) {
                a.salaries += e.outflow();
                if (e.cash()) a.salariesCash += e.outflow();
                continue;
            }
            a.expenses += e.amount();
            if (e.cash()) a.expensesCash += e.amount();
            a.entries.add(e);
        }
        List<Object> advances = new ArrayList<>();
        store.advancesOwed().forEach((k, v) -> advances.add(Json.obj("employee", k, "owed", v)));
        // Cash in the drawer at the end of each period (and at its start), counted from the starting cash.
        DataStore.Opening start = store.opening();
        TreeMap<LocalDate, Double> cash = Stats.cashByDay(store);
        List<Object> rows = rows(w, by, Money::json);
        for (Object o : rows) {
            @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) o;
            LocalDate first = LocalDate.parse((String) row.get("start")), last = LocalDate.parse((String) row.get("end"));
            if (last.isAfter(now.toLocalDate())) last = now.toLocalDate();
            row.put("drawer", Stats.drawer(start, cash, last));
            row.put("drawerBefore", Stats.drawer(start, cash, first.minusDays(1)));
            row.put("startCash", start != null && !start.start().isBefore(first) && !start.start().isAfter(last) ? start.cash() : null);
        }
        return Json.obj("period", w.period, "rows", rows, "advances", advances, "employees", new ArrayList<>(employees),
                "startDate", start == null ? null : start.start().toString());
    }
}

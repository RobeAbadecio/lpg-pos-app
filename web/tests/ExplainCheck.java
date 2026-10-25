import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Every clickable dashboard figure: the breakdown's total equals the figure the dashboard shows,
 * and its parts add up to that total. Run with a data folder, e.g. one made by web/demo/make_demo_data.py.
 */
public class ExplainCheck {
    static int checked;

    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); checked++; }
    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }
    static double num(Object o) { return o == null ? 0 : ((Number) o).doubleValue(); }
    static boolean same(double a, double b) { return Math.abs(a - b) < 0.011; }

    static void matches(Map<String, Object> ex, double expected, String name) {
        check(same(num(ex.get("total")), expected), name + ": breakdown " + ex.get("total") + " vs dashboard " + expected);
        @SuppressWarnings("unchecked") List<Object> parts = (List<Object>) ex.get("parts");
        if (parts != null && !parts.isEmpty() && !name.contains("avg") && parts.stream().allMatch(p -> ex.get("type").equals(obj(p).get("type")))) {
            double sum = 0;
            for (Object p : parts) sum += num(obj(p).get("amount"));
            check(same(sum, num(ex.get("total"))), name + ": parts add up");
        }
        check(ex.get("title") != null && ex.get("how") != null, name + ": has a title and an explanation");
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        DataStore store = new DataStore(dir);
        LocalDateTime now = LocalDateTime.now();

        for (String range : List.of("today", "month", "quarter", "7", "30", "90", "all")) {
            Map<String, Object> t = obj(Stats.compute(store, range).get("totals"));
            for (String m : List.of("revenue", "count", "qty", "avg", "customers", "collected", "supplierPaid", "profit")) {
                Map<String, Object> ex = Explain.explain(store, m, range, "day", "", now);
                matches(ex, num(t.get(m)), range + " " + m);
            }
        }
        for (String period : List.of("day", "month", "quarter")) {
            Map<String, Object> rv = obj(Stats.revenue(store, period).get("current"));
            // The Customers tab's tiles follow the same switch, so they must agree with the revenue card.
            Map<String, Object> kt = obj(Stats.compute(store, period.equals("day") ? "today" : period).get("totals"));
            check(same(num(kt.get("revenue")), num(rv.get("revenue"))) && num(kt.get("count")) == num(rv.get("count")), period + ": tiles agree with the revenue card");
            matches(Explain.explain(store, "period-revenue", "7", period, "", now), num(rv.get("revenue")), period + " revenue");
            matches(Explain.explain(store, "period-money", "7", period, "", now), num(rv.get("cash")) + num(rv.get("gcash")), period + " money in");

            List<String> sups = new ArrayList<>(List.of(""));
            for (DataStore.Supplier s : store.suppliers()) sups.add(s.id());
            for (String sid : sups) {
                Map<String, Object> c = obj(Stats.suppliers(store, period, sid).get("current"));
                String[][] pairs = {{"sup-billed", "billed"}, {"sup-paid", "paid"}, {"sup-trips", "trips"}, {"sup-refilled", "refilled"},
                        {"sup-bought", "bought"}, {"sup-cash", "paidCash"}, {"sup-check", "paidCheck"}, {"sup-online", "paidOnline"}, {"sup-owner", "paidOwner"},
                        {"sup-staff", "paidStaff"}, {"sup-unpaid", "unpaid"}};
                for (String[] p : pairs) matches(Explain.explain(store, p[0], "7", period, sid, now), num(c.get(p[1])), period + " supplier " + sid + " " + p[0]);
            }
        }
        // Daily summary: each of the last 14 days.
        for (int i = 0; i < 14; i++) {
            String date = now.toLocalDate().minusDays(i).toString();
            Map<String, Object> t = obj(Stats.daily(store, date, 1).get("totals"));
            String[][] pairs = {{"revenue", "revenue"}, {"collected", "collected"}, {"supplierPaid", "paidSuppliers"}, {"cashLeft", "cashLeft"}, {"profit", "profit"},
                    {"expenses", "expenses"}, {"salaries", "salaries"}};
            for (String[] p : pairs) matches(Explain.explain(store, Map.of("metric", p[0], "date", date)), num(t.get(p[1])), date + " " + p[0]);
            if (t.get("drawer") != null) matches(Explain.explain(store, Map.of("metric", "drawer", "date", date)), num(t.get("drawer")), date + " drawer");
        }
        balances(store);
        try {
            Explain.explain(store, "nonsense", "7", "day", "", now);
            check(false, "unknown figure refused");
        } catch (Http.Error e) {
            check(e.status == 400, "unknown figure is a 400");
        }
        System.out.println("ExplainCheck passed (" + checked + " checks)");
    }

    static Map<String, Object> now(DataStore store, String metric, String id) {
        return Explain.explain(store, Map.of("metric", metric, "id", id));
    }

    /**
     * 5.4.0: the ? beside balances that stand now. Each is recomputed here from the records, independently of the
     * code that shows it, and must match the figure the POS and the dashboard show.
     */
    static void balances(DataStore store) {
        // Customers: Σ unpaid receipts (lines × (price + swap fee) − discount − paid) + the starting balance left.
        Map<String, Double> paidOn = new HashMap<>();
        for (DataStore.Receipt r : store.receipts()) paidOn.merge(r.id(), r.paidAtSale(), Double::sum);
        for (DataStore.Payment p : store.payments()) paidOn.merge(p.receiptId(), p.amount(), Double::sum);
        Map<String, Double> owes = new HashMap<>();
        for (DataStore.Receipt r : store.receipts()) {
            double sub = 0;
            for (DataStore.Line l : r.lines()) sub += l.qty() * (l.unitPrice() + l.swapFee());
            double left = Math.max(0, Math.max(0, sub - r.discount()) - paidOn.getOrDefault(r.id(), 0.0));
            if (left > 0.004) owes.merge(r.customerId(), left, Double::sum);
        }
        DataStore.Opening o = store.opening();
        if (o != null) o.customerMoney().forEach((cid, v) -> owes.merge(cid, Math.max(0, v - paidOn.getOrDefault("O" + cid, 0.0)), Double::sum));
        double all = 0;
        for (DataStore.Customer c : store.customers()) {
            double expected = owes.getOrDefault(c.id(), 0.0);
            all += expected;
            matches(now(store, "customer-balance", c.id()), expected, "balance of customer " + c.id());
        }
        for (var e : owes.entrySet()) if (store.customer(e.getKey()) == null) all += e.getValue(); // receipts of deleted customers still count
        double receivables = num(Stats.receivables(store).get("total"));
        check(same(receivables, all), "dashboard receivables " + receivables + " = records " + all);
        matches(now(store, "receivables", ""), all, "owed by customers");

        // Suppliers: Σ (bill cost − paid) over unpaid bills; all of them = the dashboard's "still owed to suppliers".
        Map<String, Object> inv = Stats.inventory(store);
        double sup = 0;
        for (DataStore.Supplier s : store.suppliers()) {
            double expected = store.bills().stream().filter(b -> b.supplierId().equals(s.id())).mapToDouble(b -> Math.max(0, b.cost() - b.paid())).sum();
            matches(now(store, "supplier-owed", s.id()), expected, "owed to supplier " + s.id());
        }
        for (DataStore.Bill b : store.bills()) sup += Math.max(0, b.cost() - b.paid());
        check(same(num(inv.get("owedToSuppliers")), sup), "dashboard owed to suppliers = bills");
        matches(now(store, "supplier-owed", ""), sup, "owed to all suppliers");

        // Stock: the rows (where the tanks came from) add up to the stock now, and the totals match the dashboard.
        Map<String, DataStore.Stock> stock = store.stock();
        for (DataStore.Product p : store.products()) {
            DataStore.Stock st = stock.getOrDefault(p.id(), DataStore.Stock.NONE);
            Map<String, Object> ex = now(store, "stock", p.id());
            matches(ex, Math.max(0, st.loaded()) + Math.max(0, st.empty()) + Math.max(0, st.atRefiller()), "stock of product " + p.id());
            @SuppressWarnings("unchecked") List<List<Object>> rows = (List<List<Object>>) ex.get("rows");
            int[] sum = new int[3];
            for (List<Object> r : rows) if (!"Now".equals(r.get(0))) for (int i = 0; i < 3; i++) sum[i] += ((Number) r.get(i + 1)).intValue();
            check(sum[0] == st.loaded() && sum[1] == st.empty() && sum[2] == st.atRefiller(), "stock of product " + p.id() + ": every movement adds up");
        }
        matches(now(store, "stock", ""), num(inv.get("totalTanks")), "tanks in all");

        // Salary still owed: per employee and in all, as the Salaries view shows it.
        Map<String, Object> pay = store.payroll(java.time.LocalDate.now());
        @SuppressWarnings("unchecked") List<Object> people = (List<Object>) pay.get("employees");
        for (Object x : people) {
            Map<String, Object> e = obj(x);
            if (e.get("id") != null) matches(now(store, "salary-owed", (String) e.get("id")), num(e.get("owed")), "salary owed to " + e.get("name"));
        }
        matches(now(store, "salary-owed", ""), num(pay.get("totalOwed")), "salary owed to staff");
        for (String m : List.of("customer-balance", "supplier-owed", "stock", "salary-owed")) {
            try {
                now(store, m, "no-such-id");
                check(false, m + ": unknown id refused");
            } catch (Http.Error e) {
                check(e.status == 404, m + ": unknown id is a 404");
            }
        }
    }
}

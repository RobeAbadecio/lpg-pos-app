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
        if (parts != null && !parts.isEmpty() && "money".equals(ex.get("type")) && !name.contains("avg")) {
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
            String[][] pairs = {{"revenue", "revenue"}, {"collected", "collected"}, {"supplierPaid", "paidSuppliers"}, {"cashLeft", "cashLeft"}, {"profit", "profit"}};
            for (String[] p : pairs) matches(Explain.explain(store, Map.of("metric", p[0], "date", date)), num(t.get(p[1])), date + " " + p[0]);
        }
        try {
            Explain.explain(store, "nonsense", "7", "day", "", now);
            check(false, "unknown figure refused");
        } catch (Http.Error e) {
            check(e.status == 400, "unknown figure is a 400");
        }
        System.out.println("ExplainCheck passed (" + checked + " checks)");
    }
}

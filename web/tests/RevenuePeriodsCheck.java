import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
public class RevenuePeriodsCheck {
    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }
    static double revenue(Map<String, Object> out, String part) { return ((Number) obj(out.get(part)).get("revenue")).doubleValue(); }
    @SuppressWarnings("unchecked")
    static List<Object> series(Map<String, Object> out) { return (List<Object>) out.get("series"); }
    static double at(Map<String, Object> out, String key) {
        for (Object o : series(out)) if (obj(o).get("key").equals(key)) return ((Number) obj(o).get("revenue")).doubleValue();
        throw new AssertionError("missing " + key);
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("lpg-revenue-check-");
        String[][] sales = { // receipt id, time, qty, discount[, paid at sale, how]
            {"1", "2026-10-04 09:00:00", "1", "10", "90", "gcash"}, {"2", "2026-10-03 10:00:00", "2", "0", "200", ""}, {"3", "2026-10-03 15:00:00", "1", "0"},
            {"4", "2026-09-02 08:00:00", "3", "0"}, {"5", "2026-07-02 08:00:00", "1", "0"}, {"6", "2026-10-05 08:00:00", "1", "0"},
            {"7", "2024-01-01 08:00:00", "1", "0"}};
        StringBuilder lines = new StringBuilder(), receipts = new StringBuilder();
        for (String[] s : sales) {
            lines.append(s[0]).append(",1,1,").append(s[1]).append(',').append(s[2]).append(",100,test,0,").append(s[0]).append(",,,1,\n");
            receipts.append(s[0]).append(',').append(s[1]).append(",1,test,").append(s[3]).append(',').append(s.length > 4 ? s[4] : "0")
                    .append(",,").append(s.length > 4 ? s[5] : "").append('\n');
        }
        Files.writeString(dir.resolve("Customers.csv"), "1,Test,Customer,,\n");
        Files.writeString(dir.resolve("LPGs.csv"), "1,Fake,100,11\n");
        Files.writeString(dir.resolve("Transactions.csv"), lines);
        Files.writeString(dir.resolve("Receipts.csv"), receipts);
        Files.writeString(dir.resolve("Payments.csv"), "1,2026-10-04 10:00:00,3,1,100,test,,gcash\n2,2026-10-04 11:00:00,3,1,0.5,test,\n");
        DataStore store = new DataStore(dir);
        List<DataStore.Receipt> all = store.receipts();
        List<DataStore.Payment> pays = store.payments();
        LocalDateTime now = LocalDateTime.of(2026, 10, 4, 12, 0);

        Map<String, Object> day = Stats.revenue(all, pays, "day", now);
        check(series(day).size() == 30 && obj(series(day).get(0)).get("key").equals("2026-09-05"), "30 days ending today");
        check(revenue(day, "current") == 90, "today, after discount");
        check(revenue(day, "previous") == 200, "yesterday up to the same time");
        check(at(day, "2026-10-03") == 300, "whole of yesterday in the series");
        Map<String, Object> today = obj(day.get("current"));
        check(((Number) today.get("gcash")).doubleValue() == 190 && ((Number) today.get("cash")).doubleValue() == 0.5, "money in today by GCash and cash");
        check(((Number) obj(series(day).get(28)).get("cash")).doubleValue() == 200, "yesterday's cash at the sale");

        Map<String, Object> month = Stats.revenue(all, pays, "month", now);
        check(series(month).size() == 12 && obj(series(month).get(0)).get("key").equals("2025-11"), "12 months ending this month");
        check(revenue(month, "current") == 390, "this month, future sales left out");
        check(revenue(month, "previous") == 300, "last month up to the same day");
        check(at(month, "2026-07") == 100 && at(month, "2026-08") == 0, "months without sales are zero");

        Map<String, Object> quarter = Stats.revenue(all, pays, "quarter", now);
        check(series(quarter).size() == 8 && obj(series(quarter).get(0)).get("key").equals("2025-Q1"), "8 quarters ending this quarter");
        check(revenue(quarter, "current") == 390, "this quarter");
        check(revenue(quarter, "previous") == 100, "last quarter up to the same point");
        check(at(quarter, "2026-Q3") == 400, "Q3 total");
        check(Stats.revenue(all, pays, "nonsense", now).get("period").equals("day"), "unknown period falls back to day");
        System.out.println("RevenuePeriodsCheck passed");
    }
}

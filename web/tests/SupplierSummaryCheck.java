import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
public class SupplierSummaryCheck {
    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }
    static double num(Map<String, Object> o, String k) { return ((Number) o.get(k)).doubleValue(); }
    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> out, String k) { return (List<Object>) out.get(k); }
    static Map<String, Object> find(Map<String, Object> out, String listName, String field, String value) {
        for (Object o : list(out, listName)) if (obj(o).get(field).equals(value)) return obj(o);
        throw new AssertionError("missing " + value);
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("lpg-supplier-check-");
        Files.writeString(dir.resolve("Customers.csv"), "");
        Files.writeString(dir.resolve("LPGs.csv"), "1,Fake,100,11\n2,Other,100,11\n");
        Files.writeString(dir.resolve("Suppliers.csv"), "1,Acme,,Fake,\n2,Beta,,Other,\n");
        Files.writeString(dir.resolve("Refills.csv"), "1,2026-10-03 09:00:00,1,test,\n2,2026-10-04 08:00:00,2,test,\n3,2026-09-01 08:00:00,1,test,\n");
        Files.writeString(dir.resolve("StockMovements.csv"), String.join("\n",
                "1,2026-10-03 09:00:00,1,refill-send,0,-5,test,,0,5,1,0",
                "2,2026-10-04 10:00:00,1,refill-receive,4,0,test,,0,-4,1,500",
                "3,2026-10-04 10:00:00,1,refill-reject,0,1,test,,0,-1,1,0",
                "4,2026-10-04 08:00:00,2,refill-send,0,-3,test,,0,3,2,0",
                "5,2026-10-04 09:00:00,2,purchase,2,0,test,,0,0,,1500",
                "6,2026-09-01 09:00:00,1,refill-send,0,-2,test,,0,2,3,0",
                "7,2026-09-02 09:00:00,1,refill-receive,2,0,test,,0,-2,3,400",
                "8,2026-10-05 09:00:00,2,purchase,1,0,test,,0,0,,1500") + "\n");
        Files.writeString(dir.resolve("SupplierPayments.csv"), String.join("\n",
                "1,2026-10-04 11:00:00,1,1,refill,1500,test,,sales,,check",
                "2,2026-10-04 11:30:00,2,P5,purchase,1000,test,,staff,ana",
                "4,2026-10-04 11:45:00,2,P5,purchase,500,test,,owner,,cash",
                "3,2026-09-03 09:00:00,1,3,refill,800,test,,sales,") + "\n");
        DataStore store = new DataStore(dir);
        LocalDateTime now = LocalDateTime.of(2026, 10, 4, 12, 0);

        Map<String, Object> day = Stats.suppliers(store, "day", "", now);
        Map<String, Object> cur = obj(day.get("current")), prev = obj(day.get("previous"));
        check(list(day, "series").size() == 30, "30 days");
        check(num(cur, "trips") == 1 && num(cur, "sent") == 3 && num(cur, "refilled") == 4 && num(cur, "rejected") == 1 && num(cur, "bought") == 2, "today's tanks, future purchase left out");
        check(num(cur, "billed") == 5000 && num(cur, "paid") == 3000 && num(cur, "paidStaff") == 1000, "today's money");
        check(num(cur, "paidCheck") == 1500 && num(cur, "paidCash") == 0 && num(cur, "paidOwner") == 500, "cash, check and owner's money add up to paid");
        check(num(cur, "unpaid") == 1500 && num(obj(day.get("previous")), "unpaid") == 500, "pay later: still owed on each period's bills");
        check(num(prev, "trips") == 1 && num(prev, "sent") == 5, "yesterday up to the same time");
        Map<String, Object> acme = find(day, "bySupplier", "name", "Acme"), beta = find(day, "bySupplier", "name", "Beta");
        check(num(acme, "refilled") == 4 && num(acme, "rejected") == 1 && num(acme, "billed") == 2000 && num(acme, "paid") == 1500 && num(acme, "trips") == 0, "Acme today");
        check(num(beta, "trips") == 1 && num(beta, "sent") == 3 && num(beta, "bought") == 2 && num(beta, "billed") == 3000 && num(beta, "paid") == 1500, "Beta today, purchase by brand");

        Map<String, Object> one = Stats.suppliers(store, "day", "2", now);
        Map<String, Object> oc = obj(one.get("current"));
        check(num(oc, "trips") == 1 && num(oc, "sent") == 3 && num(oc, "bought") == 2 && num(oc, "billed") == 3000, "one supplier: tanks");
        check(num(oc, "paid") == 1500 && num(oc, "paidOwner") == 500 && num(oc, "paidCheck") == 0 && num(oc, "unpaid") == 1500, "one supplier: money");
        check(num(obj(one.get("previous")), "sent") == 0 && list(one, "bySupplier").isEmpty(), "other suppliers left out");

        Map<String, Object> month = Stats.suppliers(store, "month", "", now);
        cur = obj(month.get("current")); prev = obj(month.get("previous"));
        check(num(cur, "trips") == 2 && num(cur, "sent") == 8 && num(cur, "billed") == 5000 && num(cur, "paid") == 3000, "this month");
        check(num(prev, "trips") == 1 && num(prev, "refilled") == 2 && num(prev, "billed") == 800 && num(prev, "paid") == 800, "last month up to the same day");
        check(num(find(month, "series", "key", "2026-09"), "billed") == 800, "September in the series");

        Map<String, Object> quarter = Stats.suppliers(store, "quarter", "", now);
        check(num(find(quarter, "series", "key", "2026-Q3"), "trips") == 1 && num(find(quarter, "series", "key", "2026-Q4"), "billed") == 5000, "quarters");
        check(num(obj(quarter.get("previous")), "billed") == 0, "nothing in Q3 up to the same point");
        // Paying by check, part from sales and part from the owner's own money, is saved as two rows.
        store.paySupplier(store.supplier("1"), new DataStore.Paid(200, 0, "", 300, "check"), "test", "");
        List<DataStore.SupplierPayment> pays = store.supplierPayments();
        DataStore.SupplierPayment a = pays.get(pays.size() - 2), b = pays.get(pays.size() - 1);
        check(a.source().equals("sales") && a.amount() == 200 && a.byCheck() && b.fromOwner() && b.amount() == 300 && b.byCheck(), "check payment rows");
        check(pays.get(1).method().equals("cash") && !pays.get(1).fromOwner(), "older rows without a method are cash");
        check(store.owedToStaff().getOrDefault("ana", 0.0) == 1000, "owner's money isn't owed back like staff money");
        System.out.println("SupplierSummaryCheck passed");
    }
}

import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 5.4.0 money audit: one small store worked through by hand (sales with discounts, swap fees, pay later, payments,
 * a void and an edit, tanks owed and returned, a refill trip, staff and owner money, a purchase on credit, expenses),
 * every figure checked against the amount worked out below, and every view of the same figure checked against the
 * others: the daily summary, the reports, the dashboard and the (?) explanations. Also the payroll after a salary
 * change, centavo rounding, and two businesses kept apart. Uses fake records in temporary folders.
 */
public class MoneyAuditCheck {
    static int checked;

    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checked++;
    }

    static void expectConflict(Runnable r, String name) {
        try {
            r.run();
            check(false, name);
        } catch (DataStore.Conflict e) {
            checked++;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return (List<Object>) o; }

    static double num(Object o) { return o == null ? 0 : ((Number) o).doubleValue(); }

    static boolean same(double a, double b) { return Math.abs(a - b) < 0.005; }

    static void is(double actual, double expected, String name) { check(same(actual, expected), name + ": " + actual + ", expected " + expected); }

    /** An explanation's total is the figure, and its parts add up to it. */
    static void explains(Map<String, Object> ex, double expected, String name) {
        is(num(ex.get("total")), expected, name + " (?)");
        double sum = 0;
        for (Object p : list(ex.get("parts"))) sum += num(obj(p).get("amount"));
        if (!list(ex.get("parts")).isEmpty()) is(sum, expected, name + " (?) parts add up");
    }

    static Map<String, Object> ex(DataStore s, String metric, String key, String value) { return Explain.explain(s, Map.of("metric", metric, key, value)); }

    static void on(LocalDate day, Runnable r) {
        DataStore.recordOn(day);
        try {
            r.run();
        } finally {
            DataStore.recordNow();
        }
    }

    public static void main(String[] args) throws Exception {
        rounding();
        store();
        payroll();
        apart();
        System.out.println("MoneyAuditCheck passed (" + checked + " checks)");
    }

    /** Money is rounded to the centavo on its decimal value, halves away from zero. */
    static void rounding() {
        double[][] cases = {{1.005, 1.01}, {10.075, 10.08}, {2.675, 2.68}, {4.995, 5.00}, {-1.005, -1.01}, {0.1 + 0.2, 0.3}, {1234567.885, 1234567.89},
                {1005 * 0.1 / 100, 1.01}, {0.004, 0}, {-0.004, 0}};
        for (double[] c : cases) check(DataStore.round(c[0]) == c[1], "round(" + c[0] + ") = " + c[1] + ", got " + DataStore.round(c[0]));
        check(1 / DataStore.round(-0.001) > 0, "no negative zero");
    }

    static void store() throws Exception {
        Path dir = Files.createTempDirectory("lpg-money-audit-");
        Files.writeString(dir.resolve("Customers.csv"), "1,Ana,Cruz,,\n2,Ben,Reyes,,\n3,Cora,Diaz,,\n");
        // id,brand,price,weight,reorder,tankPrice,refillCost,tankCost,swapFee
        Files.writeString(dir.resolve("LPGs.csv"), "1,Petron,1000,11,5,0,800,2400,50\n2,Solane,1100,11,5,0,900,2500,50\n");
        Files.writeString(dir.resolve("Suppliers.csv"), "1,Acme,,Petron,\n2,Beta,,Solane,\n");
        Files.writeString(dir.resolve("WebUsers.csv"), "ana,s,h,2026-01-01 00:00:00,1\n");
        DataStore s = new DataStore(dir);
        DataStore.Product petron = s.product("1"), solane = s.product("2");
        DataStore.Supplier acme = s.supplier("1"), beta = s.supplier("2");
        LocalDate today = LocalDate.now(), d0 = today.minusDays(5), d1 = today.minusDays(4), d2 = today.minusDays(3), d3 = today.minusDays(2);

        // Start date d0: ₱1,000 cash; Petron 10 with load + 2 empty, Solane 5 with load; Ana owes ₱500; the store owes Acme ₱300.
        s.saveOpening(new DataStore.Opening(d0, 1000, new LinkedHashMap<>(Map.of("1", new int[]{10, 2}, "2", new int[]{5, 0})), Map.of(),
                Map.of("1", 500.0), Map.of(), Map.of(), Map.of("1", 300.0), Map.of(), "ana", ""), "ana");

        // Day 1. R1: Ana, 2 Petron with her own empties, 10% off: 2,000 − 200 = 1,800, pays 1,000 now (balance 800).
        // R2: Ben, 1 Petron for a Solane empty (+50 swap fee) and 1 Solane without an empty, all pay later: 1,050 + 1,100 = 2,150.
        // R3: Cora, 1 Solane with her empty, paid by GCash (voided on day 2).
        String[] ids = new String[3];
        on(d1, () -> {
            ids[0] = s.createReceipt("1", "ana", List.of(new DataStore.LineInput(petron, 2, 1000, "1", "", 0)), 200, 1000, "", "cash").id();
            ids[1] = s.createReceipt("2", "ana", List.of(new DataStore.LineInput(petron, 1, 1000, "2", "", 50),
                    new DataStore.LineInput(solane, 1, 1100, "", "", 0)), 0, 0, "", "cash").id();
            ids[2] = s.createReceipt("3", "ana", List.of(new DataStore.LineInput(solane, 1, 1100, "2", "", 0)), 0, 1100, "", "gcash").id();
        });
        DataStore.Receipt r1 = s.receipt(ids[0]), r2 = s.receipt(ids[1]);
        is(r1.subtotal(), 2000, "R1 subtotal");
        is(r1.total(), 1800, "R1 total after 10% off");
        is(r2.subtotal(), 2150, "R2 subtotal with swap fee");
        check(r2.qty() == 2, "R2 has 2 tanks");
        expectConflict(() -> s.createReceipt("3", "ana", List.of(new DataStore.LineInput(petron, 1, 1000, "1", "", 0)), 1001, 0, ""), "discount over subtotal refused");
        expectConflict(() -> s.createReceipt("3", "ana", List.of(new DataStore.LineInput(petron, 1, 1000, "1", "", 0)), 0, 1000.01, ""), "paying more than the total refused");
        expectConflict(() -> s.createReceipt("3", "ana", List.of(new DataStore.LineInput(petron, 99, 1000, "1", "", 0)), 0, 0, ""), "selling more than in stock refused");

        // Day 2. Ana pays 1,100: the start balance (500) first, then R1 (600 of 800). Ben brings back the Solane he owed.
        // R3 is voided; R2's Petron is corrected to ₱950 (2,100). Expenses: gasoline 150 cash, other 80 online.
        on(d2, () -> {
            List<DataStore.Payment> paid = s.addPayment("1", null, 1100, "ana", "", "cash");
            check(paid.size() == 2 && paid.get(0).receiptId().equals("O1") && same(paid.get(0).amount(), 500) && same(paid.get(1).amount(), 600),
                    "a payment settles the start balance first, then the oldest receipt");
            s.returnTanks(s.customer("2"), solane, 1, "ana", "");
            check(s.voidReceipt(ids[2]) != null, "R3 voided");
            s.updateReceipt(ids[1], "2", List.of(new DataStore.LineInput(petron, 1, 950, "2", "", 50), new DataStore.LineInput(solane, 1, 1100, "", "", 0)), 0, 0, "");
            s.addExpense("gasoline", 150, "", "cash", "ana", "", 0);
            s.addExpense("other", 80, "", "online", "ana", "", 0);
        });
        expectConflict(() -> s.addPayment("1", null, 200.01, "ana", "", "cash"), "paying more than owed refused");
        expectConflict(() -> s.returnTanks(s.customer("2"), solane, 1, "ana", ""), "returning a tank not owed refused");

        // Day 3. Send 3 Petron empties to Acme; 2 come back with load at ₱800, 1 unfilled; ₱1,000 paid from sales (₱600 left).
        // Ana pays Acme's ₱300 start balance with her own money; the store pays her back ₱100. 2 new Solane at ₱2,500 each,
        // ₱1,000 of it from the owner's money (₱4,000 left to pay Beta).
        on(d3, () -> {
            DataStore.Refill trip = s.sendToRefiller(acme, Map.of(petron, 3), "ana", "");
            s.receiveFromRefiller(trip, Map.of(petron, new int[]{2, 1}), Map.of(petron, 800.0), new DataStore.Paid(1000, 0, "", 0, "cash"), "ana", "");
            List<DataStore.SupplierPayment> sp = s.paySupplier(acme, new DataStore.Paid(0, 300, "ana", 0, "cash"), "ana", "");
            check(sp.size() == 1 && sp.get(0).refillId().equals("O1"), "a supplier payment settles the oldest bill (the start balance) first");
            s.payBackStaff("ana", 100, "ana", "");
            s.purchase(solane, 2, 2500, new DataStore.Paid(0, 0, "", 1000, "cash"), "ana", "");
        });
        expectConflict(() -> s.payBackStaff("ana", 200.01, "ana", ""), "paying staff back more than owed refused");

        // ---------------------------------------------------------------- stock: worked out by hand
        Map<String, DataStore.Stock> st = s.stock();
        DataStore.Stock p = st.get("1"), q = st.get("2");
        check(p.loaded() == 9 && p.empty() == 2 && p.atRefiller() == 0, "Petron 10 − 2 − 1 + 2 = 9 with load, 2 + 2 − 3 + 1 = 2 empty: " + p);
        check(q.loaded() == 6 && q.empty() == 2 && q.atRefiller() == 0, "Solane 5 − 1 + 2 = 6 with load, 1 swapped in + 1 returned = 2 empty: " + q);
        // Tanks are kept: 17 at the start + 2 bought = the store's tanks + tanks customers still owe (none now).
        check(p.loaded() + p.empty() + q.loaded() + q.empty() + s.owedCount("1") + s.owedCount("2") + s.owedCount("3") == 19, "every tank is accounted for");
        Map<String, Object> inv = Stats.inventory(s);
        is(num(inv.get("totalTanks")), 19, "inventory: tanks in all");
        explains(ex(s, "stock", "id", ""), 19, "tanks in all");
        explains(ex(s, "stock", "id", "1"), 11, "Petron tanks");

        // ---------------------------------------------------------------- customers
        is(s.openingOwed().getOrDefault("1", 0.0), 0, "Ana's start balance is paid");
        explains(ex(s, "customer-balance", "id", "1"), 200, "Ana owes 1,800 − 1,000 − 600");
        explains(ex(s, "customer-balance", "id", "2"), 2100, "Ben owes R2 after the edit");
        explains(ex(s, "customer-balance", "id", "3"), 0, "Cora owes nothing after the void");
        is(num(Stats.receivables(s).get("total")), 2300, "dashboard: owed by customers");
        explains(ex(s, "receivables", "id", ""), 2300, "owed by customers");

        // ---------------------------------------------------------------- suppliers and staff money
        is(num(inv.get("owedToSuppliers")), 4600, "dashboard: owed to suppliers (600 Acme + 4,000 Beta)");
        explains(ex(s, "supplier-owed", "id", "1"), 600, "owed to Acme: trip 1,600 − 1,000");
        explains(ex(s, "supplier-owed", "id", "2"), 4000, "owed to Beta: 5,000 − 1,000");
        explains(ex(s, "supplier-owed", "id", ""), 4600, "owed to suppliers");
        is(s.owedToStaff().get("ana"), 200, "owed to Ana: 300 − 100");
        is(num(inv.get("owedToRefillers")), 600, "owed to refillers");

        // ---------------------------------------------------------------- sales, money in, profit, per day and per view
        double[] revenue = {0, 3900, 0, 0}, collected = {0, 1000, 1100, 0}, cashLeft = {0, 1000, 950, -1100}, drawer = {1000, 2000, 2950, 1850};
        double[] expenses = {0, 0, 230, 0}, paidSuppliers = {0, 0, 0, 2300}, profit = {0, 600, 0, 0};
        LocalDate[] days = {d0, d1, d2, d3};
        Map<String, Object> money = Reports.money(s, "day", d0.toString(), today.toString(), LocalDateTime.now());
        Map<String, Object> sales = Reports.sales(s, "day", d0.toString(), today.toString(), LocalDateTime.now());
        for (int i = 0; i < 4; i++) {
            String day = days[i].toString();
            Map<String, Object> t = obj(Stats.daily(s, day, 1).get("totals"));
            is(num(t.get("revenue")), revenue[i], day + " daily sales");
            is(num(t.get("collected")), collected[i], day + " daily money from customers");
            is(num(t.get("cashLeft")), cashLeft[i], day + " daily cash on hand");
            is(num(t.get("drawer")), drawer[i], day + " daily cash in the drawer");
            is(num(t.get("expenses")), expenses[i], day + " daily expenses");
            is(num(t.get("paidSuppliers")), paidSuppliers[i], day + " daily paid to suppliers");
            if (revenue[i] > 0) is(num(t.get("profit")), profit[i], day + " daily profit (1,800 − 1,600 + 2,100 − 1,700)");
            explains(Explain.explain(s, Map.of("metric", "revenue", "date", day)), revenue[i], day + " sales");
            explains(Explain.explain(s, Map.of("metric", "cashLeft", "date", day)), cashLeft[i], day + " cash on hand");
            explains(Explain.explain(s, Map.of("metric", "drawer", "date", day)), drawer[i], day + " drawer");
            explains(Explain.explain(s, Map.of("metric", "expenses", "date", day)), expenses[i], day + " expenses");
            Map<String, Object> m = row(money, day), sa = row(sales, day);
            is(num(m.get("cashOnHand")), cashLeft[i], day + " report: cash on hand agrees with the daily summary");
            is(num(m.get("drawer")), drawer[i], day + " report: drawer agrees with the daily summary");
            is(num(sa.get("revenue")), revenue[i], day + " report: sales agree with the daily summary");
            is(num(sa.get("collected")), collected[i], day + " report: money in agrees with the daily summary");
        }
        Map<String, Object> week = obj(Stats.compute(s, "7").get("totals"));
        is(num(week.get("revenue")), 3900, "dashboard: 7-day revenue = R1 + R2");
        is(num(week.get("collected")), 2100, "dashboard: 7-day money in");
        is(num(week.get("profit")), 600, "dashboard: 7-day estimated profit");
        is(num(week.get("supplierPaid")), 2300, "dashboard: 7-day paid to suppliers (1,000 sales + 300 staff + 1,000 owner)");
        is(num(week.get("supplierPaidSales")), 1000, "dashboard: of which from sales");
        is(num(row(sales, d1.toString()).get("unpaid")), 2300, "report: day 1 sales still unpaid (200 + 2,100)");
        // What came in matches what went out and what's left: start cash + money in − cash out = drawer.
        is(1000 + 1000 + 1100 - 150 - 1000 - 100, 1850, "drawer by hand");
    }

    /** Each business has its own folder and store: an explanation can only see its own records. */
    static void apart() throws Exception {
        Path a = Files.createTempDirectory("lpg-money-a-"), b = Files.createTempDirectory("lpg-money-b-");
        for (Path d : List.of(a, b)) Files.writeString(d.resolve("LPGs.csv"), "1,Petron,1000,11,5,0,800,0,0\n");
        Files.writeString(a.resolve("Customers.csv"), "1,Ana,Cruz,,\n");
        Files.writeString(b.resolve("Customers.csv"), "1,Other,Store,,\n7,Only,There,,\n");
        DataStore sa = new DataStore(a), sb = new DataStore(b);
        sa.addMovement(sa.product("1"), "count", 5, 0, 0, 0, "x", "", null);
        sb.addMovement(sb.product("1"), "count", 5, 0, 0, 0, "x", "", null);
        sa.createReceipt("1", "x", List.of(new DataStore.LineInput(sa.product("1"), 1, 1000, "1", "", 0)), 0, 0, "");
        sb.createReceipt("1", "x", List.of(new DataStore.LineInput(sb.product("1"), 2, 1000, "1", "", 0)), 0, 500, "");
        explains(ex(sa, "customer-balance", "id", "1"), 1000, "business A: its customer 1");
        explains(ex(sb, "customer-balance", "id", "1"), 1500, "business B: its own customer 1");
        explains(ex(sa, "receivables", "id", ""), 1000, "business A: owed by customers leaves out B");
        try {
            ex(sa, "customer-balance", "id", "7");
            check(false, "B's customer isn't found from A");
        } catch (Http.Error e) {
            check(e.status == 404, "B's customer isn't found from A");
        }
    }

    static Map<String, Object> row(Map<String, Object> report, String key) {
        for (Object o : list(report.get("rows"))) if (obj(o).get("key").equals(key)) return obj(o);
        throw new AssertionError("no row " + key);
    }

    static Map<String, Object> person(DataStore s, LocalDate today, String name) {
        return list(s.payroll(today).get("employees")).stream().map(MoneyAuditCheck::obj).filter(o -> name.equals(o.get("name"))).findFirst().orElseThrow();
    }

    static void pay(DataStore s, String on, String name, double handed, double advance, String payday) {
        on(LocalDate.parse(on), () -> s.addExpense("salary", handed, name, "cash", "ana", "", advance, LocalDate.parse(payday)));
    }

    /** A salary change counts from the day it's entered: pay days before keep their salary. */
    static void payroll() throws Exception {
        Path dir = Files.createTempDirectory("lpg-money-payroll-");
        DataStore s = new DataStore(dir);
        LocalDate mar1 = LocalDate.parse("2026-03-01"), mar15 = LocalDate.parse("2026-03-15"), apr1 = LocalDate.parse("2026-04-01");
        DataStore.Employee[] e = new DataStore.Employee[1];
        on(LocalDate.parse("2026-01-01"), () -> e[0] = s.saveEmployee(null, "Rico", 5000, "monthly", 15, 0, ""));
        pay(s, "2026-01-15", "Rico", 5000, 0, "2026-01-15");
        pay(s, "2026-02-15", "Rico", 5000, 0, "2026-02-15");
        is(num(person(s, mar1, "Rico").get("owed")), 0, "Jan and Feb paid in full");

        // A raise on Mar 1: Jan and Feb stay paid; Mar 15 is owed at the new salary.
        on(mar1, () -> s.saveEmployee(e[0].id(), "Rico", 6000, "monthly", 15, 0, ""));
        is(num(person(s, mar1, "Rico").get("owed")), 0, "a raise doesn't make paid pay days owed again");
        expectConflict(() -> pay(s, "2026-03-02", "Rico", 1000, 0, "2026-02-15"), "the old pay day can't be paid the raise");
        is(num(person(s, mar15, "Rico").get("owed")), 6000, "Mar 15 is owed at the new salary");

        // The user's example: salary 5,000 (Rico's again from Apr 1), 2,000 paid before, 1,000 advance taken off: 2,000 still owed.
        pay(s, "2026-03-15", "Rico", 6000, 0, "2026-03-15");
        on(apr1, () -> s.saveEmployee(e[0].id(), "Rico", 5000, "monthly", 15, 0, ""));
        on(LocalDate.parse("2026-04-02"), () -> s.addExpense("advance", 1000, "Rico", "cash", "ana", "", 0));
        pay(s, "2026-04-10", "Rico", 2000, 0, "2026-04-15");
        pay(s, "2026-04-15", "Rico", 0, 1000, "2026-04-15");
        LocalDate apr15 = LocalDate.parse("2026-04-15");
        Map<String, Object> rico = person(s, apr15, "Rico");
        is(num(rico.get("owed")), 2000, "5,000 − 2,000 paid − 1,000 advance taken off");
        is(num(rico.get("advanced")), 0, "the advance is cleared");
        Map<String, Object> last = obj(rico.get("lastPayday"));
        is(num(last.get("salary")), 5000, "the pay day shows its own salary");
        is(num(last.get("left")), 2000, "the pay day shows what's left");
        // Explanations use today; the payroll view on the same day must match them.
        explains(ex(s, "salary-owed", "id", e[0].id()), num(person(s, LocalDate.now(), "Rico").get("owed")), "Rico's salary still owed today");

        // The salary was entered wrong: the fix counts for every pay day.
        s.saveEmployee(e[0].id(), "Rico", 5500, "monthly", 15, 0, "", true);
        check(s.employees().get(0).rates().isEmpty(), "earlier salaries forgotten");
        is(num(person(s, apr15, "Rico").get("owed")), 3500, "every pay day at 5,500: Jan 500 + Feb 500 + Mar 0 (6,000 paid) + Apr 2,500");

        // Saved and read back from the file.
        DataStore again = new DataStore(dir);
        is(num(person(again, apr15, "Rico").get("owed")), 3500, "same after reloading");
    }
}

import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The Sales, Suppliers and Money views (Reports), expenses with salary advances, the one-step refill and
 * entries dated another day. Uses generated fake records in a temporary folder; with a data folder as the
 * argument (a COPY of real data), also checks that the reports agree with the daily summary and the
 * supplier summary on it, read-only.
 */
public class ReportsCheck {
    static int checked;

    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checked++;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return (List<Object>) o; }

    static double num(Map<String, Object> o, String k) { return ((Number) o.get(k)).doubleValue(); }

    static boolean same(double a, double b) { return Math.abs(a - b) < 0.006; }

    static Map<String, Object> row(Map<String, Object> report, String key) {
        for (Object o : list(report.get("rows"))) if (obj(o).get("key").equals(key)) return obj(o);
        throw new AssertionError("no row " + key);
    }

    static void expectConflict(Runnable r, String name) {
        try {
            r.run();
            check(false, name);
        } catch (DataStore.Conflict e) {
            checked++;
        }
    }

    public static void main(String[] args) throws Exception {
        fixture();
        dateFixes();
        if (args.length > 0) agreesWith(Path.of(args[0]));
        System.out.println("ReportsCheck passed (" + checked + " checks)");
    }

    static void fixture() throws Exception {
        Path dir = Files.createTempDirectory("lpg-reports-check-");
        Files.writeString(dir.resolve("Customers.csv"), "1,Ana,Cruz,,\n2,Ben,Reyes,,\n");
        Files.writeString(dir.resolve("LPGs.csv"), "1,Fake,1000,11,5,0,700,0,0\n2,Other,900,11,5,0,600,0,0\n");
        Files.writeString(dir.resolve("Suppliers.csv"), "1,Acme,,Fake,\n2,Beta,,Other,\n");
        Files.writeString(dir.resolve("WebUsers.csv"), "ana,s,h,2026-01-01 00:00:00,1\n");
        DataStore store = new DataStore(dir);
        DataStore.Product fake = store.product("1"), other = store.product("2");
        LocalDate today = LocalDate.now(), yesterday = today.minusDays(1);

        // Opening stock: 10 empties of each.
        store.addMovement(fake, "count", 0, 10, 0, 0, "ana", "Opening stock", null);
        store.addMovement(other, "count", 0, 10, 0, 0, "ana", "Opening stock", null);

        // Entered today, dated yesterday: two suppliers refilled on the same day.
        DataStore.recordOn(yesterday);
        try {
            store.recordRefill(store.supplier("1"), Map.of(fake, 4), Map.of(fake, 700.0), new DataStore.Paid(2000, 0, "", 0, "online"), "ana", "");
            store.recordRefill(store.supplier("2"), Map.of(other, 3), Map.of(other, 600.0), new DataStore.Paid(1800, 0, "", 0, "check"), "ana", "");
            store.createReceipt("1", "ana", List.of(new DataStore.LineInput(fake, 2, 1000, "1", "", 0)), 0, 2000, "", "cash");
            store.createReceipt("2", "ana", List.of(new DataStore.LineInput(other, 1, 900, "2", "", 0)), 0, 400, "", "gcash");
            store.addExpense("gasoline", 300, "", "cash", "ana", "Delivery run", 0);
            store.addExpense("advance", 500, "Jun", "cash", "ana", "", 0);
        } finally {
            DataStore.recordNow();
        }
        check(store.refills().stream().allMatch(r -> r.time().toLocalDate().equals(yesterday)), "backdated refills are dated yesterday");
        check(store.receipts().stream().allMatch(r -> r.time().toLocalDate().equals(yesterday)), "backdated receipts are dated yesterday");
        check(store.expenses().stream().allMatch(e -> e.time().toLocalDate().equals(yesterday)), "backdated expenses are dated yesterday");
        DataStore.Stock fs = store.stock().get("1");
        check(fs.loaded() == 2 && fs.empty() == 8 && fs.atRefiller() == 0, "one-step refill moves empties to with load (4 refilled, 2 sold, 2 empties back)");
        expectConflict(() -> store.recordRefill(store.supplier("1"), Map.of(fake, 50), Map.of(fake, 700.0), new DataStore.Paid(0, 0, ""), "ana", ""),
                "refilling more empties than on hand is refused");
        expectConflict(() -> store.recordRefill(store.supplier("1"), Map.of(fake, 1), Map.of(fake, 700.0), new DataStore.Paid(800, 0, ""), "ana", ""),
                "paying more than the refill costs is refused");

        // Today: a salary with the advance taken off, and a payment on yesterday's unpaid receipt.
        expectConflict(() -> store.addExpense("salary", 1000, "Jun", "cash", "ana", "", 600), "taking off more than the advance is refused");
        expectConflict(() -> store.addExpense("salary", 1000, "", "cash", "ana", "", 0), "a salary needs a name");
        store.addExpense("salary", 1500, "jun", "cash", "ana", "Week 41", 500);
        check(store.advancesOwed().isEmpty(), "the advance is paid off (names match regardless of case)");
        String advanceId = store.expenses().stream().filter(e -> e.category().equals("advance")).findFirst().orElseThrow().id();
        expectConflict(() -> store.deleteExpense(advanceId), "an advance already taken off a salary can't be deleted");
        store.addExpense("other", 200, "", "online", "ana", "Load", 0);
        String receipt2 = store.receipts().stream().filter(r -> r.customerId().equals("2")).findFirst().orElseThrow().id();
        store.addPayment("2", receipt2, 300, "ana", "", "cash");

        LocalDateTime now = LocalDateTime.now();
        String y = yesterday.toString(), t = today.toString();

        Map<String, Object> sales = Reports.sales(store, "day", "", "", now);
        Map<String, Object> sy = row(sales, y), st = row(sales, t);
        check(list(sales.get("rows")).size() == 30 && obj(list(sales.get("rows")).get(0)).get("key").equals(t), "30 days, newest first");
        check(same(num(sy, "revenue"), 2900) && num(sy, "receipts") == 2 && num(sy, "tanks") == 3, "yesterday's sales");
        check(same(num(sy, "cash"), 2000) && same(num(sy, "online"), 400), "yesterday's money in by cash and online");
        check(same(num(sy, "unpaid"), 200) && num(sy, "unpaidCustomers") == 1, "yesterday's sales still unpaid today");
        check(same(num(st, "revenue"), 0) && same(num(st, "cash"), 300) && same(num(st, "later"), 300), "a balance paid today counts today as cash");
        check(list(sy.get("products")).size() == 2, "sales by product");

        Map<String, Object> sup = Reports.suppliers(store, "day", "", "", "", now);
        Map<String, Object> py = row(sup, y);
        check(num(py, "trips") == 2 && num(py, "refilled") == 7 && same(num(py, "billed"), 4600), "both refills yesterday");
        check(same(num(py, "paidOnline"), 2000) && same(num(py, "paidCheck"), 1800) && same(num(py, "paidCash"), 0), "paid by online and check");
        check(same(num(py, "unpaid"), 800) && same(num(sup, "owedNow"), 800), "what's still owed");
        check(list(py.get("suppliers")).size() == 2, "each supplier listed on the day");
        Map<String, Object> acme = obj(list(py.get("suppliers")).stream().filter(o -> obj(o).get("name").equals("Acme")).findFirst().orElseThrow());
        Map<String, Object> acmeFake = obj(list(acme.get("products")).get(0));
        check(acmeFake.get("label").equals(fake.label()) && num(acmeFake, "refilled") == 4 && same(num(acmeFake, "amount"), 2800), "tanks refilled per LPG product");
        Map<String, Object> one = Reports.suppliers(store, "week", "2", "", "", now);
        check(list(one.get("rows")).size() == 12, "12 weeks");
        double beta = list(one.get("rows")).stream().mapToDouble(o -> num(obj(o), "refilled")).sum();
        check(beta == 3, "one supplier only");
        Map<String, Object> days = Reports.suppliers(store, "day", "1", y, t, now);
        check(list(days.get("rows")).size() == 2, "a period opened day by day");

        Map<String, Object> money = Reports.money(store, "day", "", "", now);
        Map<String, Object> my = row(money, y), mt = row(money, t);
        // Yesterday: cash in 2000; supplier payments by online/check don't leave the drawer; cash expenses 300 + 500.
        check(same(num(my, "cashIn"), 2000) && same(num(my, "onlineIn"), 400), "money in");
        check(same(num(my, "supplierPaid"), 3800) && same(num(my, "supplierCash"), 0), "paid to suppliers, none from the drawer");
        check(same(num(my, "expenses"), 800) && same(num(obj(my.get("byCategory")), "advance"), 500), "expenses by category");
        check(same(num(my, "cashOnHand"), 1200), "cash on hand yesterday");
        check(same(num(mt, "cashOnHand"), 300 - 1500), "cash on hand today: salary in cash, online expense not counted");
        check(same(num(mt, "expenses"), 1700) && list(mt.get("entries")).size() == 2, "today's expense entries");
        for (String d : List.of(y, t)) {
            Map<String, Object> daily = obj(Stats.daily(store, d, 1).get("totals"));
            check(same(num(daily, "cashLeft"), num(row(money, d), "cashOnHand")), d + ": daily summary agrees with Money");
            check(same(num(Explain.explain(store, Map.of("metric", "cashLeft", "date", d)), "total"), num(daily, "cashLeft")), d + ": breakdown agrees");
        }
        check(list(Reports.money(store, "quarter", "", "", now).get("rows")).size() == 8, "8 quarters");
    }

    /** Correcting the date of a receipt or an expense moves it (and its money) to that day. */
    static void dateFixes() throws Exception {
        Path dir = Files.createTempDirectory("lpg-date-check-");
        Files.writeString(dir.resolve("Customers.csv"), "1,Ana,Cruz,,\n");
        Files.writeString(dir.resolve("LPGs.csv"), "1,Fake,1000,11,5,0,700,0,0\n");
        DataStore store = new DataStore(dir);
        DataStore.Product fake = store.product("1");
        store.addMovement(fake, "count", 5, 0, 0, 0, "ana", "", null);
        LocalDate today = LocalDate.now(), back = today.minusDays(3);
        DataStore.Receipt rc = store.createReceipt("1", "ana", List.of(new DataStore.LineInput(fake, 1, 1000, "1", "", 0)), 0, 1000, "", "cash");
        DataStore.Receipt moved = store.updateReceipt(rc.id(), "1", List.of(new DataStore.LineInput(fake, 1, 1000, "1", "", 0)), 0, 1000, "", "cash", back);
        check(moved.time().toLocalDate().equals(back) && moved.time().toLocalTime().equals(rc.time().toLocalTime()), "receipt moved to another day, same time of day");
        check(moved.lines().stream().allMatch(l -> l.time().toLocalDate().equals(back)), "its items moved too");
        LocalDateTime now = LocalDateTime.now();
        check(same(num(row(Reports.sales(store, "day", "", "", now), back.toString()), "revenue"), 1000)
                && same(num(row(Reports.sales(store, "day", "", "", now), today.toString()), "revenue"), 0), "the sale counts on its new day");

        DataStore.Expense adv = store.addExpense("advance", 500, "Jun", "cash", "ana", "", 0);
        DataStore.Expense sal = store.addExpense("salary", 3000, "Jun", "cash", "ana", "", 500);
        store.updateExpense(sal.id(), "salary", 3000, "Jun", "cash", "fixed", 500, back);
        DataStore.Expense fixed = store.expenses().stream().filter(e -> e.id().equals(sal.id())).findFirst().orElseThrow();
        check(fixed.time().toLocalDate().equals(back) && fixed.note().equals("fixed") && fixed.recordedBy().equals("ana"), "expense moved and corrected");
        expectConflict(() -> store.updateExpense(adv.id(), "advance", 200, "Jun", "cash", "", 0, null), "an advance can't drop below what was taken off");
        expectConflict(() -> store.updateExpense(sal.id(), "salary", 3000, "Jun", "cash", "", 900, null), "a salary can't take off more than the advances");
        store.updateExpense(sal.id(), "salary", 3500, "Jun", "online", "", 0, null);
        check(store.advancesOwed().getOrDefault("Jun", 0.0) == 500, "taking the advance off the salary again leaves it owed");
        check(same(num(row(Reports.money(store, "day", "", "", now), back.toString()), "expenses"), 3500), "the expense counts on its new day");
    }

    /** Read-only: the new reports agree with the existing daily summary and supplier summary on real data. */
    static void agreesWith(Path dir) throws Exception {
        DataStore store = new DataStore(dir);
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> money = Reports.money(store, "day", "", "", now), sales = Reports.sales(store, "day", "", "", now);
        for (int i = 0; i < 14; i++) {
            String d = now.toLocalDate().minusDays(i).toString();
            Map<String, Object> t = obj(Stats.daily(store, d, 1).get("totals"));
            check(same(num(row(money, d), "cashOnHand"), num(t, "cashLeft")), d + " cash on hand");
            check(same(num(row(sales, d), "revenue"), num(t, "revenue")), d + " sales");
            check(same(num(row(sales, d), "collected"), num(t, "collected")), d + " money in");
            check(same(num(row(money, d), "supplierPaid"), num(t, "paidSuppliers")), d + " paid to suppliers");
        }
        for (String period : List.of("day", "week", "month", "quarter")) {
            Map<String, Object> rv = obj(Stats.revenue(store, period).get("current"));
            Map<String, Object> cur = obj(list(Reports.sales(store, period, "", "", now).get("rows")).get(0));
            if (!period.equals("week")) check(same(num(cur, "revenue"), num(rv, "revenue")), period + " sales agree with the revenue card");
            List<String> sups = new ArrayList<>(List.of(""));
            for (DataStore.Supplier s : store.suppliers()) sups.add(s.id());
            for (String sid : sups) {
                Map<String, Object> c = obj(Stats.suppliers(store, period, sid, now).get("current"));
                Map<String, Object> r = obj(list(Reports.suppliers(store, period, sid, "", "", now).get("rows")).get(0));
                for (String k : List.of("billed", "paid", "trips", "refilled", "bought", "unpaid")) {
                    check(same(num(r, k), num(c, k)), period + " supplier " + sid + " " + k);
                }
            }
        }
    }
}

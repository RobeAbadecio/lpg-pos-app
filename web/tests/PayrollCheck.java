import java.nio.file.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 4.1: the payroll (employees, schedules, next pay day, advances, paid back, balance) and generic /
 * other-brand empties (kept out of every product's stock). Uses generated fake records in a temporary folder.
 */
public class PayrollCheck {
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

    static double num(Map<String, Object> o, String k) { return ((Number) o.get(k)).doubleValue(); }

    static boolean same(double a, double b) { return Math.abs(a - b) < 0.006; }

    static Map<String, Object> person(Map<String, Object> payroll, String name) {
        return list(payroll.get("employees")).stream().map(PayrollCheck::obj).filter(o -> name.equals(o.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("no payroll row " + name));
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("lpg-payroll-check-");
        Files.writeString(dir.resolve("Customers.csv"), "1,Ana,Cruz,,\n");
        Files.writeString(dir.resolve("LPGs.csv"), "1,Fake,1000,11,5,0,700,0,50\n2,Small,400,2.7,5,0,300,0,0\n");
        Files.writeString(dir.resolve("WebUsers.csv"), "ana,s,h,2026-01-01 00:00:00,1\n");
        String start = LocalDate.now().minusDays(30) + " 00:00:00";
        Files.writeString(dir.resolve("StartingBalances.csv"), "1," + start + ",start,,,,,,ana,saved\n2," + start + ",advance,Lea,,,250,,ana,\n");
        DataStore store = new DataStore(dir);
        DataStore.Product fake = store.product("1");
        LocalDate today = LocalDate.now();

        // ---- payroll
        store.addExpense("advance", 300, "Old", "cash", "ana", "before 4.1", 0); // a name with records but no payroll entry yet
        expectConflict(() -> store.saveEmployee(null, "", 1000, "weekly", 5, 0, ""), "a name is needed");
        expectConflict(() -> store.saveEmployee(null, "X", 1000, "twice", 15, 15, ""), "two different pay days");
        DataStore.Employee jun = store.saveEmployee(null, "Jun", 3000, "weekly", 6, 0, "");
        store.saveEmployee(null, "Mae", 8000, "twice", 30, 15, "");
        expectConflict(() -> store.saveEmployee(null, "jun", 1, "monthly", 1, 0, ""), "names are unique regardless of case");
        DataStore.Employee mae = store.employees().stream().filter(e -> e.name().equals("Mae")).findFirst().orElseThrow();
        check(mae.day1() == 15 && mae.day2() == 30, "twice-a-month days are kept in order");

        LocalDate monday = LocalDate.of(2026, 10, 19);
        check(DataStore.nextPayday(jun, monday).equals(LocalDate.of(2026, 10, 24)), "weekly: the coming Saturday");
        check(DataStore.nextPayday(jun, LocalDate.of(2026, 10, 24)).getDayOfWeek() == DayOfWeek.SATURDAY
                && DataStore.nextPayday(jun, LocalDate.of(2026, 10, 24)).equals(LocalDate.of(2026, 10, 24)), "weekly: today when today is pay day");
        check(DataStore.nextPayday(mae, LocalDate.of(2026, 2, 16)).equals(LocalDate.of(2026, 2, 28)), "twice: day 30 is the month's last day in February");
        check(DataStore.nextPayday(mae, LocalDate.of(2026, 10, 31)).equals(LocalDate.of(2026, 11, 15)), "twice: rolls into next month");
        DataStore.Employee monthly = new DataStore.Employee("9", "M", 1, "monthly", 31, 0, "", "");
        check(DataStore.nextPayday(monthly, LocalDate.of(2026, 4, 2)).equals(LocalDate.of(2026, 4, 30)), "monthly: day 31 in a 30-day month");

        store.addExpense("advance", 1000, "Jun", "cash", "ana", "", 0);
        store.addExpense("advance", 500, "jun", "online", "ana", "", 0);
        Map<String, Object> p = store.payroll(today);
        Map<String, Object> pj = person(p, "Jun");
        check(same(num(pj, "advanced"), 1500) && same(num(pj, "balance"), num(pj, "owed")), "advances are kept apart from the salary owed");
        check(same(num(p, "totalSalary"), 11000) && same(num(p, "totalAdvanced"), 2050), "payroll totals (Old's and Lea's starting advances counted)");
        check(person(p, "Old").get("id") == null && same(num(person(p, "Old"), "advanced"), 300), "names on older records show, to be set up");

        // Clear: 400 paid back in cash, the rest taken off pay day's salary.
        expectConflict(() -> store.addExpense("repaid", 2000, "Jun", "cash", "ana", "", 0), "can't pay back more than is owed");
        store.addExpense("repaid", 400, "Jun", "cash", "ana", "", 0);
        check(same(store.advancesOwed().get("Jun"), 1100), "paying back lowers the advance");
        store.addExpense("salary", 1900, "Jun", "cash", "ana", "Week 43", 1100);
        Map<String, Object> after = person(store.payroll(today), "Jun");
        check(same(num(after, "advanced"), 0) && same(num(after, "owed"), 0), "advances cleared on pay day; nothing left owed");
        check(list(after.get("history")).size() == 4 && after.get("lastPaid") != null, "history newest first, last paid");
        String repaidId = store.expenses().stream().filter(e -> e.category().equals("repaid")).findFirst().orElseThrow().id();
        expectConflict(() -> store.updateExpense(repaidId, "repaid", 5000, "Jun", "cash", "", 0, null), "editing a pay-back can't overdraw the advances");
        check(store.deleteEmployee("999") == null, "unknown employee");
        store.addExpense("advance", 200, "Mae", "cash", "ana", "", 0);
        expectConflict(() -> store.deleteEmployee(mae.id()), "can't remove someone who still owes advances");

        // Renaming moves the records with them.
        store.saveEmployee(jun.id(), "Jun R.", 3200, "monthly", 31, 0, "");
        check(store.expenses().stream().filter(e -> e.payroll() && !e.employee().equals("Mae") && !e.employee().equals("Old")).allMatch(e -> e.employee().equals("Jun R.")),
                "renaming renames their records");

        // A starting-balance advance follows a rename too.
        DataStore.Employee lea = store.saveEmployee(null, "Lea", 2000, "monthly", 30, 0, "");
        store.saveEmployee(lea.id(), "Lea B.", 2000, "monthly", 30, 0, "");
        check(same(store.advancesOwed().getOrDefault("Lea B.", 0.0), 250) && !store.advancesOwed().containsKey("Lea"), "renaming moves the starting advance");

        // Money: salaries apart from expenses; a pay-back is cash in.
        store.addExpense("gasoline", 250, "", "cash", "ana", "", 0);
        Map<String, Object> money = obj(list(Reports.money(store, "day", "", "", LocalDateTime.now()).get("rows")).get(0));
        // cash: advances 300 + 1000 + 200, salary 1900, gasoline 250 out; 400 paid back in. Online advance 500 not cash.
        check(same(num(money, "expenses"), 250), "expenses leave out salaries and advances");
        check(same(num(money, "salaries"), 300 + 1000 + 500 - 400 + 1900 + 200), "salaries: advances + salary − paid back");
        check(same(num(money, "cashOnHand"), -(250 + 300 + 1000 - 400 + 1900 + 200)), "cash on hand counts salaries and the pay-back");
        Map<String, Object> daily = obj(Stats.daily(store, today.toString(), 1).get("totals"));
        check(same(num(daily, "cashLeft"), num(money, "cashOnHand")) && same(num(daily, "expenses"), 250), "daily summary agrees");
        check(same(num(Explain.explain(store, Map.of("metric", "cashLeft", "date", today.toString())), "total"), num(daily, "cashLeft")), "breakdown agrees");

        // ---- generic and other-brand empties
        store.addMovement(fake, "count", 10, 0, 0, 0, "ana", "Opening stock", null);
        DataStore.Stock before = store.stock().get("1");
        store.createReceipt("1", "ana", List.of(
                new DataStore.LineInput(fake, 2, 1000, fake.id(), "", 50, "generic", ""),
                new DataStore.LineInput(fake, 1, 1000, fake.id(), "", 50, "other", "Shine"),
                new DataStore.LineInput(fake, 1, 1000, "", "", 0)), 0, 4150, "", "cash");
        DataStore.Stock s = store.stock().get("1");
        check(s.loaded() == before.loaded() - 4 && s.empty() == before.empty(), "loose empties don't go into the product's empties");
        List<Object> loose = store.looseEmpties();
        check(loose.size() == 2, "generic and Shine counted apart");
        Map<String, Object> g = obj(loose.stream().filter(o -> "Generic".equals(obj(o).get("label"))).findFirst().orElseThrow());
        check(((Number) g.get("qty")).intValue() == 2 && ((Number) g.get("weight")).doubleValue() == 11, "2 generic 11 kg");
        DataStore.Line shine = store.lines().stream().filter(l -> "other".equals(l.emptyKind())).findFirst().orElseThrow();
        check(shine.emptyBrand().equals("Shine") && shine.swapFee() == 50 && shine.looseEmpty(), "other brand kept with its swap fee");
        // The customer owes the one tank; they bring back a generic one.
        store.returnTanks(store.customer("1"), fake, 1, "ana", "", "generic", "");
        check(store.stock().get("1").empty() == before.empty() && ((Number) obj(store.looseEmpties().stream()
                .filter(o -> "Generic".equals(obj(o).get("label"))).findFirst().orElseThrow()).get("qty")).intValue() == 3, "a generic return is a loose empty too");
        check(store.owedTanks().getOrDefault("1", List.of()).isEmpty(), "the customer no longer owes the tank");
        System.out.println("PayrollCheck passed (" + checked + " checks)");
    }
}

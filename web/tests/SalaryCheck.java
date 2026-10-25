import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * 5.2.1: salary owed per pay day. A salary pays one pay day (chosen, or the nearest one to when it was paid);
 * the last pay day shows paid / partial / unpaid / overdue / unknown; a pay day can't be paid twice; advances stay
 * apart. Uses generated fake records in a temporary folder with fixed dates.
 */
public class SalaryCheck {
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

    static LocalDate d(String s) { return LocalDate.parse(s); }

    static Map<String, Object> person(DataStore store, String today, String name) {
        return list(store.payroll(d(today)).get("employees")).stream().map(SalaryCheck::obj).filter(o -> name.equals(o.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("no payroll row " + name));
    }

    static Map<String, Object> last(DataStore store, String today, String name) { return obj(person(store, today, name).get("lastPayday")); }

    static boolean is(Map<String, Object> period, String payday, String status, double paid) {
        return payday.equals(period.get("payday")) && status.equals(period.get("status")) && same(num(period, "paid"), paid);
    }

    static void pay(DataStore store, String on, String name, double handed, double advance, String payday) {
        DataStore.recordOn(d(on));
        try {
            store.addExpense("salary", handed, name, "cash", "ana", "", advance, payday == null ? null : d(payday));
        } finally {
            DataStore.recordNow();
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("lpg-salary-check-");
        Files.writeString(dir.resolve("Customers.csv"), "");
        Files.writeString(dir.resolve("LPGs.csv"), "1,Fake,1000,11,5,0,700,0,50\n");
        Files.writeString(dir.resolve("WebUsers.csv"), "ana,s,h,2026-01-01 00:00:00,1\n");
        // Lea was on the payroll before 5.2.1: her salary record has no pay day saved (9 columns).
        Files.writeString(dir.resolve("Employees.csv"), "1,Lea,2000.00,weekly,6,,2026-10-01 08:00:00,\n");
        Files.writeString(dir.resolve("Expenses.csv"), "1,2026-10-09 17:00:00,salary,2000.00,Lea,cash,ana,,\n");
        DataStore store = new DataStore(dir);

        // ---- older records stay and count for the nearest pay day
        Map<String, Object> lea = last(store, "2026-10-12", "Lea");
        check(is(lea, "2026-10-10", "paid", 2000), "an older salary (paid a day early, no pay day saved) pays the nearest pay day");
        Map<String, Object> rec = obj(list(person(store, "2026-10-12", "Lea").get("history")).get(0));
        check("2026-10-10".equals(rec.get("payday")) && Boolean.TRUE.equals(rec.get("paydayGuessed")), "the history says the pay day was worked out from the date");
        check(same(num(person(store, "2026-10-12", "Lea"), "owed"), 2000), "Lea's Oct 3 pay day isn't recorded: owed 2000");
        check(is(obj(list(person(store, "2026-10-12", "Lea").get("periods")).stream().map(SalaryCheck::obj)
                .filter(p -> "2026-10-03".equals(p.get("payday"))).findFirst().orElseThrow()), "2026-10-03", "overdue", 0), "Oct 3 shows overdue");

        // ---- weekly: Jun, ₱3000 every Saturday, put on the payroll Monday Oct 5
        DataStore.recordOn(d("2026-10-05"));
        store.saveEmployee(null, "Jun", 3000, "weekly", 6, 0, "");
        DataStore.recordNow();
        check(is(last(store, "2026-10-09", "Jun"), "2026-10-03", "unknown", 0) && last(store, "2026-10-09", "Jun").get("left") == null,
                "a pay day before they were on the payroll is unknown, not unpaid");
        check(same(num(person(store, "2026-10-09", "Jun"), "owed"), 0), "nothing owed before the first pay day");
        check(is(last(store, "2026-10-10", "Jun"), "2026-10-10", "unpaid", 0) && same(num(person(store, "2026-10-10", "Jun"), "owed"), 3000), "pay day today: unpaid");
        check(is(last(store, "2026-10-12", "Jun"), "2026-10-10", "overdue", 0), "pay day passed: overdue");

        // Full payment on pay day: the balance drops at once.
        pay(store, "2026-10-10", "Jun", 3000, 0, null);
        check(is(last(store, "2026-10-12", "Jun"), "2026-10-10", "paid", 3000) && same(num(person(store, "2026-10-12", "Jun"), "owed"), 0), "full payment: paid, nothing owed");
        check(same(num(person(store, "2026-10-12", "Jun"), "balance"), 0), "balance follows the salary paid");
        // Duplicate payment of the same pay day is refused.
        expectConflict(() -> pay(store, "2026-10-10", "Jun", 3000, 0, null), "the same pay day can't be paid twice");
        expectConflict(() -> pay(store, "2026-10-12", "Jun", 1, 0, "2026-10-10"), "not even ₱1 more");
        expectConflict(() -> pay(store, "2026-10-12", "Jun", 100, 0, "2026-10-14"), "a day that isn't a pay day is refused");

        // Early: ₱1000 on Friday Oct 16 counts for Saturday Oct 17.
        pay(store, "2026-10-16", "Jun", 1000, 0, null);
        Map<String, Object> fri = person(store, "2026-10-16", "Jun");
        check(is(obj(fri.get("lastPayday")), "2026-10-10", "paid", 3000) && is(obj(fri.get("upcoming")), "2026-10-17", "partial", 1000)
                && same(num(fri, "owed"), 0), "paid early: shows against the coming pay day, nothing owed yet");
        check(is(last(store, "2026-10-17", "Jun"), "2026-10-17", "partial", 1000) && same(num(last(store, "2026-10-17", "Jun"), "left"), 2000)
                && same(num(person(store, "2026-10-17", "Jun"), "owed"), 2000), "partial: ₱2000 left");
        // Late: the rest on Thursday Oct 22 (nearest pay day would be Oct 24), chosen for Oct 17.
        expectConflict(() -> pay(store, "2026-10-22", "Jun", 2500, 0, "2026-10-17"), "can't pay more than is left for a pay day");
        pay(store, "2026-10-22", "Jun", 2000, 0, "2026-10-17");
        check(is(last(store, "2026-10-23", "Jun"), "2026-10-17", "paid", 3000), "paid late: counts for the pay day it was for");
        check(same(num(obj(person(store, "2026-10-23", "Jun").get("upcoming")), "paid"), 0), "and not for the next one");

        // Advances: given Oct 20, taken off Oct 24's salary. They're apart from the salary owed.
        DataStore.recordOn(d("2026-10-20"));
        store.addExpense("advance", 1000, "Jun", "cash", "ana", "", 0);
        DataStore.recordNow();
        Map<String, Object> adv = person(store, "2026-10-24", "Jun");
        check(same(num(adv, "advanced"), 1000) && same(num(adv, "owed"), 3000), "an advance isn't a salary payment");
        expectConflict(() -> pay(store, "2026-10-24", "Jun", 1500, 1600, null), "can't take off more advance than given");
        pay(store, "2026-10-24", "Jun", 2000, 1000, null);
        adv = person(store, "2026-10-24", "Jun");
        check(is(obj(adv.get("lastPayday")), "2026-10-24", "paid", 3000) && same(num(adv, "advanced"), 0) && same(num(adv, "owed"), 0),
                "salary ₱3000 = ₱2000 handed over + ₱1000 advance: paid, advance cleared");

        // Several pay days in a row unpaid add up.
        Map<String, Object> nov = person(store, "2026-11-16", "Jun");
        check(same(num(nov, "owed"), 9000) && is(obj(nov.get("lastPayday")), "2026-11-14", "overdue", 0), "Oct 31, Nov 7 and Nov 14 unpaid: ₱9000");
        List<Object> periods = list(nov.get("periods"));
        check(periods.size() >= 6 && "2026-11-14".equals(obj(periods.get(0)).get("payday")), "recent pay days, newest first");
        check(obj(periods.stream().map(SalaryCheck::obj).filter(p -> "2026-10-17".equals(p.get("payday"))).findFirst().orElseThrow()).get("records") instanceof List<?> l
                && l.size() == 2, "each pay day lists the salaries that paid it");

        // Correcting a salary: a note change is fine; raising it past the salary isn't.
        String early = store.expenses().stream().filter(x -> x.category().equals("salary") && x.employee().equals("Jun") && x.amount() == 1000).findFirst().orElseThrow().id();
        store.updateExpense(early, "salary", 1000, "Jun", "cash", "early pay", 0, null);
        check(store.expenses().stream().filter(x -> x.id().equals(early)).findFirst().orElseThrow().payday().equals("2026-10-17"), "a correction keeps the pay day");
        expectConflict(() -> store.updateExpense(early, "salary", 1500, "Jun", "cash", "", 0, null), "a correction can't pay a pay day twice");
        // Lowering the salary later doesn't lock older records.
        DataStore.Employee jun = store.employees().stream().filter(e -> e.name().equals("Jun")).findFirst().orElseThrow();
        store.saveEmployee(jun.id(), "Jun", 2500, "weekly", 6, 0, "");
        store.updateExpense(early, "salary", 1000, "Jun", "online", "early pay", 0, null);
        checked++;
        store.saveEmployee(jun.id(), "Jun", 3000, "weekly", 6, 0, "");

        // Deleting a salary entered by mistake puts it back as owed.
        String oct24 = store.expenses().stream().filter(x -> x.category().equals("salary") && "2026-10-24".equals(x.payday())).findFirst().orElseThrow().id();
        store.deleteExpense(oct24);
        check(is(last(store, "2026-10-26", "Jun"), "2026-10-24", "overdue", 0), "deleted salary: the pay day is owed again");
        pay(store, "2026-10-26", "Jun", 3000, 0, "2026-10-24");

        // ---- page reload: a fresh store from the same files says the same
        DataStore again = new DataStore(dir);
        check(is(last(again, "2026-10-26", "Jun"), "2026-10-24", "paid", 3000) && same(num(person(again, "2026-10-26", "Jun"), "owed"), 0), "saved: same after reloading");
        check(Files.readString(dir.resolve("Expenses.csv")).startsWith("1,2026-10-09 17:00:00,salary,2000.00,Lea,cash,ana,,"), "the older record is untouched");

        // ---- twice a month (15th and 30th) and month ends
        DataStore.recordOn(d("2026-10-20"));
        store.saveEmployee(null, "Mae", 8000, "twice", 15, 30, "");
        DataStore.recordNow();
        check(is(last(store, "2026-10-22", "Mae"), "2026-10-15", "unknown", 0), "twice: before the payroll start is unknown");
        check(is(last(store, "2026-10-30", "Mae"), "2026-10-30", "unpaid", 0), "twice: the 30th");
        check(is(last(store, "2027-02-28", "Mae"), "2027-02-28", "unpaid", 0), "twice: the 30th is Feb 28");
        check("2027-03-15".equals(person(store, "2027-03-01", "Mae").get("nextPayday")), "twice: next is the 15th");
        pay(store, "2027-02-27", "Mae", 8000, 0, null);
        check(is(last(store, "2027-02-28", "Mae"), "2027-02-28", "paid", 8000), "paid a day early at month end: counts for Feb 28");
        check(DataStore.nearestPayday(store.employees().stream().filter(e -> e.name().equals("Mae")).findFirst().orElseThrow(), d("2026-11-22"))
                .equals(d("2026-11-15")), "a tie goes to the earlier pay day (late payment)");

        // ---- monthly on the 31st
        DataStore.recordOn(d("2026-09-01"));
        store.saveEmployee(null, "Rey", 12000, "monthly", 31, 0, "");
        DataStore.recordNow();
        check(is(last(store, "2026-11-30", "Rey"), "2026-11-30", "unpaid", 0), "monthly 31st: Nov 30");
        check(is(last(store, "2026-12-05", "Rey"), "2026-11-30", "overdue", 0) && same(num(person(store, "2026-12-05", "Rey"), "owed"), 36000),
                "Sep 30, Oct 31, Nov 30 owed");
        pay(store, "2026-12-01", "Rey", 5000, 0, null);
        check(is(last(store, "2026-12-05", "Rey"), "2026-11-30", "partial", 5000) && same(num(person(store, "2026-12-05", "Rey"), "owed"), 31000),
                "partial at month change counts for Nov 30");
        check("2026-12-31".equals(person(store, "2026-12-05", "Rey").get("nextPayday")), "next is Dec 31");

        // ---- totals
        Map<String, Object> all = store.payroll(d("2026-12-05"));
        double owed = list(all.get("employees")).stream().map(SalaryCheck::obj).filter(o -> o.get("owed") != null).mapToDouble(o -> num(o, "owed")).sum();
        check(same(num(all, "totalOwed"), owed), "total owed adds up");
        System.out.println("SalaryCheck passed (" + checked + " checks)");
    }
}

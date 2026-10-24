import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Starting balances (cash, tanks, tanks at a refiller, what customers and suppliers owe, our tanks customers have,
 * customers' tanks kept at the store, salary advances) and kept customer tanks, on generated fake records in a
 * temporary folder: everything carries on from the starting balances, and changes that would no longer add up are refused.
 */
public class OpeningCheck {
    static int checked;

    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checked++;
    }

    static boolean same(double a, double b) { return Math.abs(a - b) < 0.006; }

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

    static DataStore.Opening opening(LocalDate start, double cash, int loaded, int empty, int atRefiller, double custOwes, int custTanks,
                                     int kept, double supOwed, double advance) {
        Map<String, int[]> stock = new LinkedHashMap<>();
        stock.put("1", new int[]{loaded, empty});
        Map<String, Map<String, Integer>> refiller = new LinkedHashMap<>();
        if (atRefiller > 0) refiller.put("1", new LinkedHashMap<>(Map.of("1", atRefiller)));
        Map<String, Double> owes = new LinkedHashMap<>();
        if (custOwes > 0) owes.put("1", custOwes);
        Map<String, Map<String, Integer>> tanks = new LinkedHashMap<>(), keptMap = new LinkedHashMap<>();
        if (custTanks > 0) tanks.put("1", new LinkedHashMap<>(Map.of("1", custTanks)));
        if (kept > 0) keptMap.put("2", new LinkedHashMap<>(Map.of("1", kept)));
        Map<String, Double> sup = new LinkedHashMap<>(), adv = new LinkedHashMap<>();
        if (supOwed > 0) sup.put("1", supOwed);
        if (advance > 0) adv.put("Rico", advance);
        return new DataStore.Opening(start, cash, stock, refiller, owes, tanks, keptMap, sup, adv, "", "");
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("lpg-opening-check-");
        Files.writeString(dir.resolve("Customers.csv"), "1,Ana,Cruz,,\n2,Ben,Reyes,,\n");
        Files.writeString(dir.resolve("LPGs.csv"), "1,Fake,1000,11,5,0,700,0,0\n");
        Files.writeString(dir.resolve("Suppliers.csv"), "1,Acme,,Fake,\n");
        Files.writeString(dir.resolve("WebUsers.csv"), "ana,s,h,2026-01-01 00:00:00,1\n");
        DataStore store = new DataStore(dir);
        DataStore.Product fake = store.product("1");
        LocalDate today = LocalDate.now(), start = today.minusDays(3);

        check(store.opening() == null && store.startDate() == null, "no starting balances at first");
        store.saveOpening(opening(start, 5000, 10, 4, 3, 1500, 2, 2, 8000, 1000), "ana");
        DataStore.Opening o = store.opening();
        check(o != null && o.start().equals(start) && same(o.cash(), 5000), "starting balances saved");
        DataStore.Stock st = store.stock().get("1");
        check(st.loaded() == 10 && st.empty() == 4 && st.atRefiller() == 3, "stock starts from the counts");
        check(store.owedCount("1") == 2 && store.owedTanks().get("1").get(0).receiptId().equals("start"), "customer has 2 of our tanks from the start");
        check(store.depositsHeld().get("2").get("1") == 2, "2 of Ben's tanks kept");
        check(same(store.openingOwed().get("1"), 1500), "Ana owes 1500 from the start");
        check(same(store.advancesOwed().get("Rico"), 1000), "Rico's advance carried over");
        check(store.bills().stream().anyMatch(b -> b.key().equals("O1") && same(b.owed(), 8000)), "owed to Acme from the start");
        DataStore.Refill trip = store.refills().stream().filter(DataStore.Refill::start).findFirst().orElseThrow();
        check(store.refillItems(trip.id()).get(0).outstanding() == 3, "start trip holds the 3 tanks at the refiller");

        // Business carries on from there.
        store.receiveFromRefiller(trip, Map.of(fake, new int[]{3, 0}), Map.of(fake, 700.0), new DataStore.Paid(0, 0, ""), "ana", "");
        check(store.stock().get("1").loaded() == 13 && store.stock().get("1").atRefiller() == 0, "start trip received");
        DataStore.Receipt credit = store.createReceipt("1", "ana", List.of(new DataStore.LineInput(fake, 1, 1000, "", "", 0)), 0, 0, "", "cash");
        check(store.owedCount("1") == 3, "a new sale without an empty adds to the tanks owed");
        store.addPayment("1", null, 2000, "ana", "", "cash");
        check(!store.openingOwed().containsKey("1"), "payment settles the starting balance first");
        check(same(DataStore.balance(store.receipt(credit.id()), store.paidByReceipt(store.receipts())), 500), "the rest goes to the receipt");
        store.addPayment("1", null, 500, "ana", "", "cash");
        store.returnTanks(store.customer("1"), fake, 2, "ana", "");
        check(store.owedTanks().get("1").stream().noneMatch(t -> t.receiptId().equals("start")), "returns settle the starting tanks first");
        store.paySupplier(store.supplier("1"), new DataStore.Paid(3000, 0, ""), "ana", "");
        check(store.bills().stream().anyMatch(b -> b.key().equals("O1") && same(b.owed(), 5000)), "supplier payment settles the starting balance first");
        store.addExpense("salary", 4500, "rico", "cash", "ana", "", 500);
        check(same(store.advancesOwed().get("Rico"), 500), "salary takes off the starting advance");

        // Kept customer tanks: left, given back, used as the empty on a sale.
        store.addDeposit(store.customer("2"), fake, 1, "in", "ana", "");
        check(store.depositsHeld().get("2").get("1") == 3, "Ben leaves one more");
        int emptyBefore = store.stock().get("1").empty();
        expectConflict(() -> store.createReceipt("2", "ana", List.of(new DataStore.LineInput(fake, 4, 1000, "1", "", 0)), 0, 4000, "", "cash", true),
                "can't use more kept tanks than kept");
        DataStore.Receipt used = store.createReceipt("2", "ana", List.of(new DataStore.LineInput(fake, 2, 1000, "1", "", 0)), 0, 2000, "", "cash", true);
        check(store.depositsHeld().get("2").get("1") == 1, "sale used 2 kept tanks");
        check(store.stock().get("1").empty() == emptyBefore + 2, "the used kept tanks become the store's empties");
        expectConflict(() -> store.updateReceipt(used.id(), "2", List.of(new DataStore.LineInput(fake, 2, 1000, "", "", 0)), 0, 2000, ""),
                "can't drop empties that came from kept tanks");
        store.voidReceipt(used.id());
        check(store.depositsHeld().get("2").get("1") == 3, "voiding gives the kept tanks back");
        store.addDeposit(store.customer("2"), fake, 3, "back", "ana", "");
        check(!store.depositsHeld().containsKey("2"), "all of Ben's tanks given back");
        expectConflict(() -> store.addDeposit(store.customer("2"), fake, 1, "back", "ana", ""), "nothing left to give back");

        // Cash in the drawer: starting cash + cash on hand per day since the start.
        Map<String, Object> daily = Stats.daily(store, "", 14);
        double drawer = ((Number) obj(daily.get("totals")).get("drawer")).doubleValue();
        // 5000 start + 2500 paid by Ana − 3000 to Acme − 4500 salary (sale used kept tanks was voided)
        check(same(drawer, 0), "drawer = starting cash + cash in − cash out (" + drawer + ")");
        Map<String, Object> money = Reports.money(store, "month", "", "", java.time.LocalDateTime.now());
        check(same(((Number) obj(list(money.get("rows")).get(0)).get("drawer")).doubleValue(), 0), "Money view shows the same drawer");
        check(daily.get("startDate").equals(start.toString()), "daily summary knows the start date");

        // Changes that no longer add up are refused, and leave the balances as they were.
        expectConflict(() -> store.saveOpening(opening(start, 5000, 10, 4, 0, 1500, 2, 2, 8000, 1000), "ana"), "can't drop tanks already received");
        expectConflict(() -> store.saveOpening(opening(start, 5000, 10, 4, 3, 1000, 2, 2, 8000, 1000), "ana"), "can't drop below what the customer paid");
        expectConflict(() -> store.saveOpening(opening(start, 5000, 10, 4, 3, 1500, 2, 2, 2000, 1000), "ana"), "can't drop below what was paid to the supplier");
        expectConflict(() -> store.saveOpening(opening(start, 5000, 10, 4, 3, 1500, 2, 2, 8000, 0), "ana"), "can't drop an advance already taken off");
        expectConflict(() -> store.saveOpening(opening(today.plusDays(1), 5000, 10, 4, 3, 1500, 2, 2, 8000, 1000), "ana"), "start can't be after the first record");
        check(store.opening().cash() == 5000 && store.stock().get("1").loaded() == 12, "refused changes left everything as it was");
        store.saveOpening(opening(start, 6000, 11, 4, 3, 1500, 2, 2, 8000, 1000), "ana");
        check(store.stock().get("1").loaded() == 13 && same(store.opening().cash(), 6000), "a fix that still adds up is saved");
        check(store.refills().stream().filter(DataStore.Refill::start).count() == 1, "the start trip is reused, not duplicated");

        System.out.println("OpeningCheck passed (" + checked + " checks)");
    }
}

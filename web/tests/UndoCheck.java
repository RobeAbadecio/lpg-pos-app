import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Undoing records entered by mistake, on fake records in a temporary folder: each kind comes out with everything
 * that belongs to it, and an undo that later records depend on is refused with nothing changed.
 */
public class UndoCheck {
    static int checked;

    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checked++;
    }

    static boolean same(double a, double b) { return Math.abs(a - b) < 0.006; }

    static String expectConflict(Runnable r, String name) {
        try {
            r.run();
        } catch (DataStore.Conflict ex) {
            checked++;
            return ex.getMessage();
        }
        throw new AssertionError(name);
    }

    static DataStore.Stock stock(DataStore store) { return store.stock().get("1"); }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("lpg-undo-check-");
        Files.writeString(dir.resolve("Customers.csv"), "1,Ana,Cruz,,\n2,Ben,Reyes,,\n");
        Files.writeString(dir.resolve("LPGs.csv"), "1,Fake,1000,11,5,0,700,900,0\n");
        Files.writeString(dir.resolve("Suppliers.csv"), "1,Acme,,Fake,\n");
        Files.writeString(dir.resolve("WebUsers.csv"), "ana,s,h,2026-01-01 00:00:00,1\n");
        DataStore store = new DataStore(dir);
        DataStore.Product fake = store.product("1");
        DataStore.Supplier acme = store.supplier("1");
        LocalDate start = LocalDate.now().minusDays(3);
        Map<String, int[]> opening = new LinkedHashMap<>();
        opening.put("1", new int[]{10, 10});
        store.saveOpening(new DataStore.Opening(start, 0, opening, new LinkedHashMap<>(Map.of("1", new LinkedHashMap<>(Map.of("1", 2)))),
                new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), "ana", ""), "ana");

        // A refill recorded by mistake comes out with its stock and payments (staff money too).
        DataStore.Refill a = store.recordRefill(acme, Map.of(fake, 4), Map.of(fake, 700.0), new DataStore.Paid(1000, 500, "ana"), "ana", "");
        check(stock(store).loaded() == 14 && stock(store).empty() == 6, "refill adds stock");
        check(same(store.owedToStaff().get("ana"), 500), "staff money from the refill");
        DataStore.Undone u = store.undo("refill", a.id());
        check(u.what().contains("Refill trip #" + a.id()) && u.what().contains("₱1500.00"), "undo says what it took out: " + u.what());
        check(u.staff().equals("ana") && !u.rows().isEmpty(), "undo reports who entered it and the rows");
        check(stock(store).loaded() == 10 && stock(store).empty() == 10, "refill undone: stock back");
        check(store.refills().stream().noneMatch(r -> r.id().equals(a.id())), "trip gone");
        check(store.supplierPayments().isEmpty(), "its payments gone");
        check(same(store.owedToStaff().getOrDefault("ana", 0.0), 0), "staff money gone");
        check(store.undoTarget("refill", a.id()) == null, "nothing left to undo");

        // The tanks from a refill were sold: the undo is refused and nothing changes.
        DataStore.Refill b = store.recordRefill(acme, Map.of(fake, 4), Map.of(fake, 700.0), new DataStore.Paid(0, 0, ""), "ana", "");
        store.createReceipt("1", "ana", List.of(new DataStore.LineInput(fake, 12, 1000, "1", "", 0)), 0, 12000, "", "cash", false);
        int moves = store.movements().size();
        String why = expectConflict(() -> store.undo("refill", b.id()), "sold tanks block the undo");
        check(why.contains("already sold"), "says the tanks were sold: " + why);
        check(store.movements().size() == moves && store.refills().stream().anyMatch(r -> r.id().equals(b.id())), "refused undo changes nothing");
        check(stock(store).loaded() == 2, "stock unchanged after a refused undo");
        store.purchase(fake, 5, 900, new DataStore.Paid(4500, 0, ""), "ana", "");

        // Undo one receiving: the tanks are at the refiller again, its payment is gone, then the whole trip.
        DataStore.Refill c = store.sendToRefiller(acme, Map.of(fake, 3), "ana", "");
        DataStore.Movement got = store.receiveFromRefiller(c, Map.of(fake, new int[]{2, 1}), Map.of(fake, 700.0), new DataStore.Paid(700, 0, ""), "ana", "");
        check(store.refillItems(c.id()).get(0).outstanding() == 0 && same(store.refillPaid(c.id()), 700), "received and paid");
        store.undo("receive", got.id());
        check(store.refillItems(c.id()).get(0).outstanding() == 3, "tanks at the refiller again");
        check(same(store.refillPaid(c.id()), 0), "the payment made when receiving is gone");
        expectConflict(() -> store.undo("movement", store.movements().get(store.movements().size() - 1).id()), "a trip's stock change is undone with its trip");
        store.undo("refill", c.id());
        check(stock(store).atRefiller() == 2, "only the start date's tanks at the refiller");
        String startTrip = store.refills().stream().filter(DataStore.Refill::start).findFirst().orElseThrow().id();
        expectConflict(() -> store.undo("refill", startTrip), "the start date's trip is changed in the starting balances");

        // A customer payment split over two receipts comes out whole.
        store.createReceipt("2", "ana", List.of(new DataStore.LineInput(fake, 1, 1000, "1", "", 0)), 0, 0, "", "cash", false);
        store.createReceipt("2", "ana", List.of(new DataStore.LineInput(fake, 1, 1000, "1", "", 0)), 0, 0, "", "cash", false);
        List<DataStore.Payment> parts = store.addPayment("2", null, 1500, "ana", "", "cash");
        check(parts.size() == 2, "payment split over two receipts");
        u = store.undo("payment", parts.get(1).id());
        check(store.payments().isEmpty() && u.what().contains("₱1500.00 from Ben Reyes"), "both parts undone: " + u.what());

        // A tank return.
        store.createReceipt("1", "ana", List.of(new DataStore.LineInput(fake, 1, 1000, "", "", 0)), 0, 1000, "", "cash", false);
        DataStore.TankReturn ret = store.returnTanks(store.customer("1"), fake, 1, "ana", "");
        check(store.owedCount("1") == 0, "returned");
        store.undo("return", ret.id());
        check(store.owedCount("1") == 1, "owes the tank again");

        // Kept tanks: taking back "left with us" is refused while they were given back; undo in the other order works.
        DataStore.Deposit in = store.addDeposit(store.customer("2"), fake, 2, "in", "ana", "");
        DataStore.Deposit back = store.addDeposit(store.customer("2"), fake, 2, "back", "ana", "");
        expectConflict(() -> store.undo("deposit", in.id()), "kept tanks already given back");
        store.undo("deposit", back.id());
        check(store.depositsHeld().get("2").get("1") == 2, "kept again");
        store.undo("deposit", in.id());
        check(!store.depositsHeld().containsKey("2"), "none kept");

        // New tanks bought with a payment; a stock count.
        DataStore.Movement bought = store.purchase(fake, 3, 900, new DataStore.Paid(2700, 0, ""), "ana", "");
        int loaded = stock(store).loaded();
        store.undo("movement", bought.id());
        check(stock(store).loaded() == loaded - 3 && store.supplierPayments().stream().noneMatch(p -> p.refillId().equals("P" + bought.id())), "purchase and its payment gone");
        List<DataStore.Movement> made = new ArrayList<>();
        store.adjustTo(fake, "count", 0, 0, null, "ana", "", made);
        check(stock(store).empty() == 0, "counted");
        store.undo("movement", made.get(0).id());
        check(stock(store).empty() > 0, "count undone");

        // Supplier payments: one payment over several bills comes out whole; a payback can't be left larger than what staff put in.
        DataStore.Refill d = store.recordRefill(acme, Map.of(fake, 1), Map.of(fake, 700.0), new DataStore.Paid(0, 0, ""), "ana", "");
        DataStore.Refill e = store.recordRefill(acme, Map.of(fake, 1), Map.of(fake, 700.0), new DataStore.Paid(0, 0, ""), "ana", "");
        List<DataStore.SupplierPayment> paid = store.paySupplier(acme, new DataStore.Paid(1000, 400, "ana"), "ana", "");
        check(paid.size() >= 2 && same(paid.stream().mapToDouble(DataStore.SupplierPayment::amount).sum(), 1400), "one payment, two sources");
        DataStore.Payback pb = store.payBackStaff("ana", 400, "ana", "");
        why = expectConflict(() -> store.undo("supplier-payment", paid.get(0).id()), "payback larger than what staff put in");
        check(why.contains("paid back"), "says why: " + why);
        store.undo("payback", pb.id());
        store.undo("supplier-payment", paid.get(0).id());
        Set<String> ids = new HashSet<>();
        paid.forEach(p -> ids.add(p.id()));
        check(store.supplierPayments().stream().noneMatch(p -> ids.contains(p.id())), "the whole payment is gone");

        // Editing: the old record comes out and the corrected one goes in as one change.
        DataStore.Refill f = store.recordRefill(acme, Map.of(fake, 2), Map.of(fake, 700.0), new DataStore.Paid(1400, 0, ""), "ana", "");
        int loadedBefore = stock(store).loaded() - 2;
        DataStore.Refill g = store.replacing("refill", f.id(), () ->
                store.recordRefill(acme, Map.of(fake, 3), Map.of(fake, 650.0), new DataStore.Paid(1950, 0, ""), "ana", ""));
        check(stock(store).loaded() == loadedBefore + 3, "edited refill: 3 tanks instead of 2");
        check(same(store.refillCost(g.id()), 1950) && same(store.refillPaid(g.id()), 1950), "edited refill: new cost and payment");
        check(store.refills().stream().noneMatch(r -> r.id().equals(f.id()) && !r.id().equals(g.id())) || f.id().equals(g.id()), "old trip replaced");
        int movesBefore = store.movements().size(), paysBefore = store.supplierPayments().size();
        expectConflict(() -> {
            try {
                store.replacing("refill", g.id(), () -> store.recordRefill(acme, Map.of(fake, 999), Map.of(fake, 650.0), new DataStore.Paid(0, 0, ""), "ana", ""));
            } catch (DataStore.Conflict ex) {
                throw ex;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }, "an edit the new record can't be saved for");
        check(store.movements().size() == movesBefore && store.supplierPayments().size() == paysBefore && same(store.refillCost(g.id()), 1950), "refused edit changes nothing");
        List<DataStore.Payment> pay = store.addPayment("2", null, 300, "ana", "", "cash");
        store.replacing("payment", pay.get(0).id(), () -> store.addPayment("2", null, 250, "ana", "fixed", "gcash"));
        check(store.payments().size() == 1 && same(store.payments().get(0).amount(), 250) && store.payments().get(0).gcash(), "edited payment");

        expectConflict(() -> store.undo("payment", "999"), "missing record");
        check(store.undoTarget("nonsense", "1") == null, "unknown kind");
        System.out.println("UndoCheck passed: " + checked + " checks");
    }
}

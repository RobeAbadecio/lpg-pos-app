import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Predicate;

/**
 * CSV-backed storage shared with the desktop Swing app (same folder, same leading columns).
 *
 * Customers.csv       id,firstName,lastName,contactNo,address
 * LPGs.csv            id,brand,price,weight[,reorderLevel,tankPrice,refillCost,tankCost]
 * Transactions.csv    id,customerId,lpgId,dateTime[,qty,unitPrice,staff,returnedEmpty,receiptId,emptyLpgId,remark]
 *                     one row per receipt line
 * Receipts.csv        id,dateTime,customerId,staff,discount,paidAtSale,note
 * Payments.csv        id,dateTime,receiptId,customerId,amount,staff,note
 * StockMovements.csv  id,dateTime,lpgId,type,loadedDelta,emptyDelta,staff,note[,damagedDelta,refillerDelta,refillId,unitCost]
 * Suppliers.csv       id,name,contact,brands,note       brands separated by ";"
 * Refills.csv         id,dateTime,supplierId,staff,note  one row per trip to the refiller
 * SupplierPayments.csv id,dateTime,supplierId,refillId,kind,amount,staff,note   money paid to refillers/suppliers
 *
 * Older rows simply lack the trailing columns. A transaction row without a receipt id is a
 * one-line receipt "S<id>", fully paid; without an empty-tank column it took back an empty of
 * the same product unless returnedEmpty is "0"; a product without a tank price sells new
 * tanks at its refill price; a product without costs has unknown costs (0); a movement
 * without the trailing columns touches only the with-load and empty counts.
 *
 * Money paid out: receiving tanks back from the refiller records what each refill cost (the
 * unitCost on the receive movements) and what was paid; buying new tanks records a payment.
 *
 * Stock is never stored as a number. For each product, tanks with load, empty tanks and tanks
 * at the refiller are the sum of the stock movements plus the effect of every sale line, so
 * editing or voiding a receipt corrects the stock automatically. A tank's condition is only a
 * free-text remark; it doesn't move the tank anywhere. (The "damaged" count exists only for data
 * written by an earlier test version and is no longer added to.)
 *
 * Files are re-read whenever they change on disk, so edits made in the desktop app show up on
 * the web without a restart.
 */
final class DataStore {
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    record Customer(String id, String firstName, String lastName, String contact, String address) {
        String name() { return (firstName + " " + lastName).trim(); }
    }

    /**
     * price: refill price (customer swaps in an empty). tankPrice: price when they take a new tank home.
     * refillCost: what the refiller charges per tank. tankCost: what a new tank with load costs the store.
     */
    record Product(String id, String brand, double price, double weight, int reorderLevel, double tankPrice,
                   double refillCost, double tankCost) {
        String label() { return brand + " " + fmtKg(weight) + " kg"; }

        double priceFor(boolean withEmpty) { return withEmpty || tankPrice <= 0 ? price : tankPrice; }

        /** What one tank sold this way cost the store (0 when costs aren't set). */
        double costFor(boolean withEmpty) { return withEmpty || tankCost <= 0 ? refillCost : tankCost; }
    }

    /**
     * One item on a receipt. emptyProductId is "" when no empty tank came back (new tank sale).
     * remark: staff's note on the tank's condition (e.g. "dented"), may be empty.
     */
    record Line(String id, String receiptId, String customerId, String productId, String rawTime, LocalDateTime time,
                int qty, double unitPrice, String staff, String emptyProductId, String remark) {
        boolean returnedEmpty() { return !emptyProductId.isEmpty(); }

        double amount() { return qty * unitPrice; }
    }

    record Receipt(String id, String rawTime, LocalDateTime time, String customerId, String staff,
                   double discount, double paidAtSale, String note, List<Line> lines) {
        double subtotal() { return round(lines.stream().mapToDouble(Line::amount).sum()); }

        double total() { return round(Math.max(0, subtotal() - discount)); }

        int qty() { return lines.stream().mapToInt(Line::qty).sum(); }
    }

    record Payment(String id, String rawTime, LocalDateTime time, String receiptId, String customerId,
                   double amount, String staff, String note) {}

    /** type: count | damaged | other | delivery | purchase | condition | refill-send | refill-receive | refill-reject */
    record Movement(String id, String rawTime, LocalDateTime time, String productId, String type,
                    int loaded, int empty, int damaged, int atRefiller, String staff, String note, String refillId,
                    double unitCost) {}

    /** kind: refill | purchase */
    record SupplierPayment(String id, String rawTime, LocalDateTime time, String supplierId, String refillId,
                           String kind, double amount, String staff, String note) {}

    record Stock(int loaded, int empty, int damaged, int atRefiller) {
        static final Stock NONE = new Stock(0, 0, 0, 0);
    }

    record Supplier(String id, String name, String contact, List<String> brands, String note) {
        boolean supplies(String brand) {
            return brands.stream().anyMatch(b -> b.equalsIgnoreCase(brand.trim()));
        }
    }

    record Refill(String id, String rawTime, LocalDateTime time, String supplierId, String staff, String note) {}

    /** Per product on one refill trip: tanks sent, back with load, and returned unfilled. */
    record RefillItem(String productId, int sent, int received, int rejected) {
        int outstanding() { return sent - received - rejected; }
    }

    record LineInput(Product product, int qty, double unitPrice, String emptyProductId, String remark) {}

    record User(String username, String salt, String hash, String created) {}

    /** A business rule stopped the change (not enough stock, overpayment...). Shown to staff as-is. */
    static final class Conflict extends RuntimeException {
        Conflict(String message) {
            super(message);
        }
    }

    private final Path dir;
    private final Object lock = new Object();
    private final Table customers, products, lines, receipts, payments, movements, suppliers, refills, supplierPayments, users;
    private final List<Table> all;

    DataStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        customers = new Table("Customers.csv");
        products = new Table("LPGs.csv");
        lines = new Table("Transactions.csv");
        receipts = new Table("Receipts.csv");
        payments = new Table("Payments.csv");
        movements = new Table("StockMovements.csv");
        suppliers = new Table("Suppliers.csv");
        refills = new Table("Refills.csv");
        supplierPayments = new Table("SupplierPayments.csv");
        users = new Table("WebUsers.csv");
        all = List.of(customers, products, lines, receipts, payments, movements, suppliers, refills, supplierPayments);
    }

    Path dir() { return dir; }

    /** Changes whenever any business data file changes; used as an ETag so idle phones download nothing. */
    String version() {
        StringBuilder sb = new StringBuilder();
        for (Table t : all) sb.append(t.stamp()).append('/');
        return Integer.toHexString(sb.toString().hashCode());
    }

    // ---------------------------------------------------------------- customers

    List<Customer> customers() {
        synchronized (lock) {
            List<Customer> out = new ArrayList<>();
            for (String[] r : customers.rows()) out.add(new Customer(col(r, 0), col(r, 1), col(r, 2), col(r, 3), col(r, 4)));
            return out;
        }
    }

    Customer customer(String id) {
        return customers().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    Customer addCustomer(String first, String last, String contact, String address) {
        synchronized (lock) {
            List<String[]> rows = customers.copy();
            String id = nextId(rows);
            rows.add(new String[]{id, first, last, contact, address});
            customers.save(rows);
            return new Customer(id, first, last, contact, address);
        }
    }

    boolean updateCustomer(String id, String first, String last, String contact, String address) {
        synchronized (lock) {
            return customers.replace(id, new String[]{id, first, last, contact, address});
        }
    }

    /** Returns the number of receipts blocking deletion, 0 when deleted, -1 when not found. */
    int deleteCustomer(String id) {
        synchronized (lock) {
            long used = receipts().stream().filter(r -> r.customerId().equals(id)).count();
            if (used > 0) return (int) used;
            return customers.remove(id) ? 0 : -1;
        }
    }

    // ---------------------------------------------------------------- products

    List<Product> products() {
        synchronized (lock) {
            List<Product> out = new ArrayList<>();
            for (String[] r : products.rows()) {
                out.add(new Product(col(r, 0), col(r, 1), num(col(r, 2), 0), num(col(r, 3), 0),
                        (int) num(col(r, 4), 5), num(col(r, 5), 0), num(col(r, 6), 0), num(col(r, 7), 0)));
            }
            return out;
        }
    }

    Map<String, Product> productMap() {
        Map<String, Product> m = new LinkedHashMap<>();
        for (Product p : products()) m.putIfAbsent(p.id(), p);
        return m;
    }

    Product product(String id) {
        return productMap().get(id);
    }

    Product addProduct(Product p) {
        synchronized (lock) {
            List<String[]> rows = products.copy();
            Product saved = new Product(nextId(rows), p.brand(), p.price(), p.weight(), p.reorderLevel(), p.tankPrice(), p.refillCost(), p.tankCost());
            rows.add(productRow(saved));
            products.save(rows);
            return saved;
        }
    }

    boolean updateProduct(Product p) {
        synchronized (lock) {
            return products.replace(p.id(), productRow(p));
        }
    }

    private static String[] productRow(Product p) {
        return new String[]{p.id(), p.brand(), money(p.price()), fmtKg(p.weight()), String.valueOf(p.reorderLevel()),
                money(p.tankPrice()), money(p.refillCost()), money(p.tankCost())};
    }

    /**
     * Products on any receipt (sold, or taken back as an empty) or with tanks at the refiller
     * can't be deleted; the reason is returned, or null once deleted. A deleted product's stock
     * movements go with it, so a product added later under the same id doesn't inherit them.
     */
    String deleteProduct(String id) {
        synchronized (lock) {
            if (product(id) == null) return "Product not found";
            long used = lines().stream().filter(l -> l.productId().equals(id) || l.emptyProductId().equals(id)).count();
            if (used > 0) return "This product is on " + used + " receipt line(s), so it can't be deleted.";
            if (stock().getOrDefault(id, Stock.NONE).atRefiller() > 0) return "Some of these tanks are still at the refiller.";
            if (movements().stream().anyMatch(m -> m.productId().equals(id) && (!m.refillId().isEmpty() || m.unitCost() > 0))) {
                return "This product has refill or purchase payments on record, so it can't be deleted.";
            }
            products.remove(id);
            movements.removeWhere(r -> col(r, 2).equals(id));
            return null;
        }
    }

    // ---------------------------------------------------------------- receipts

    private static String receiptIdOf(String[] r) {
        return col(r, 8).isEmpty() ? "S" + col(r, 0) : col(r, 8);
    }

    List<Line> lines() {
        synchronized (lock) {
            Map<String, Product> prods = productMap();
            List<Line> out = new ArrayList<>();
            for (String[] r : lines.rows()) {
                if (r.length < 4) continue; // same guard as TransactionPanel
                String pid = col(r, 2);
                int qty = (int) Math.max(1, num(col(r, 4), 1));
                double unit = col(r, 5).isEmpty()
                        ? Optional.ofNullable(prods.get(pid)).map(Product::price).orElse(0.0)
                        : num(col(r, 5), 0);
                String emptyId = r.length > 9 ? col(r, 9) : ("0".equals(col(r, 7)) ? "" : pid);
                // An earlier test version stored good/damaged here; anything else is the remark itself.
                String remark = switch (col(r, 10)) {
                    case "good" -> "";
                    case "damaged" -> "Damaged";
                    default -> col(r, 10);
                };
                out.add(new Line(col(r, 0), receiptIdOf(r), col(r, 1), pid, col(r, 3), parseTime(col(r, 3)),
                        qty, unit, col(r, 6), emptyId, remark));
            }
            return out;
        }
    }

    /** All receipts, oldest first. */
    List<Receipt> receipts() {
        synchronized (lock) {
            Map<String, List<Line>> grouped = new LinkedHashMap<>();
            for (Line l : lines()) grouped.computeIfAbsent(l.receiptId(), k -> new ArrayList<>()).add(l);
            Map<String, String[]> meta = new HashMap<>();
            for (String[] r : receipts.rows()) meta.put(col(r, 0), r);
            List<Receipt> out = new ArrayList<>();
            grouped.forEach((id, ls) -> {
                String[] m = meta.get(id);
                Line first = ls.get(0);
                if (m == null) {
                    // Older single-line sale: paid in full at the time.
                    double total = round(ls.stream().mapToDouble(Line::amount).sum());
                    out.add(new Receipt(id, first.rawTime(), first.time(), first.customerId(), first.staff(), 0, total, "", ls));
                } else {
                    out.add(new Receipt(id, col(m, 1), parseTime(col(m, 1)), col(m, 2), col(m, 3),
                            num(col(m, 4), 0), num(col(m, 5), 0), col(m, 6), ls));
                }
            });
            out.sort(Comparator.comparing((Receipt r) -> r.time() == null ? LocalDateTime.MIN : r.time())
                    .thenComparingInt(r -> numericId(r.id())));
            return out;
        }
    }

    Receipt receipt(String id) {
        return receipts().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    }

    List<Payment> payments() {
        synchronized (lock) {
            List<Payment> out = new ArrayList<>();
            for (String[] r : payments.rows()) {
                out.add(new Payment(col(r, 0), col(r, 1), parseTime(col(r, 1)), col(r, 2), col(r, 3),
                        num(col(r, 4), 0), col(r, 5), col(r, 6)));
            }
            return out;
        }
    }

    /** Money received per receipt id: paid at the sale plus later payments. */
    Map<String, Double> paidByReceipt(List<Receipt> rs) {
        Map<String, Double> paid = new HashMap<>();
        for (Receipt r : rs) paid.put(r.id(), r.paidAtSale());
        for (Payment p : payments()) paid.merge(p.receiptId(), p.amount(), Double::sum);
        return paid;
    }

    static double balance(Receipt r, Map<String, Double> paid) {
        return round(Math.max(0, r.total() - paid.getOrDefault(r.id(), 0.0)));
    }

    Receipt createReceipt(String customerId, String staff, List<LineInput> items, double discount, double paidNow, String note) {
        synchronized (lock) {
            checkStock(items, Map.of());
            double subtotal = round(items.stream().mapToDouble(i -> i.qty() * i.unitPrice()).sum());
            checkMoney(subtotal, discount, paidNow, 0);
            List<String[]> rrows = receipts.copy();
            String id = nextId(rrows);
            String time = now();
            rrows.add(new String[]{id, time, customerId, staff, money(discount), money(paidNow), note});
            receipts.save(rrows);
            writeLines(id, customerId, time, staff, items);
            return receipt(id);
        }
    }

    /** Replaces a receipt's items, keeping its number, time and seller. Returns null if not found. */
    Receipt updateReceipt(String id, String customerId, List<LineInput> items, double discount, double paidAtSale, String note) {
        synchronized (lock) {
            Receipt old = receipt(id);
            if (old == null) return null;
            Map<String, Integer> own = new HashMap<>();
            for (Line l : old.lines()) own.merge(l.productId(), l.qty(), Integer::sum);
            checkStock(items, own);
            double subtotal = round(items.stream().mapToDouble(i -> i.qty() * i.unitPrice()).sum());
            double later = payments().stream().filter(p -> p.receiptId().equals(id)).mapToDouble(Payment::amount).sum();
            checkMoney(subtotal, discount, paidAtSale, later);
            String[] row = {id, old.rawTime(), customerId, old.staff(), money(discount), money(paidAtSale), note};
            if (!receipts.replace(id, row)) {
                List<String[]> rrows = receipts.copy();
                rrows.add(row);
                receipts.save(rrows);
            }
            lines.removeWhere(r -> receiptIdOf(r).equals(id));
            writeLines(id, customerId, old.rawTime(), old.staff(), items);
            payments.saveIf(r -> col(r, 2).equals(id), r -> {
                r[3] = customerId;
                return r;
            });
            return receipt(id);
        }
    }

    /** Deletes a receipt with its items and payments; its stock goes back. Returns it, or null. */
    Receipt voidReceipt(String id) {
        synchronized (lock) {
            Receipt r = receipt(id);
            if (r == null) return null;
            lines.removeWhere(row -> receiptIdOf(row).equals(id));
            receipts.remove(id);
            payments.removeWhere(row -> col(row, 2).equals(id));
            return r;
        }
    }

    private void writeLines(String receiptId, String customerId, String time, String staff, List<LineInput> items) {
        List<String[]> rows = lines.copy();
        int next = Integer.parseInt(nextId(rows));
        for (LineInput i : items) {
            rows.add(new String[]{String.valueOf(next++), customerId, i.product().id(), time, String.valueOf(i.qty()),
                    money(i.unitPrice()), staff == null ? "" : staff, i.emptyProductId().isEmpty() ? "0" : "1",
                    receiptId, i.emptyProductId(), i.remark()});
        }
        lines.save(rows);
    }

    /** Every product on the receipt must have that many tanks with load (plus what this receipt already holds). */
    private void checkStock(List<LineInput> items, Map<String, Integer> alreadyHeld) {
        Map<String, Integer> need = new LinkedHashMap<>();
        Map<String, Product> byId = new HashMap<>();
        for (LineInput i : items) {
            need.merge(i.product().id(), i.qty(), Integer::sum);
            byId.put(i.product().id(), i.product());
        }
        Map<String, Stock> stock = stock();
        for (var e : need.entrySet()) {
            int available = stock.getOrDefault(e.getKey(), Stock.NONE).loaded() + alreadyHeld.getOrDefault(e.getKey(), 0);
            if (e.getValue() > available) {
                Product p = byId.get(e.getKey());
                throw new Conflict(available <= 0
                        ? p.label() + " has no tanks with load. Receive from the refiller or buy stock first."
                        : "Only " + available + " × " + p.label() + " with load in stock.");
            }
        }
    }

    private static void checkMoney(double subtotal, double discount, double paidAtSale, double paidLater) {
        if (discount > subtotal + 0.005) throw new Conflict("The discount is more than the receipt subtotal.");
        double total = round(Math.max(0, subtotal - discount));
        if (paidAtSale > total + 0.005) throw new Conflict("Amount paid is more than the total (₱" + money(total) + ").");
        if (paidAtSale + paidLater > total + 0.005) {
            throw new Conflict("Payments already recorded (₱" + money(paidLater) + ") plus the amount paid at sale exceed the new total.");
        }
    }

    /**
     * Records money received from a customer. With a receipt id it goes to that receipt;
     * otherwise it pays off the customer's oldest unpaid receipts first.
     */
    List<Payment> addPayment(String customerId, String receiptId, double amount, String staff, String note) {
        synchronized (lock) {
            List<Receipt> rs = receipts();
            Map<String, Double> paid = paidByReceipt(rs);
            List<Receipt> owing = rs.stream()
                    .filter(r -> receiptId == null ? r.customerId().equals(customerId) : r.id().equals(receiptId))
                    .filter(r -> balance(r, paid) > 0).toList();
            double owed = round(owing.stream().mapToDouble(r -> balance(r, paid)).sum());
            if (owed <= 0) throw new Conflict("Nothing is owed" + (receiptId == null ? " by this customer." : " on this receipt."));
            if (amount > owed + 0.005) throw new Conflict("That's more than the ₱" + money(owed) + " owed.");
            List<String[]> rows = payments.copy();
            int next = Integer.parseInt(nextId(rows));
            String time = now();
            double left = round(amount);
            List<Payment> out = new ArrayList<>();
            for (Receipt r : owing) {
                if (left <= 0) break;
                double part = round(Math.min(left, balance(r, paid)));
                String id = String.valueOf(next++);
                rows.add(new String[]{id, time, r.id(), r.customerId(), money(part), staff, note});
                out.add(new Payment(id, time, parseTime(time), r.id(), r.customerId(), part, staff, note));
                left = round(left - part);
            }
            payments.save(rows);
            return out;
        }
    }

    // ---------------------------------------------------------------- inventory

    List<Movement> movements() {
        synchronized (lock) {
            List<Movement> out = new ArrayList<>();
            for (String[] r : movements.rows()) {
                out.add(new Movement(col(r, 0), col(r, 1), parseTime(col(r, 1)), col(r, 2), col(r, 3),
                        (int) num(col(r, 4), 0), (int) num(col(r, 5), 0), (int) num(col(r, 8), 0), (int) num(col(r, 9), 0),
                        col(r, 6), col(r, 7), col(r, 10), num(col(r, 11), 0)));
            }
            return out;
        }
    }

    /** Tanks per product id: with load, empty (good), damaged, at the refiller. */
    Map<String, Stock> stock() {
        synchronized (lock) {
            Map<String, int[]> s = new HashMap<>();
            for (Movement m : movements()) {
                int[] v = s.computeIfAbsent(m.productId(), k -> new int[4]);
                v[0] += m.loaded();
                v[1] += m.empty();
                v[2] += m.damaged();
                v[3] += m.atRefiller();
            }
            for (Line l : lines()) {
                s.computeIfAbsent(l.productId(), k -> new int[4])[0] -= l.qty();
                if (l.returnedEmpty()) s.computeIfAbsent(l.emptyProductId(), k -> new int[4])[1] += l.qty();
            }
            Map<String, Stock> out = new HashMap<>();
            s.forEach((id, v) -> out.put(id, new Stock(v[0], v[1], v[2], v[3])));
            return out;
        }
    }

    Movement addMovement(Product p, String type, int loaded, int empty, int damaged, int atRefiller,
                         String staff, String note, String refillId) {
        return addMovement(p, type, loaded, empty, damaged, atRefiller, staff, note, refillId, 0);
    }

    /** Records a stock change; refuses one that would leave any count below zero. */
    Movement addMovement(Product p, String type, int loaded, int empty, int damaged, int atRefiller,
                         String staff, String note, String refillId, double unitCost) {
        synchronized (lock) {
            Stock now = stock().getOrDefault(p.id(), Stock.NONE);
            if (now.loaded() + loaded < 0) throw new Conflict("Only " + now.loaded() + " × " + p.label() + " with load on hand.");
            if (now.empty() + empty < 0) throw new Conflict("Only " + now.empty() + " good empty " + p.label() + " tanks on hand.");
            if (now.damaged() + damaged < 0) throw new Conflict("Only " + now.damaged() + " damaged " + p.label() + " tanks on hand.");
            if (now.atRefiller() + atRefiller < 0) throw new Conflict("Only " + now.atRefiller() + " × " + p.label() + " at the refiller.");
            List<String[]> rows = movements.copy();
            String id = nextId(rows);
            String time = now();
            rows.add(new String[]{id, time, p.id(), type, String.valueOf(loaded), String.valueOf(empty),
                    staff == null ? "" : staff, note == null ? "" : note,
                    String.valueOf(damaged), String.valueOf(atRefiller), refillId == null ? "" : refillId, money(unitCost)});
            movements.save(rows);
            return new Movement(id, time, parseTime(time), p.id(), type, loaded, empty, damaged, atRefiller, staff, note, refillId, unitCost);
        }
    }

    /**
     * Sets the store's counts to what was physically counted (tanks at the refiller aren't in
     * the store, so they're left alone). Read and write happen under one lock so a sale landing
     * at the same moment can't skew the difference. Returns the counts before, or null if unchanged.
     */
    Stock adjustTo(Product p, String type, int loaded, int empty, Integer damaged, String staff, String note) {
        synchronized (lock) {
            Stock before = stock().getOrDefault(p.id(), Stock.NONE);
            if (damaged == null) damaged = before.damaged();
            if (before.loaded() == loaded && before.empty() == empty && before.damaged() == damaged) return null;
            addMovement(p, type, loaded - before.loaded(), empty - before.empty(), damaged - before.damaged(), 0, staff, note, null);
            return before;
        }
    }

    // ---------------------------------------------------------------- suppliers and refills

    List<Supplier> suppliers() {
        synchronized (lock) {
            List<Supplier> out = new ArrayList<>();
            for (String[] r : suppliers.rows()) {
                List<String> brands = Arrays.stream(col(r, 3).split(";")).map(String::trim).filter(b -> !b.isEmpty()).toList();
                out.add(new Supplier(col(r, 0), col(r, 1), col(r, 2), brands, col(r, 4)));
            }
            return out;
        }
    }

    Supplier supplier(String id) {
        return suppliers().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    Supplier supplierFor(String brand) {
        return suppliers().stream().filter(s -> s.supplies(brand)).findFirst().orElse(null);
    }

    Supplier saveSupplier(String id, String name, String contact, List<String> brands, String note) {
        synchronized (lock) {
            String[] row = {id, name, contact, String.join("; ", brands), note};
            if (id == null) {
                List<String[]> rows = suppliers.copy();
                row[0] = nextId(rows);
                rows.add(row);
                suppliers.save(rows);
            } else if (!suppliers.replace(id, row)) {
                return null;
            }
            return supplier(row[0]);
        }
    }

    /** Returns why the supplier can't be deleted, or null once deleted. */
    String deleteSupplier(String id) {
        synchronized (lock) {
            if (supplier(id) == null) return "Supplier not found";
            boolean open = refills().stream().anyMatch(r -> r.supplierId().equals(id)
                    && refillItems(r.id()).stream().anyMatch(i -> i.outstanding() > 0));
            if (open) return "Tanks are still at this refiller. Receive them first.";
            suppliers.remove(id);
            return null;
        }
    }

    List<Refill> refills() {
        synchronized (lock) {
            List<Refill> out = new ArrayList<>();
            for (String[] r : refills.rows()) {
                out.add(new Refill(col(r, 0), col(r, 1), parseTime(col(r, 1)), col(r, 2), col(r, 3), col(r, 4)));
            }
            return out;
        }
    }

    List<RefillItem> refillItems(String refillId) {
        Map<String, int[]> m = new LinkedHashMap<>();
        for (Movement mv : movements()) {
            if (!refillId.equals(mv.refillId())) continue;
            int[] v = m.computeIfAbsent(mv.productId(), k -> new int[3]);
            switch (mv.type()) {
                case "refill-send" -> v[0] += mv.atRefiller();
                case "refill-receive" -> v[1] += mv.loaded();
                case "refill-reject" -> v[2] += mv.empty() + mv.damaged(); // older rows sent them to damaged
                default -> {
                }
            }
        }
        List<RefillItem> out = new ArrayList<>();
        m.forEach((pid, v) -> out.add(new RefillItem(pid, v[0], v[1], v[2])));
        return out;
    }

    /** Sends good empty tanks to a refiller: they leave the store and wait as "at refiller". */
    Refill sendToRefiller(Supplier s, Map<Product, Integer> items, String staff, String note) {
        synchronized (lock) {
            Map<String, Stock> stock = stock();
            for (var e : items.entrySet()) {
                int empty = stock.getOrDefault(e.getKey().id(), Stock.NONE).empty();
                if (e.getValue() > empty) throw new Conflict("Only " + empty + " good empty " + e.getKey().label() + " tanks to send.");
            }
            List<String[]> rows = refills.copy();
            String id = nextId(rows);
            rows.add(new String[]{id, now(), s.id(), staff, note});
            refills.save(rows);
            for (var e : items.entrySet()) {
                addMovement(e.getKey(), "refill-send", 0, -e.getValue(), 0, e.getValue(), staff, note, id);
            }
            return refills().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
        }
    }

    /**
     * Tanks back from the refiller: received ones come back with load (at costs[product] each),
     * unfilled ones come back as empty tanks, free. paidNow is what was handed to the refiller.
     */
    void receiveFromRefiller(Refill refill, Map<Product, int[]> items, Map<Product, Double> costs, double paidNow,
                             String staff, String note) {
        synchronized (lock) {
            Map<String, RefillItem> open = new HashMap<>();
            for (RefillItem i : refillItems(refill.id())) open.put(i.productId(), i);
            double added = 0;
            for (var e : items.entrySet()) {
                RefillItem i = open.get(e.getKey().id());
                int back = e.getValue()[0] + e.getValue()[1];
                int waiting = i == null ? 0 : i.outstanding();
                if (back > waiting) throw new Conflict("Only " + waiting + " × " + e.getKey().label() + " are still at the refiller on this trip.");
                added += e.getValue()[0] * costs.getOrDefault(e.getKey(), 0.0);
            }
            double owed = round(refillCost(refill.id()) + added - refillPaid(refill.id()));
            if (paidNow > owed + 0.005) throw new Conflict("That's more than the ₱" + money(Math.max(0, owed)) + " this trip costs.");
            for (var e : items.entrySet()) {
                int got = e.getValue()[0], rejected = e.getValue()[1];
                if (got > 0) addMovement(e.getKey(), "refill-receive", got, 0, 0, -got, staff, note, refill.id(), costs.getOrDefault(e.getKey(), 0.0));
                if (rejected > 0) addMovement(e.getKey(), "refill-reject", 0, rejected, 0, -rejected, staff, note, refill.id());
            }
            if (paidNow > 0) addSupplierPayment(refill.supplierId(), refill.id(), "refill", paidNow, staff, note);
        }
    }

    /** What the tanks received on a trip cost (refill cost × tanks back with load). */
    double refillCost(String refillId) {
        double cost = 0;
        for (Movement m : movements()) {
            if (refillId.equals(m.refillId()) && "refill-receive".equals(m.type())) cost += m.loaded() * m.unitCost();
        }
        return round(cost);
    }

    double refillPaid(String refillId) {
        return round(supplierPayments().stream().filter(p -> refillId.equals(p.refillId())).mapToDouble(SupplierPayment::amount).sum());
    }

    /** Pays (part of) what a refill trip still owes. */
    SupplierPayment payRefiller(Refill refill, double amount, String staff, String note) {
        synchronized (lock) {
            double owed = round(refillCost(refill.id()) - refillPaid(refill.id()));
            if (owed <= 0) throw new Conflict("Nothing is owed on this trip.");
            if (amount > owed + 0.005) throw new Conflict("That's more than the ₱" + money(owed) + " still owed on this trip.");
            return addSupplierPayment(refill.supplierId(), refill.id(), "refill", amount, staff, note);
        }
    }

    /** New tanks with load bought from a supplier, paid on the spot. */
    Movement purchase(Product p, int qty, double unitCost, String staff, String note) {
        synchronized (lock) {
            Movement m = addMovement(p, "purchase", qty, 0, 0, 0, staff, note, null, unitCost);
            Supplier s = supplierFor(p.brand());
            if (unitCost > 0) addSupplierPayment(s == null ? "" : s.id(), "", "purchase", round(qty * unitCost), staff, note);
            return m;
        }
    }

    List<SupplierPayment> supplierPayments() {
        synchronized (lock) {
            List<SupplierPayment> out = new ArrayList<>();
            for (String[] r : supplierPayments.rows()) {
                out.add(new SupplierPayment(col(r, 0), col(r, 1), parseTime(col(r, 1)), col(r, 2), col(r, 3), col(r, 4),
                        num(col(r, 5), 0), col(r, 6), col(r, 7)));
            }
            return out;
        }
    }

    private SupplierPayment addSupplierPayment(String supplierId, String refillId, String kind, double amount, String staff, String note) {
        List<String[]> rows = supplierPayments.copy();
        String id = nextId(rows);
        String time = now();
        rows.add(new String[]{id, time, supplierId, refillId, kind, money(amount), staff, note == null ? "" : note});
        supplierPayments.save(rows);
        return new SupplierPayment(id, time, parseTime(time), supplierId, refillId, kind, amount, staff, note);
    }

    // ---------------------------------------------------------------- web users

    List<User> users() {
        synchronized (lock) {
            List<User> out = new ArrayList<>();
            for (String[] r : users.rows()) out.add(new User(col(r, 0), col(r, 1), col(r, 2), col(r, 3)));
            return out;
        }
    }

    User user(String username) {
        return users().stream().filter(u -> u.username().equals(username)).findFirst().orElse(null);
    }

    boolean addUser(String username, String salt, String hash) {
        synchronized (lock) {
            if (user(username) != null) return false;
            List<String[]> rows = users.copy();
            rows.add(new String[]{username, salt, hash, now()});
            users.save(rows);
            return true;
        }
    }

    boolean setPassword(String username, String salt, String hash) {
        synchronized (lock) {
            User u = user(username);
            return u != null && users.replace(username, new String[]{username, salt, hash, u.created()});
        }
    }

    boolean deleteUser(String username) {
        synchronized (lock) {
            return users.remove(username);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String col(String[] r, int i) {
        return i < r.length && r[i] != null ? r[i].trim() : "";
    }

    private static double num(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static LocalDateTime parseTime(String s) {
        try {
            return LocalDateTime.parse(s, TIME);
        } catch (DateTimeParseException e) {
            return null; // keep the row; it just won't land in a dated bucket
        }
    }

    private static String now() {
        return LocalDateTime.now().withNano(0).format(TIME);
    }

    static int numericId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nextId(List<String[]> rows) {
        int max = 0;
        for (String[] r : rows) max = Math.max(max, numericId(col(r, 0)));
        return String.valueOf(max + 1);
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static String money(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static String fmtKg(double v) {
        return v == Math.rint(v) ? String.format(Locale.ROOT, "%.1f", v) : String.valueOf(v);
    }

    /** One CSV file, cached in memory and reloaded when its mtime or size changes. */
    private final class Table {
        private final Path path;
        private List<String[]> rows = new ArrayList<>();
        private FileTime mtime;
        private long size = -1;

        Table(String name) {
            this.path = dir.resolve(name);
        }

        String stamp() {
            try {
                return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() + ":" + Files.size(path) : "-";
            } catch (IOException e) {
                return "?";
            }
        }

        List<String[]> rows() {
            try {
                if (!Files.exists(path)) {
                    rows = new ArrayList<>();
                    mtime = null;
                    size = -1;
                    return rows;
                }
                FileTime t = Files.getLastModifiedTime(path);
                long s = Files.size(path);
                if (!t.equals(mtime) || s != size) {
                    rows = Csv.parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                    mtime = t;
                    size = s;
                }
                return rows;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        List<String[]> copy() {
            return new ArrayList<>(rows());
        }

        boolean replace(String id, String[] row) {
            List<String[]> next = copy();
            for (int i = 0; i < next.size(); i++) {
                if (col(next.get(i), 0).equals(id)) {
                    next.set(i, row);
                    save(next);
                    return true;
                }
            }
            return false;
        }

        boolean remove(String id) {
            return removeWhere(r -> col(r, 0).equals(id));
        }

        boolean removeWhere(Predicate<String[]> match) {
            List<String[]> next = copy();
            boolean removed = next.removeIf(match);
            if (removed) save(next);
            return removed;
        }

        /** Rewrites matching rows (on copies) and saves if any matched. */
        void saveIf(Predicate<String[]> match, java.util.function.UnaryOperator<String[]> change) {
            List<String[]> next = copy();
            boolean any = false;
            for (int i = 0; i < next.size(); i++) {
                if (match.test(next.get(i))) {
                    next.set(i, change.apply(Arrays.copyOf(next.get(i), Math.max(7, next.get(i).length))));
                    any = true;
                }
            }
            if (any) save(next);
        }

        void save(List<String[]> next) {
            try {
                Path tmp = dir.resolve("." + path.getFileName() + ".web.tmp");
                Files.writeString(tmp, Csv.format(next), StandardCharsets.UTF_8);
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                rows = next;
                mtime = Files.getLastModifiedTime(path);
                size = Files.size(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * CSV-backed storage shared with the desktop Swing app (same folder, same leading columns).
 *
 * Customers.csv       id,firstName,lastName,contactNo,address
 * LPGs.csv            id,brand,price,weight[,reorderLevel,tankPrice,refillCost,tankCost,swapFee]
 * Transactions.csv    id,customerId,lpgId,dateTime[,qty,unitPrice,staff,returnedEmpty,receiptId,emptyLpgId,remark,owesTank,swapFee,
 *                     emptyKind,emptyBrand]   one row per receipt line; owesTank "1": no empty came in, the customer will
 *                     bring one back; emptyKind "generic" (no brand) or "other" (another brand, named in emptyBrand, that the
 *                     store doesn't sell): the empty is the sold tank's size but no product's stock (emptyLpgId = lpgId)
 * Receipts.csv        id,dateTime,customerId,staff,discount,paidAtSale,note[,payMethod]   payMethod: how paidAtSale came in,
 *                     "cash" (the default) or "gcash"
 * Payments.csv        id,dateTime,receiptId,customerId,amount,staff,note[,method]          method "cash" (default) or "gcash"
 * StockMovements.csv  id,dateTime,lpgId,type,loadedDelta,emptyDelta,staff,note[,damagedDelta,refillerDelta,refillId,unitCost]
 * Suppliers.csv       id,name,contact,brand,note        each supplier refills only its own brand
 *                                                        (rows from earlier test builds may list several, separated by ";")
 * Refills.csv         id,dateTime,supplierId,staff,note[,start]  one row per trip to the refiller; start "1": the
 *                     tanks already at that refiller on the start date (sent per StartingBalances.csv)
 * SupplierPayments.csv id,dateTime,supplierId,refillId,kind,amount,staff,note[,source,paidBy,method]   money paid to
 *                     refillers/suppliers; refillId is the trip id, or "P" + the purchase's movement id for new
 *                     tanks bought; source "sales" (the store's money, the default), "owner" (the owner's personal
 *                     money) or "staff" (paidBy's own money); method "cash" (the default), "check" or "online"
 * StaffPaybacks.csv   id,dateTime,staff,amount,recordedBy,note   the store paying staff back for money they put in
 * TankReturns.csv     id,dateTime,customerId,lpgId,qty,staff,remark[,emptyKind,emptyBrand]   empties brought back by
 *                     customers who owed them; with emptyKind, a generic / other-brand empty of lpgId's size
 * Expenses.csv        id,dateTime,category,amount,employee,method,recordedBy,note,advanceDeducted   money the store
 *                     spent besides suppliers: category gasoline (delivery), salary, advance (salary paid ahead,
 *                     taken off a later salary as advanceDeducted), repaid (an advance the employee paid back in cash:
 *                     money in) or other; method "cash" (default), "online" or "check"
 * Employees.csv       id,name,salary,schedule,day1,day2,created,note   staff on the payroll (matched to Expenses by name):
 *                     schedule weekly (day1 1-7 = Mon-Sun), twice (day1, day2 = days of the month) or monthly (day1)
 * WebUsers.csv        username,salt,hash,created[,dashboard]             dashboard "1": may see the summary dashboard
 * StartingBalances.csv id,dateTime,kind,ref,lpgId,qty,amount,trip,staff,note   the business as it stood on the start
 *                     date, all dated that day at 00:00. kind: start (one row; note = when it was saved), cash (amount),
 *                     loaded / empty (lpgId, qty), refiller (ref supplier, lpgId, qty, trip = its Refills id),
 *                     customer-money (ref customer, amount), customer-tanks (ref customer, lpgId, qty: our tanks they
 *                     have), deposit (ref customer, lpgId, qty: their own tanks kept at the store), supplier (ref
 *                     supplier, amount owed), advance (ref employee, amount). Customers pay their starting balance
 *                     as receipt "O" + customer id, suppliers are paid on bill "O" + supplier id.
 * TankDeposits.csv    id,dateTime,customerId,lpgId,qty,kind,staff,note,receiptId   customers' own tanks kept at the
 *                     store (not the store's stock): kind in (left with us), back (given back), used (handed over
 *                     as the empty on receiptId)
 *
 * Older rows simply lack the trailing columns. A transaction row without a receipt id is a
 * one-line receipt "S<id>", fully paid; without an empty-tank column it took back an empty of
 * the same product unless returnedEmpty is "0" (a new tank sold before 3.0, which nobody owes
 * back); a product without costs has unknown costs (0); a movement without the trailing
 * columns touches only the with-load and empty counts. tankPrice is no longer used.
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

    /**
     * The day new records are dated, when they're entered later (set per request by the API, cleared after).
     * Unset, records are dated now. A past day keeps the current time of day.
     */
    private static final ThreadLocal<LocalDate> RECORD_DATE = new ThreadLocal<>();

    static void recordOn(LocalDate day) { RECORD_DATE.set(day); }

    static void recordNow() { RECORD_DATE.remove(); }

    record Customer(String id, String firstName, String lastName, String contact, String address) {
        String name() { return (firstName + " " + lastName).trim(); }
    }

    /**
     * price: refill price. refillCost: what the refiller charges per tank. tankCost: what a new tank
     * with load costs the store. swapFee: extra charged per tank when the customer's empty is
     * another brand. tankPrice (selling new tanks) is no longer used; it's kept so old rows round-trip.
     */
    record Product(String id, String brand, double price, double weight, int reorderLevel, double tankPrice,
                   double refillCost, double tankCost, double swapFee) {
        String label() { return brand + " " + fmtKg(weight) + " kg"; }

        /** What one tank sold this way cost the store (0 when costs aren't set). */
        double costFor(boolean newTank) { return newTank && tankCost > 0 ? tankCost : refillCost; }
    }

    /**
     * One item on a receipt. emptyProductId is the empty that came in, or "" when none did: then
     * owesTank says the customer will bring one back (otherwise it's a new tank sold before 3.0).
     * swapFee: extra per tank because the empty was another brand. remark: staff's note, may be empty.
     */
    record Line(String id, String receiptId, String customerId, String productId, String rawTime, LocalDateTime time,
                int qty, double unitPrice, String staff, String emptyProductId, String remark, boolean owesTank, double swapFee,
                String emptyKind, String emptyBrand) {
        Line(String id, String receiptId, String customerId, String productId, String rawTime, LocalDateTime time,
             int qty, double unitPrice, String staff, String emptyProductId, String remark, boolean owesTank, double swapFee) {
            this(id, receiptId, customerId, productId, rawTime, time, qty, unitPrice, staff, emptyProductId, remark, owesTank, swapFee, "", "");
        }

        boolean returnedEmpty() { return !emptyProductId.isEmpty(); }

        /** A generic or other-brand empty: the sold tank's size, but no product's stock. */
        boolean looseEmpty() { return !emptyKind.isEmpty() && returnedEmpty(); }

        boolean newTank() { return emptyProductId.isEmpty() && !owesTank; }

        double amount() { return qty * (unitPrice + swapFee); }
    }

    /** payMethod: how the money paid at the sale came in, "cash" or "gcash". */
    record Receipt(String id, String rawTime, LocalDateTime time, String customerId, String staff,
                   double discount, double paidAtSale, String note, List<Line> lines, String payMethod) {
        boolean gcash() { return "gcash".equals(payMethod); }

        double subtotal() { return round(lines.stream().mapToDouble(Line::amount).sum()); }

        double total() { return round(Math.max(0, subtotal() - discount)); }

        int qty() { return lines.stream().mapToInt(Line::qty).sum(); }
    }

    /** method: "cash" or "gcash". */
    record Payment(String id, String rawTime, LocalDateTime time, String receiptId, String customerId,
                   double amount, String staff, String note, String method) {
        boolean gcash() { return "gcash".equals(method); }
    }

    static String customerMethod(String v) { return "gcash".equals(v) ? "gcash" : "cash"; }

    /** type: count | damaged | other | delivery | purchase | condition | refill-send | refill-receive | refill-reject */
    record Movement(String id, String rawTime, LocalDateTime time, String productId, String type,
                    int loaded, int empty, int damaged, int atRefiller, String staff, String note, String refillId,
                    double unitCost) {}

    /**
     * kind: refill | purchase. source: "sales" (store money), "owner" (the owner's personal money) or
     * "staff" (paidBy paid from their own pocket). method: "cash", "check" or "online" (bank transfer, GCash).
     */
    record SupplierPayment(String id, String rawTime, LocalDateTime time, String supplierId, String refillId,
                           String kind, double amount, String staff, String note, String source, String paidBy, String method) {
        boolean fromStaff() { return "staff".equals(source); }

        boolean fromOwner() { return "owner".equals(source); }

        boolean byCheck() { return "check".equals(method); }

        boolean online() { return "online".equals(method); }

        /** Only cash from the store's own money leaves the drawer (checks and online come from the bank). */
        boolean cashFromDrawer() { return !fromStaff() && !fromOwner() && "cash".equals(method); }
    }

    static String supplierMethod(String v) { return "check".equals(v) || "online".equals(v) ? v : "cash"; }

    /**
     * Money spent besides suppliers. category: gasoline | salary | advance | other. For a salary, amount is
     * what was handed over and advanceDeducted the advances taken off it (the salary itself is the sum).
     * method: "cash", "online" or "check"; only cash comes out of the drawer.
     */
    record Expense(String id, String rawTime, LocalDateTime time, String category, double amount, String employee,
                   String method, String recordedBy, String note, double advanceDeducted) {
        boolean cash() { return "cash".equals(method); }

        /** Money out of the store: an advance paid back ("repaid") is money in. */
        double outflow() { return "repaid".equals(category) ? -amount : amount; }

        /** Salaries, advances and advances paid back: kept apart from the other expenses. */
        boolean payroll() { return PAYROLL.contains(category); }

        /** How this changes the employee's unpaid advances. */
        double advanceEffect() {
            return switch (category) {
                case "advance" -> amount;
                case "salary" -> -advanceDeducted;
                case "repaid" -> -amount;
                default -> 0;
            };
        }
    }

    static final List<String> EXPENSE_CATEGORIES = List.of("gasoline", "salary", "advance", "repaid", "other");
    static final Set<String> PAYROLL = Set.of("salary", "advance", "repaid");

    /**
     * Someone on the payroll. schedule: weekly (day1 = 1..7, Monday..Sunday), twice (a month, on day1 and day2)
     * or monthly (on day1; a day past the month's end means its last day).
     */
    record Employee(String id, String name, double salary, String schedule, int day1, int day2, String created, String note) {}

    static String expenseMethod(String v) { return "online".equals(v) || "check".equals(v) ? v : "cash"; }

    /**
     * One payment to a supplier, split by where the money came from: the store's sales, the owner's
     * personal money, or a staff member's own (byStaff). method: how it was handed over, "cash" or "check".
     */
    record Paid(double sales, double staff, String byStaff, double owner, String method) {
        Paid(double sales, double staff, String byStaff) {
            this(sales, staff, byStaff, 0, "cash");
        }

        double total() { return round(sales + owner + staff); }
    }

    /** The store paying a staff member back for money they put in. */
    record Payback(String id, String rawTime, LocalDateTime time, String staff, double amount, String recordedBy, String note) {}

    record Stock(int loaded, int empty, int damaged, int atRefiller) {
        static final Stock NONE = new Stock(0, 0, 0, 0);
    }

    record Supplier(String id, String name, String contact, List<String> brands, String note) {
        boolean supplies(String brand) {
            return brands.stream().anyMatch(b -> b.equalsIgnoreCase(brand.trim()));
        }
    }

    /** start: not a real trip, the tanks already at the refiller on the start date. */
    record Refill(String id, String rawTime, LocalDateTime time, String supplierId, String staff, String note, boolean start) {}

    /** Per product on one refill trip: tanks sent, back with load, and returned unfilled. */
    record RefillItem(String productId, int sent, int received, int rejected) {
        int outstanding() { return sent - received - rejected; }
    }

    record LineInput(Product product, int qty, double unitPrice, String emptyProductId, String remark, double swapFee,
                     String emptyKind, String emptyBrand) {
        LineInput(Product product, int qty, double unitPrice, String emptyProductId, String remark, double swapFee) {
            this(product, qty, unitPrice, emptyProductId, remark, swapFee, "", "");
        }

        boolean owesTank() { return emptyProductId.isEmpty(); }

        boolean looseEmpty() { return !emptyKind.isEmpty() && !emptyProductId.isEmpty(); }

        double amount() { return qty * (unitPrice + swapFee); }
    }

    /** Empty tanks a customer brought back for tanks they took without one. */
    record TankReturn(String id, String rawTime, LocalDateTime time, String customerId, String productId, int qty,
                      String staff, String remark, String emptyKind, String emptyBrand) {
        TankReturn(String id, String rawTime, LocalDateTime time, String customerId, String productId, int qty, String staff, String remark) {
            this(id, rawTime, time, customerId, productId, qty, staff, remark, "", "");
        }

        boolean looseEmpty() { return !emptyKind.isEmpty(); }
    }

    /** "generic" or "other" (another brand the store doesn't sell), or "" for an empty of a product. */
    static String emptyKind(String v) { return "generic".equals(v) || "other".equals(v) ? v : ""; }

    /** "Generic" or the other brand's name, for a loose empty. */
    static String looseLabel(String kind, String brand) {
        return "generic".equals(kind) ? "Generic" : brand == null || brand.isBlank() ? "Other brand" : brand.trim();
    }

    /** Tanks a customer still has to bring back, per receipt line (oldest are settled first). */
    record OwedTank(String customerId, String productId, int qty, String receiptId, String rawTime, LocalDateTime time) {}

    /**
     * Something the store owes a supplier for: a refill trip (key = trip id), new tanks bought
     * (key = "P" + the purchase movement's id) or what was owed on the start date (key = "O" + supplier id, kind "start").
     */
    record Bill(String key, String supplierId, String kind, String label, String rawTime, LocalDateTime time, double cost, double paid) {
        double owed() { return round(Math.max(0, cost - paid)); }
    }

    record User(String username, String salt, String hash, String created, boolean dashboard) {}

    /** A business rule stopped the change (not enough stock, overpayment...). Shown to staff as-is. */
    static final class Conflict extends RuntimeException {
        Conflict(String message) {
            super(message);
        }
    }

    private final Path dir;
    private final Object lock = new Object();
    /**
     * The business as it stood on the start date. Maps keep the order entered. stock: product → {with load, empty};
     * atRefiller: supplier → product → tanks; customerTanks: our tanks a customer has; deposits: a customer's own
     * tanks kept at the store.
     */
    record Opening(LocalDate start, double cash, Map<String, int[]> stock, Map<String, Map<String, Integer>> atRefiller,
                   Map<String, Double> customerMoney, Map<String, Map<String, Integer>> customerTanks,
                   Map<String, Map<String, Integer>> deposits, Map<String, Double> suppliers, Map<String, Double> advances,
                   String staff, String savedAt) {
        LocalDateTime time() { return start.atStartOfDay(); }
    }

    /** A customer's own tanks kept at the store. kind: in (left with us), back (given back), used (the empty on receiptId). */
    record Deposit(String id, String rawTime, LocalDateTime time, String customerId, String productId, int qty, String kind,
                   String staff, String note, String receiptId) {}

    private final Table customers, products, lines, receipts, payments, movements, suppliers, refills, supplierPayments, tankReturns, paybacks, expenses, users,
            openings, deposits, employees;
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
        tankReturns = new Table("TankReturns.csv");
        paybacks = new Table("StaffPaybacks.csv");
        expenses = new Table("Expenses.csv");
        users = new Table("WebUsers.csv");
        openings = new Table("StartingBalances.csv");
        deposits = new Table("TankDeposits.csv");
        employees = new Table("Employees.csv");
        // users too: the POS data carries the viewer's dashboard permission.
        all = List.of(customers, products, lines, receipts, payments, movements, suppliers, refills, supplierPayments, tankReturns, paybacks, expenses, users,
                openings, deposits, employees);
    }

    Path dir() { return dir; }

    Map<String, Object> adminRecords(String table) throws IOException {
        synchronized (lock) { return DatabaseEditor.read(dir, table); }
    }

    Map<String, Object> correctRecord(String table, String mode, String id, String revision,
                                      String[] row, String reason, ActivityLog log) throws IOException {
        synchronized (lock) {
            DatabaseEditor.Change change = DatabaseEditor.prepare(dir, table, mode, id, revision, row, reason);
            Table target = all.stream().filter(t -> t.path.getFileName().toString().equals(table + ".csv"))
                    .findFirst().orElseThrow(() -> new Http.Error(404, "Unknown business table"));
            log.add("admin", "this Mac", "Database correction requested: " + change.message() + "; backup " + change.backup());
            target.save(change.rows());
            log.add("admin", "this Mac", "Database correction saved: " + change.message());
            return Json.obj("ok", true, "backup", change.backup());
        }
    }

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

    /** Returns the number of receipts blocking deletion, 0 when deleted, -1 when not found, -2 when in the starting balances or deposits. */
    int deleteCustomer(String id) {
        synchronized (lock) {
            long used = receipts().stream().filter(r -> r.customerId().equals(id)).count();
            if (used > 0) return (int) used;
            if (inOpening("customer", id) || deposits().stream().anyMatch(d -> d.customerId().equals(id))) return -2;
            return customers.remove(id) ? 0 : -1;
        }
    }

    // ---------------------------------------------------------------- products

    List<Product> products() {
        synchronized (lock) {
            List<Product> out = new ArrayList<>();
            for (String[] r : products.rows()) {
                out.add(new Product(col(r, 0), col(r, 1), num(col(r, 2), 0), num(col(r, 3), 0),
                        (int) num(col(r, 4), 5), num(col(r, 5), 0), num(col(r, 6), 0), num(col(r, 7), 0), num(col(r, 8), 0)));
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
            Product saved = new Product(nextId(rows), p.brand(), p.price(), p.weight(), p.reorderLevel(), p.tankPrice(), p.refillCost(), p.tankCost(), p.swapFee());
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
                money(p.tankPrice()), money(p.refillCost()), money(p.tankCost()), money(p.swapFee())};
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
            if (tankReturns().stream().anyMatch(t -> t.productId().equals(id))) return "Customers have returned tanks of this product, so it can't be deleted.";
            if (inOpening("product", id)) return "This product is in the starting balances, so it can't be deleted.";
            if (deposits().stream().anyMatch(d -> d.productId().equals(id))) return "Customers have left tanks of this product with the store, so it can't be deleted.";
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
                boolean owes = emptyId.isEmpty() && "1".equals(col(r, 11));
                String kind = emptyId.isEmpty() ? "" : emptyKind(col(r, 13));
                out.add(new Line(col(r, 0), receiptIdOf(r), col(r, 1), pid, col(r, 3), parseTime(col(r, 3)),
                        qty, unit, col(r, 6), emptyId, remark, owes, emptyId.isEmpty() ? 0 : num(col(r, 12), 0),
                        kind, kind.equals("other") ? col(r, 14) : ""));
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
                    out.add(new Receipt(id, first.rawTime(), first.time(), first.customerId(), first.staff(), 0, total, "", ls, "cash"));
                } else {
                    out.add(new Receipt(id, col(m, 1), parseTime(col(m, 1)), col(m, 2), col(m, 3),
                            num(col(m, 4), 0), num(col(m, 5), 0), col(m, 6), ls, customerMethod(col(m, 7))));
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
                        num(col(r, 4), 0), col(r, 5), col(r, 6), customerMethod(col(r, 7))));
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
        return createReceipt(customerId, staff, items, discount, paidNow, note, "cash");
    }

    Receipt createReceipt(String customerId, String staff, List<LineInput> items, double discount, double paidNow, String note, String payMethod) {
        return createReceipt(customerId, staff, items, discount, paidNow, note, payMethod, false);
    }

    /** As above; fromDeposit: the empties handed over are the customer's tanks already kept at the store. */
    Receipt createReceipt(String customerId, String staff, List<LineInput> items, double discount, double paidNow, String note, String payMethod,
                          boolean fromDeposit) {
        synchronized (lock) {
            checkStock(items, Map.of());
            Map<String, Integer> fromKept = new LinkedHashMap<>();
            if (fromDeposit) {
                for (LineInput i : items) if (!i.emptyProductId().isEmpty() && !i.looseEmpty()) fromKept.merge(i.emptyProductId(), i.qty(), Integer::sum);
                if (fromKept.isEmpty()) throw new Conflict("No empty tank is on this receipt, so none can come from the customer's kept tanks.");
                Map<String, Integer> held = depositsHeld().getOrDefault(customerId, Map.of());
                for (var e : fromKept.entrySet()) {
                    int have = held.getOrDefault(e.getKey(), 0);
                    if (e.getValue() > have) {
                        Product p = product(e.getKey());
                        throw new Conflict("The store keeps only " + have + " of this customer's " + (p == null ? "" : p.label() + " ") + "tanks.");
                    }
                }
            }
            double subtotal = round(items.stream().mapToDouble(LineInput::amount).sum());
            checkMoney(subtotal, discount, paidNow, 0);
            List<String[]> rrows = receipts.copy();
            String id = nextId(rrows);
            String time = now();
            rrows.add(new String[]{id, time, customerId, staff, money(discount), money(paidNow), note, customerMethod(payMethod)});
            receipts.save(rrows);
            writeLines(id, customerId, time, staff, items);
            if (!fromKept.isEmpty()) {
                List<String[]> drows = deposits.copy();
                int next = Integer.parseInt(nextId(drows));
                for (var e : fromKept.entrySet()) {
                    drows.add(new String[]{String.valueOf(next++), time, customerId, e.getKey(), String.valueOf(e.getValue()), "used", staff, "", id});
                }
                deposits.save(drows);
            }
            return receipt(id);
        }
    }

    /** Replaces a receipt's items, keeping its number, time and seller. Returns null if not found. */
    Receipt updateReceipt(String id, String customerId, List<LineInput> items, double discount, double paidAtSale, String note) {
        Receipt old = receipt(id);
        return updateReceipt(id, customerId, items, discount, paidAtSale, note, old == null ? "cash" : old.payMethod());
    }

    Receipt updateReceipt(String id, String customerId, List<LineInput> items, double discount, double paidAtSale, String note, String payMethod) {
        return updateReceipt(id, customerId, items, discount, paidAtSale, note, payMethod, null);
    }

    /** As above; day (when not null) moves the receipt to that day, keeping its time of day. */
    Receipt updateReceipt(String id, String customerId, List<LineInput> items, double discount, double paidAtSale, String note, String payMethod,
                          LocalDate day) {
        synchronized (lock) {
            Receipt old = receipt(id);
            if (old == null) return null;
            String time = day == null || old.time() == null ? old.rawTime() : day.atTime(old.time().toLocalTime()).format(TIME);
            Map<String, Integer> own = new HashMap<>();
            for (Line l : old.lines()) own.merge(l.productId(), l.qty(), Integer::sum);
            checkStock(items, own);
            // Tanks already returned against this receipt's customer must still be owed afterwards.
            int oldOwed = old.lines().stream().filter(Line::owesTank).mapToInt(Line::qty).sum();
            int newOwed = items.stream().filter(LineInput::owesTank).mapToInt(LineInput::qty).sum();
            int stillOwed = owedCount(old.customerId()) - oldOwed + (old.customerId().equals(customerId) ? newOwed : 0);
            if (stillOwed < 0) throw new Conflict(returnedMessage(old.customerId()));
            // Empties that came from the customer's kept tanks must stay on the receipt.
            Map<String, Integer> kept = new HashMap<>(), empties = new HashMap<>();
            for (Deposit d : deposits()) if ("used".equals(d.kind()) && d.receiptId().equals(id)) kept.merge(d.productId(), d.qty(), Integer::sum);
            for (LineInput i : items) if (!i.emptyProductId().isEmpty() && !i.looseEmpty()) empties.merge(i.emptyProductId(), i.qty(), Integer::sum);
            if (!kept.isEmpty() && (!old.customerId().equals(customerId) || kept.entrySet().stream().anyMatch(e -> empties.getOrDefault(e.getKey(), 0) < e.getValue()))) {
                throw new Conflict("This sale used the customer's kept tanks as its empties. Keep those empties and the customer, or void the receipt and sell again.");
            }
            double subtotal = round(items.stream().mapToDouble(LineInput::amount).sum());
            double later = payments().stream().filter(p -> p.receiptId().equals(id)).mapToDouble(Payment::amount).sum();
            checkMoney(subtotal, discount, paidAtSale, later);
            String[] row = {id, time, customerId, old.staff(), money(discount), money(paidAtSale), note, customerMethod(payMethod)};
            if (!receipts.replace(id, row)) {
                List<String[]> rrows = receipts.copy();
                rrows.add(row);
                receipts.save(rrows);
            }
            lines.removeWhere(r -> receiptIdOf(r).equals(id));
            writeLines(id, customerId, time, old.staff(), items);
            payments.saveIf(r -> col(r, 2).equals(id), r -> {
                r[3] = customerId;
                return r;
            });
            if (!kept.isEmpty() && !time.equals(old.rawTime())) {
                deposits.saveIf(r -> "used".equals(col(r, 5)) && col(r, 8).equals(id), r -> {
                    r[1] = time;
                    return r;
                });
            }
            return receipt(id);
        }
    }

    /** Deletes a receipt with its items and payments; its stock goes back. Returns it, or null. */
    Receipt voidReceipt(String id) {
        synchronized (lock) {
            Receipt r = receipt(id);
            if (r == null) return null;
            int owed = r.lines().stream().filter(Line::owesTank).mapToInt(Line::qty).sum();
            if (owedCount(r.customerId()) - owed < 0) throw new Conflict(returnedMessage(r.customerId()));
            lines.removeWhere(row -> receiptIdOf(row).equals(id));
            receipts.remove(id);
            payments.removeWhere(row -> col(row, 2).equals(id));
            // Kept tanks handed over as this receipt's empties are the customer's again.
            deposits.removeWhere(row -> "used".equals(col(row, 5)) && col(row, 8).equals(id));
            return r;
        }
    }

    private void writeLines(String receiptId, String customerId, String time, String staff, List<LineInput> items) {
        List<String[]> rows = lines.copy();
        int next = Integer.parseInt(nextId(rows));
        for (LineInput i : items) {
            rows.add(new String[]{String.valueOf(next++), customerId, i.product().id(), time, String.valueOf(i.qty()),
                    money(i.unitPrice()), staff == null ? "" : staff, i.emptyProductId().isEmpty() ? "0" : "1",
                    receiptId, i.emptyProductId(), i.remark(), i.owesTank() ? "1" : "", i.swapFee() > 0 ? money(i.swapFee()) : "",
                    i.looseEmpty() ? i.emptyKind() : "", i.looseEmpty() && i.emptyKind().equals("other") ? i.emptyBrand() : ""});
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
        return addPayment(customerId, receiptId, amount, staff, note, "cash");
    }

    List<Payment> addPayment(String customerId, String receiptId, double amount, String staff, String note, String method) {
        synchronized (lock) {
            List<Receipt> rs = receipts();
            Map<String, Double> paid = paidByReceipt(rs);
            // What's owed, oldest first: the starting balance (receipt "O" + customer id), then unpaid receipts.
            record Due(String id, double balance) {}
            List<Due> owing = new ArrayList<>();
            if (receiptId == null || receiptId.equals(openingKey(customerId))) {
                double start = openingOwed().getOrDefault(customerId, 0.0);
                if (start > 0) owing.add(new Due(openingKey(customerId), start));
            }
            rs.stream().filter(r -> receiptId == null ? r.customerId().equals(customerId) : r.id().equals(receiptId))
                    .filter(r -> balance(r, paid) > 0).forEach(r -> owing.add(new Due(r.id(), balance(r, paid))));
            double owed = round(owing.stream().mapToDouble(Due::balance).sum());
            if (owed <= 0) throw new Conflict("Nothing is owed" + (receiptId == null ? " by this customer." : " on this receipt."));
            if (amount > owed + 0.005) throw new Conflict("That's more than the ₱" + money(owed) + " owed.");
            List<String[]> rows = payments.copy();
            int next = Integer.parseInt(nextId(rows));
            String time = now();
            double left = round(amount);
            List<Payment> out = new ArrayList<>();
            for (Due r : owing) {
                if (left <= 0) break;
                double part = round(Math.min(left, r.balance()));
                String id = String.valueOf(next++);
                rows.add(new String[]{id, time, r.id(), customerId, money(part), staff, note, customerMethod(method)});
                out.add(new Payment(id, time, parseTime(time), r.id(), customerId, part, staff, note, customerMethod(method)));
                left = round(left - part);
            }
            payments.save(rows);
            return out;
        }
    }

    // ---------------------------------------------------------------- tanks customers owe

    List<TankReturn> tankReturns() {
        synchronized (lock) {
            List<TankReturn> out = new ArrayList<>();
            for (String[] r : tankReturns.rows()) {
                String kind = emptyKind(col(r, 7));
                out.add(new TankReturn(col(r, 0), col(r, 1), parseTime(col(r, 1)), col(r, 2), col(r, 3),
                        (int) Math.max(1, num(col(r, 4), 1)), col(r, 5), col(r, 6), kind, kind.equals("other") ? col(r, 8) : ""));
            }
            return out;
        }
    }

    /**
     * Tanks still to be brought back, per customer, oldest first. Returns settle the oldest
     * tanks first, whatever brand comes back (a customer may return another brand).
     */
    Map<String, List<OwedTank>> owedTanks() {
        synchronized (lock) {
            Map<String, List<OwedTank>> out = new LinkedHashMap<>();
            Opening o = opening();
            if (o != null) {
                String raw = o.time().format(TIME);
                o.customerTanks().forEach((cid, tanks) -> tanks.forEach((pid, qty) -> {
                    if (qty > 0) out.computeIfAbsent(cid, k -> new ArrayList<>()).add(new OwedTank(cid, pid, qty, "start", raw, o.time()));
                }));
            }
            for (Receipt r : receipts()) {
                for (Line l : r.lines()) {
                    if (l.owesTank()) out.computeIfAbsent(r.customerId(), k -> new ArrayList<>())
                            .add(new OwedTank(r.customerId(), l.productId(), l.qty(), r.id(), r.rawTime(), r.time()));
                }
            }
            Map<String, Integer> returned = new HashMap<>();
            for (TankReturn t : tankReturns()) returned.merge(t.customerId(), t.qty(), Integer::sum);
            Map<String, List<OwedTank>> left = new LinkedHashMap<>();
            out.forEach((cid, list) -> {
                int settle = returned.getOrDefault(cid, 0);
                List<OwedTank> still = new ArrayList<>();
                for (OwedTank ow : list) {
                    int take = Math.min(settle, ow.qty());
                    settle -= take;
                    if (ow.qty() > take) still.add(new OwedTank(cid, ow.productId(), ow.qty() - take, ow.receiptId(), ow.rawTime(), ow.time()));
                }
                if (!still.isEmpty()) left.put(cid, still);
            });
            return left;
        }
    }

    /** Tanks this customer still owes (taken without an empty, minus what they brought back). */
    int owedCount(String customerId) {
        synchronized (lock) {
            int owed = 0;
            Opening o = opening();
            if (o != null) for (int n : o.customerTanks().getOrDefault(customerId, Map.of()).values()) owed += n;
            for (Line l : lines()) if (l.owesTank() && l.customerId().equals(customerId)) owed += l.qty();
            for (TankReturn t : tankReturns()) if (t.customerId().equals(customerId)) owed -= t.qty();
            return owed;
        }
    }

    private String returnedMessage(String customerId) {
        Customer c = customer(customerId);
        return (c == null ? "This customer" : c.name()) + " has already returned tanks for this. Change it another way, or count the stock instead.";
    }

    /** Records empty tanks a customer brought back; they go into the store as empties of that product. */
    TankReturn returnTanks(Customer c, Product p, int qty, String staff, String remark) {
        return returnTanks(c, p, qty, staff, remark, "", "");
    }

    /** As above; kind "generic" / "other" (brand): a loose empty of p's size instead of p itself. */
    TankReturn returnTanks(Customer c, Product p, int qty, String staff, String remark, String kind, String brand) {
        kind = emptyKind(kind);
        brand = kind.equals("other") ? (brand == null ? "" : brand.trim()) : "";
        synchronized (lock) {
            int owed = owedCount(c.id());
            if (owed <= 0) throw new Conflict(c.name() + " doesn't owe any tanks.");
            if (qty > owed) throw new Conflict(c.name() + " owes only " + owed + " tank" + (owed == 1 ? "" : "s") + ".");
            List<String[]> rows = tankReturns.copy();
            String id = nextId(rows);
            String time = now();
            rows.add(kind.isEmpty() ? new String[]{id, time, c.id(), p.id(), String.valueOf(qty), staff, remark}
                    : new String[]{id, time, c.id(), p.id(), String.valueOf(qty), staff, remark, kind, brand});
            tankReturns.save(rows);
            return new TankReturn(id, time, parseTime(time), c.id(), p.id(), qty, staff, remark, kind, brand);
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
            Opening o = opening();
            if (o != null) {
                o.stock().forEach((pid, v) -> {
                    int[] t = s.computeIfAbsent(pid, k -> new int[4]);
                    t[0] += v[0];
                    t[1] += v[1];
                });
                o.atRefiller().values().forEach(m -> m.forEach((pid, n) -> s.computeIfAbsent(pid, k -> new int[4])[3] += n));
            }
            for (Movement m : movements()) {
                int[] v = s.computeIfAbsent(m.productId(), k -> new int[4]);
                v[0] += m.loaded();
                v[1] += m.empty();
                v[2] += m.damaged();
                v[3] += m.atRefiller();
            }
            for (Line l : lines()) {
                s.computeIfAbsent(l.productId(), k -> new int[4])[0] -= l.qty();
                if (l.returnedEmpty() && !l.looseEmpty()) s.computeIfAbsent(l.emptyProductId(), k -> new int[4])[1] += l.qty();
            }
            for (TankReturn t : tankReturns()) if (!t.looseEmpty()) s.computeIfAbsent(t.productId(), k -> new int[4])[1] += t.qty();
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
        return adjustTo(p, type, loaded, empty, damaged, staff, note, new ArrayList<>());
    }

    /** As above; adds the stock change it recorded to made. */
    Stock adjustTo(Product p, String type, int loaded, int empty, Integer damaged, String staff, String note, List<Movement> made) {
        synchronized (lock) {
            Stock before = stock().getOrDefault(p.id(), Stock.NONE);
            if (damaged == null) damaged = before.damaged();
            if (before.loaded() == loaded && before.empty() == empty && before.damaged() == damaged) return null;
            made.add(addMovement(p, type, loaded - before.loaded(), empty - before.empty(), damaged - before.damaged(), 0, staff, note, null));
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
            if (hasTanksOut(id)) return "Tanks are still at this refiller. Receive them first.";
            suppliers.remove(id);
            return null;
        }
    }

    /** True when some tanks sent to this supplier haven't come back yet. */
    boolean hasTanksOut(String supplierId) {
        synchronized (lock) {
            return refills().stream().anyMatch(r -> r.supplierId().equals(supplierId)
                    && refillItems(r.id()).stream().anyMatch(i -> i.outstanding() > 0));
        }
    }

    List<Refill> refills() {
        synchronized (lock) {
            List<Refill> out = new ArrayList<>();
            for (String[] r : refills.rows()) {
                out.add(new Refill(col(r, 0), col(r, 1), parseTime(col(r, 1)), col(r, 2), col(r, 3), col(r, 4), "1".equals(col(r, 5))));
            }
            return out;
        }
    }

    List<RefillItem> refillItems(String refillId) {
        Map<String, int[]> m = new LinkedHashMap<>();
        // The tanks already at the refiller on the start date count as sent on its start trip.
        for (String[] r : openings.rows()) {
            if ("refiller".equals(col(r, 2)) && refillId.equals(col(r, 7))) m.computeIfAbsent(col(r, 4), k -> new int[3])[0] += (int) num(col(r, 5), 0);
        }
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
     * unfilled ones come back as empty tanks, free. paid is what was handed to the refiller, and from whose money.
     */
    /** Returns the first stock change it recorded (undoing it undoes this whole receiving). */
    Movement receiveFromRefiller(Refill refill, Map<Product, int[]> items, Map<Product, Double> costs, Paid paid,
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
            if (paid.total() > owed + 0.005) throw new Conflict("That's more than the ₱" + money(Math.max(0, owed)) + " this trip costs.");
            Movement first = null;
            for (var e : items.entrySet()) {
                int got = e.getValue()[0], rejected = e.getValue()[1];
                Movement m = null;
                if (got > 0) m = addMovement(e.getKey(), "refill-receive", got, 0, 0, -got, staff, note, refill.id(), costs.getOrDefault(e.getKey(), 0.0));
                if (rejected > 0) {
                    Movement back = addMovement(e.getKey(), "refill-reject", 0, rejected, 0, -rejected, staff, note, refill.id());
                    if (m == null) m = back;
                }
                if (first == null) first = m;
            }
            addPaid(refill.supplierId(), refill.id(), "refill", paid, staff, note);
            return first;
        }
    }

    /**
     * A refill done in one go (the supplier took the empties and brought them back filled): one trip,
     * sent and received at once, at costs[product] each, with what was paid. Needs that many good empties.
     */
    Refill recordRefill(Supplier s, Map<Product, Integer> items, Map<Product, Double> costs, Paid paid, String staff, String note) {
        synchronized (lock) {
            Map<String, Stock> stock = stock();
            double bill = 0;
            for (var e : items.entrySet()) {
                int empty = stock.getOrDefault(e.getKey().id(), Stock.NONE).empty();
                if (e.getValue() > empty) throw new Conflict("Only " + empty + " good empty " + e.getKey().label() + " tanks on hand to refill.");
                bill += e.getValue() * costs.getOrDefault(e.getKey(), 0.0);
            }
            if (paid.total() > round(bill) + 0.005) throw new Conflict("That's more than the ₱" + money(round(bill)) + " this refill costs.");
            Refill rf = sendToRefiller(s, items, staff, note);
            for (var e : items.entrySet()) {
                addMovement(e.getKey(), "refill-receive", e.getValue(), 0, 0, -e.getValue(), staff, note, rf.id(), costs.getOrDefault(e.getKey(), 0.0));
            }
            addPaid(s.id(), rf.id(), "refill", paid, staff, note);
            return rf;
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
    SupplierPayment payRefiller(Refill refill, Paid paid, String staff, String note) {
        synchronized (lock) {
            double owed = round(refillCost(refill.id()) - refillPaid(refill.id()));
            if (owed <= 0) throw new Conflict("Nothing is owed on this trip.");
            if (paid.total() <= 0) throw new Conflict("Enter how much was paid.");
            if (paid.total() > owed + 0.005) throw new Conflict("That's more than the ₱" + money(owed) + " still owed on this trip.");
            return addPaid(refill.supplierId(), refill.id(), "refill", paid, staff, note);
        }
    }

    /** New tanks with load bought from the brand's supplier; what's paid now can be less than the bill (pay later). */
    Movement purchase(Product p, int qty, double unitCost, Paid paid, String staff, String note) {
        synchronized (lock) {
            double bill = round(qty * unitCost);
            if (paid.total() > bill + 0.005) throw new Conflict("That's more than the ₱" + money(bill) + " these tanks cost.");
            Supplier s = supplierFor(p.brand());
            if (s == null && paid.total() < bill - 0.005) {
                throw new Conflict("Add the supplier for " + p.brand() + " first, so the unpaid ₱" + money(round(bill - paid.total())) + " is owed to someone.");
            }
            Movement m = addMovement(p, "purchase", qty, 0, 0, 0, staff, note, null, unitCost);
            addPaid(s == null ? "" : s.id(), "P" + m.id(), "purchase", paid, staff, note);
            return m;
        }
    }

    /** One row per source of money (sales, a staff member's own), for the same bill. Returns the first row, or null when nothing was paid. */
    private SupplierPayment addPaid(String supplierId, String billKey, String kind, Paid paid, String staff, String note) {
        List<SupplierPayment> out = new ArrayList<>();
        if (paid.sales() > 0) out.add(addSupplierPayment(supplierId, billKey, kind, round(paid.sales()), staff, note, "sales", "", paid.method()));
        if (paid.owner() > 0) out.add(addSupplierPayment(supplierId, billKey, kind, round(paid.owner()), staff, note, "owner", "", paid.method()));
        if (paid.staff() > 0) out.add(addSupplierPayment(supplierId, billKey, kind, round(paid.staff()), staff, note, "staff", paid.byStaff(), paid.method()));
        return out.isEmpty() ? null : out.get(0);
    }

    /**
     * Everything the store was billed for, oldest first, with what was paid: refill trips and
     * new tanks bought. Purchases are billed to their brand's supplier. Purchase payments
     * recorded before 3.0 (no bill key) settle that supplier's oldest purchases.
     */
    List<Bill> bills() {
        synchronized (lock) {
            Map<String, Product> prods = productMap();
            List<SupplierPayment> pays = supplierPayments();
            Map<String, Double> paidByKey = new HashMap<>();
            Map<String, Double> loose = new HashMap<>(); // supplierId -> old purchase payments without a key
            for (SupplierPayment sp : pays) {
                if (!sp.refillId().isEmpty()) paidByKey.merge(sp.refillId(), sp.amount(), Double::sum);
                else if ("purchase".equals(sp.kind())) loose.merge(sp.supplierId(), sp.amount(), Double::sum);
            }
            Map<String, Supplier> byBrand = new HashMap<>();
            for (Supplier s : suppliers()) for (String b : s.brands()) byBrand.putIfAbsent(b.toLowerCase(Locale.ROOT), s);
            Map<String, Double> tripCost = new HashMap<>();
            List<Bill> out = new ArrayList<>();
            for (Movement m : movements()) {
                if ("refill-receive".equals(m.type())) tripCost.merge(m.refillId(), m.loaded() * m.unitCost(), Double::sum);
                if ("purchase".equals(m.type()) && m.unitCost() > 0) {
                    Product p = prods.get(m.productId());
                    Supplier s = p == null ? null : byBrand.get(p.brand().toLowerCase(Locale.ROOT));
                    String sid = s == null ? "" : s.id();
                    double cost = round(m.loaded() * m.unitCost());
                    double paid = paidByKey.getOrDefault("P" + m.id(), 0.0);
                    double fromOld = Math.min(Math.max(0, cost - paid), loose.getOrDefault(sid, 0.0));
                    loose.merge(sid, -fromOld, Double::sum);
                    out.add(new Bill("P" + m.id(), sid, "purchase", m.loaded() + " new × " + (p == null ? "#" + m.productId() : p.label()),
                            m.rawTime(), m.time(), cost, round(paid + fromOld)));
                }
            }
            Opening o = opening();
            if (o != null) {
                String raw = o.time().format(TIME);
                o.suppliers().forEach((sid, amount) -> out.add(new Bill(openingKey(sid), sid, "start", "Owed on " + o.start(), raw, o.time(),
                        round(amount), round(paidByKey.getOrDefault(openingKey(sid), 0.0)))));
            }
            for (Refill rf : refills()) {
                double cost = round(tripCost.getOrDefault(rf.id(), 0.0));
                if (cost > 0 || paidByKey.containsKey(rf.id())) {
                    out.add(new Bill(rf.id(), rf.supplierId(), "refill", "Refill trip #" + rf.id(), rf.rawTime(), rf.time(),
                            cost, round(paidByKey.getOrDefault(rf.id(), 0.0))));
                }
            }
            out.sort(Comparator.comparing((Bill b) -> b.time() == null ? LocalDateTime.MIN : b.time()));
            return out;
        }
    }

    /** Pays a supplier, settling their oldest unpaid bills first (sales money first, then the owner's, then staff money). */
    List<SupplierPayment> paySupplier(Supplier s, Paid paid, String staff, String note) {
        synchronized (lock) {
            List<Bill> owing = bills().stream().filter(b -> b.supplierId().equals(s.id()) && b.owed() > 0).toList();
            double owed = round(owing.stream().mapToDouble(Bill::owed).sum());
            if (owed <= 0) throw new Conflict("Nothing is owed to " + s.name() + ".");
            if (paid.total() <= 0) throw new Conflict("Enter how much was paid.");
            if (paid.total() > owed + 0.005) throw new Conflict("That's more than the ₱" + money(owed) + " owed to " + s.name() + ".");
            List<SupplierPayment> out = new ArrayList<>();
            double[] left = {round(paid.sales()), round(paid.owner()), round(paid.staff())};
            String[] source = {"sales", "owner", "staff"};
            int k = 0;
            for (Bill b : owing) {
                double due = b.owed();
                while (due > 0.005 && k < 3) {
                    if (left[k] <= 0.005) { k++; continue; }
                    double part = round(Math.min(left[k], due));
                    out.add(addSupplierPayment(s.id(), b.key(), b.kind(), part, staff, note, source[k], k == 2 ? paid.byStaff() : "", paid.method()));
                    left[k] = round(left[k] - part);
                    due = round(due - part);
                }
            }
            return out;
        }
    }

    List<SupplierPayment> supplierPayments() {
        synchronized (lock) {
            List<SupplierPayment> out = new ArrayList<>();
            for (String[] r : supplierPayments.rows()) {
                out.add(new SupplierPayment(col(r, 0), col(r, 1), parseTime(col(r, 1)), col(r, 2), col(r, 3), col(r, 4),
                        num(col(r, 5), 0), col(r, 6), col(r, 7), "staff".equals(col(r, 8)) || "owner".equals(col(r, 8)) ? col(r, 8) : "sales",
                        col(r, 9), supplierMethod(col(r, 10))));
            }
            return out;
        }
    }

    private SupplierPayment addSupplierPayment(String supplierId, String refillId, String kind, double amount, String staff, String note,
                                               String source, String paidBy, String method) {
        List<String[]> rows = supplierPayments.copy();
        String id = nextId(rows);
        String time = now();
        rows.add(new String[]{id, time, supplierId, refillId, kind, money(amount), staff, note == null ? "" : note, source, paidBy, method});
        supplierPayments.save(rows);
        return new SupplierPayment(id, time, parseTime(time), supplierId, refillId, kind, amount, staff, note, source, paidBy, method);
    }

    // ---------------------------------------------------------------- staff money

    List<Payback> paybacks() {
        synchronized (lock) {
            List<Payback> out = new ArrayList<>();
            for (String[] r : paybacks.rows()) {
                out.add(new Payback(col(r, 0), col(r, 1), parseTime(col(r, 1)), col(r, 2), num(col(r, 3), 0), col(r, 4), col(r, 5)));
            }
            return out;
        }
    }

    /** What the store still owes each staff member who paid suppliers from their own pocket. */
    Map<String, Double> owedToStaff() {
        synchronized (lock) {
            Map<String, Double> owed = new TreeMap<>();
            for (SupplierPayment sp : supplierPayments()) if (sp.fromStaff()) owed.merge(sp.paidBy(), sp.amount(), Double::sum);
            for (Payback p : paybacks()) owed.merge(p.staff(), -p.amount(), Double::sum);
            owed.replaceAll((k, v) -> round(v));
            return owed;
        }
    }

    /** Records the store paying a staff member back (from sales money). */
    Payback payBackStaff(String staffName, double amount, String recordedBy, String note) {
        synchronized (lock) {
            double owed = owedToStaff().getOrDefault(staffName, 0.0);
            if (owed <= 0) throw new Conflict("Nothing is owed to " + staffName + ".");
            if (amount > owed + 0.005) throw new Conflict("That's more than the ₱" + money(owed) + " owed to " + staffName + ".");
            List<String[]> rows = paybacks.copy();
            String id = nextId(rows);
            String time = now();
            rows.add(new String[]{id, time, staffName, money(amount), recordedBy, note});
            paybacks.save(rows);
            return new Payback(id, time, parseTime(time), staffName, amount, recordedBy, note);
        }
    }

    // ---------------------------------------------------------------- expenses

    List<Expense> expenses() {
        synchronized (lock) {
            List<Expense> out = new ArrayList<>();
            for (String[] r : expenses.rows()) {
                out.add(new Expense(col(r, 0), col(r, 1), parseTime(col(r, 1)), col(r, 2), num(col(r, 3), 0), col(r, 4),
                        expenseMethod(col(r, 5)), col(r, 6), col(r, 7), num(col(r, 8), 0)));
            }
            out.sort(Comparator.comparing((Expense e) -> e.time() == null ? LocalDateTime.MIN : e.time()).thenComparingInt(e -> numericId(e.id())));
            return out;
        }
    }

    /** Salary advances not yet taken off a salary, per employee. */
    Map<String, Double> advancesOwed() {
        synchronized (lock) {
            Map<String, Double> owed = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Opening o = opening();
            if (o != null) o.advances().forEach((k, v) -> owed.merge(k, v, Double::sum));
            for (Expense e : expenses()) if (e.advanceEffect() != 0) owed.merge(e.employee(), e.advanceEffect(), Double::sum);
            owed.replaceAll((k, v) -> round(v));
            owed.values().removeIf(v -> v <= 0.005);
            return owed;
        }
    }

    /** Records an expense. A salary can take off (part of) the employee's unpaid advances. */
    Expense addExpense(String category, double amount, String employee, String method, String recordedBy, String note, double advanceDeducted) {
        synchronized (lock) {
            if (!EXPENSE_CATEGORIES.contains(category)) throw new Conflict("Choose what the expense was for.");
            boolean forEmployee = PAYROLL.contains(category);
            String who = forEmployee ? employee.trim() : "";
            if (forEmployee && who.isEmpty()) throw new Conflict("Enter the employee's name.");
            if (!category.equals("salary")) advanceDeducted = 0;
            if (category.equals("repaid")) {
                double open = advancesOwed().getOrDefault(who, 0.0);
                if (amount > open + 0.005) {
                    throw new Conflict(open <= 0 ? who + " has no unpaid advance." : who + " has only ₱" + money(open) + " in unpaid advances.");
                }
            }
            if (advanceDeducted > 0) {
                double open = advancesOwed().getOrDefault(who, 0.0);
                if (advanceDeducted > open + 0.005) {
                    throw new Conflict(open <= 0 ? who + " has no unpaid advance." : who + " has only ₱" + money(open) + " in unpaid advances.");
                }
            }
            if (amount <= 0 && advanceDeducted <= 0) throw new Conflict("Enter the amount.");
            List<String[]> rows = expenses.copy();
            String id = nextId(rows);
            String time = now();
            rows.add(new String[]{id, time, category, money(amount), who, expenseMethod(method),
                    recordedBy, note == null ? "" : note, advanceDeducted > 0 ? money(advanceDeducted) : ""});
            expenses.save(rows);
            return new Expense(id, time, parseTime(time), category, amount, who, expenseMethod(method), recordedBy, note, advanceDeducted);
        }
    }

    /**
     * Corrects an expense (what for, amount, employee, method, note, advance taken off) and, with day,
     * moves it to that day keeping its time of day. Refused when advances would end up taken off twice.
     * Returns the expense as it was, or null when not found.
     */
    Expense updateExpense(String id, String category, double amount, String employee, String method, String note,
                          double advanceDeducted, LocalDate day) {
        synchronized (lock) {
            Expense old = expenses().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
            if (old == null) return null;
            if (!EXPENSE_CATEGORIES.contains(category)) throw new Conflict("Choose what the expense was for.");
            boolean forEmployee = PAYROLL.contains(category);
            String who = forEmployee ? employee.trim() : "";
            if (forEmployee && who.isEmpty()) throw new Conflict("Enter the employee's name.");
            if (!category.equals("salary")) advanceDeducted = 0;
            if (amount <= 0 && advanceDeducted <= 0) throw new Conflict("Enter the amount.");
            String time = day == null || old.time() == null ? old.rawTime() : day.atTime(old.time().toLocalTime()).format(TIME);
            String[] row = {id, time, category, money(amount), who, expenseMethod(method), old.recordedBy(), note == null ? "" : note,
                    advanceDeducted > 0 ? money(advanceDeducted) : ""};
            // Every employee's advances must still cover what was taken off their salaries.
            Map<String, Double> left = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Opening o = opening();
            if (o != null) o.advances().forEach((k, v) -> left.merge(k, v, Double::sum));
            for (Expense e : expenses()) {
                String cat = e.id().equals(id) ? category : e.category(), name = e.id().equals(id) ? who : e.employee();
                double amt = e.id().equals(id) ? amount : e.amount(), took = e.id().equals(id) ? advanceDeducted : e.advanceDeducted();
                if ("advance".equals(cat)) left.merge(name, amt, Double::sum);
                else if ("salary".equals(cat) && took > 0) left.merge(name, -took, Double::sum);
                else if ("repaid".equals(cat)) left.merge(name, -amt, Double::sum);
            }
            for (var e : left.entrySet()) {
                if (e.getValue() < -0.005) throw new Conflict("That would take off more of " + e.getKey() + "'s advances than were given.");
            }
            expenses.replace(id, row);
            return old;
        }
    }

    // ---------------------------------------------------------------- payroll

    List<Employee> employees() {
        synchronized (lock) {
            List<Employee> out = new ArrayList<>();
            for (String[] r : employees.rows()) {
                if (col(r, 1).isBlank()) continue;
                out.add(new Employee(col(r, 0), col(r, 1).trim(), num(col(r, 2), 0), schedule(col(r, 3)),
                        (int) num(col(r, 4), 1), (int) num(col(r, 5), 0), col(r, 6), col(r, 7)));
            }
            out.sort(Comparator.comparing(Employee::name, String.CASE_INSENSITIVE_ORDER));
            return out;
        }
    }

    static String schedule(String v) { return "weekly".equals(v) || "twice".equals(v) ? v : "monthly"; }

    /**
     * Adds (id null) or changes someone on the payroll. A new name must not be taken; renaming also renames
     * their salary and advance records, so the history follows them.
     */
    Employee saveEmployee(String id, String name, double salary, String schedule, int day1, int day2, String note) {
        synchronized (lock) {
            name = name == null ? "" : name.trim();
            if (name.isEmpty()) throw new Conflict("Enter the employee's name.");
            schedule = schedule(schedule);
            if (schedule.equals("weekly") ? day1 < 1 || day1 > 7 : day1 < 1 || day1 > 31) throw new Conflict("Choose the pay day.");
            if (schedule.equals("twice") && (day2 < 1 || day2 > 31 || day2 == day1)) throw new Conflict("Choose two different pay days.");
            if (!schedule.equals("twice")) day2 = 0;
            if (schedule.equals("twice") && day2 < day1) { int t = day1; day1 = day2; day2 = t; }
            String key = name;
            Employee old = id == null ? null : employees().stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
            if (id != null && old == null) return null;
            if (employees().stream().anyMatch(e -> e.name().equalsIgnoreCase(key) && (old == null || !e.id().equals(old.id())))) {
                throw new Conflict(name + " is already on the payroll.");
            }
            String[] row = {id == null ? "" : id, name, money(salary), schedule, String.valueOf(day1), day2 > 0 ? String.valueOf(day2) : "",
                    old == null ? now() : old.created(), note == null ? "" : note};
            if (old == null) {
                List<String[]> rows = employees.copy();
                row[0] = nextId(rows);
                rows.add(row);
                employees.save(rows);
            } else {
                if (!old.name().equals(name)) {
                    List<String[]> rows = expenses.copy();
                    boolean changed = false;
                    for (String[] r : rows) {
                        if (r.length > 4 && PAYROLL.contains(col(r, 2)) && col(r, 4).trim().equalsIgnoreCase(old.name())) { r[4] = name; changed = true; }
                    }
                    if (changed) expenses.save(rows);
                    // Advances from the starting balances follow the name too (StartingBalances: kind advance, ref name).
                    List<String[]> start = openings.copy();
                    boolean moved = false;
                    for (String[] r : start) {
                        if (r.length > 3 && "advance".equals(col(r, 2)) && col(r, 3).trim().equalsIgnoreCase(old.name())) { r[3] = name; moved = true; }
                    }
                    if (moved) openings.save(start);
                }
                employees.replace(old.id(), row);
            }
            return new Employee(row[0], name, salary, schedule, day1, day2, row[6], row[7]);
        }
    }

    /** Takes someone off the payroll list. Their salary and advance records stay; refused while they owe advances. */
    Employee deleteEmployee(String id) {
        synchronized (lock) {
            Employee e = employees().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
            if (e == null) return null;
            double owed = advancesOwed().getOrDefault(e.name(), 0.0);
            if (owed > 0.005) throw new Conflict(e.name() + " still has ₱" + money(owed) + " in unpaid advances. Clear them first.");
            employees.remove(id);
            return e;
        }
    }

    /** The next pay day on or after from. */
    static LocalDate nextPayday(Employee e, LocalDate from) {
        if (e.schedule().equals("weekly")) {
            int diff = (e.day1() - from.getDayOfWeek().getValue() + 7) % 7;
            return from.plusDays(diff);
        }
        for (int m = 0; m < 3; m++) {
            java.time.YearMonth ym = java.time.YearMonth.from(from).plusMonths(m);
            List<Integer> days = e.schedule().equals("twice") ? List.of(e.day1(), e.day2()) : List.of(e.day1());
            for (int d : days) {
                LocalDate pay = ym.atDay(Math.min(d, ym.lengthOfMonth()));
                if (!pay.isBefore(from)) return pay;
            }
        }
        return from;
    }

    /**
     * Everyone on the payroll (plus names that only appear on salary or advance records, so older entries
     * show up to be set up): salary, schedule, next pay day, unpaid advances, what's left to hand over on
     * pay day (salary − advances), and their salary / advance / paid-back records, newest first.
     */
    Map<String, Object> payroll(LocalDate today) {
        synchronized (lock) {
            Map<String, Double> owed = advancesOwed();
            Map<String, List<Object>> history = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Map<String, String> lastPaid = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            List<Expense> all = expenses();
            for (int i = all.size() - 1; i >= 0; i--) {
                Expense e = all.get(i);
                if (!e.payroll() || e.employee().isBlank()) continue;
                history.computeIfAbsent(e.employee(), k -> new ArrayList<>()).add(Json.obj("id", e.id(), "time", e.rawTime(), "category", e.category(),
                        "amount", round(e.amount()), "advanceDeducted", round(e.advanceDeducted()), "method", e.method(), "recordedBy", e.recordedBy(), "note", e.note()));
                if (e.category().equals("salary")) lastPaid.putIfAbsent(e.employee(), e.rawTime());
            }
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            List<Object> rows = new ArrayList<>();
            double totalSalary = 0, totalAdvanced = 0;
            for (Employee e : employees()) {
                names.add(e.name());
                double adv = owed.getOrDefault(e.name(), 0.0);
                totalSalary += e.salary();
                totalAdvanced += adv;
                rows.add(Json.obj("id", e.id(), "name", e.name(), "salary", round(e.salary()), "schedule", e.schedule(), "day1", e.day1(), "day2", e.day2(),
                        "nextPayday", nextPayday(e, today).toString(), "advanced", round(adv), "balance", round(e.salary() - adv),
                        "lastPaid", lastPaid.get(e.name()), "note", e.note(), "history", history.getOrDefault(e.name(), List.of())));
            }
            // Names with records (or starting advances) but no payroll entry yet.
            Set<String> loose = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            loose.addAll(history.keySet());
            loose.addAll(owed.keySet());
            for (String n : loose) {
                if (names.contains(n)) continue;
                double adv = owed.getOrDefault(n, 0.0);
                totalAdvanced += adv;
                rows.add(Json.obj("id", null, "name", n, "salary", null, "schedule", null, "nextPayday", null, "advanced", round(adv), "balance", null,
                        "lastPaid", lastPaid.get(n), "history", history.getOrDefault(n, List.of())));
            }
            return Json.obj("employees", rows, "totalSalary", round(totalSalary), "totalAdvanced", round(totalAdvanced), "today", today.toString());
        }
    }

    /** Generic and other-brand empties taken in (on sales and tank returns), per tank size and brand, most first. */
    List<Object> looseEmpties() {
        synchronized (lock) {
            Map<String, Product> prods = productMap();
            Map<String, Object[]> by = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            java.util.function.BiConsumer<String[], Integer> add = (k, n) -> {
                Product p = prods.get(k[0]);
                double w = p == null ? 0 : p.weight();
                String label = looseLabel(k[1], k[2]);
                Object[] v = by.computeIfAbsent(String.format(Locale.ROOT, "%08.2f|%s", w, label.toLowerCase(Locale.ROOT)),
                        x -> new Object[]{w, label, "generic".equals(k[1]) ? "generic" : "other", 0});
                v[3] = (Integer) v[3] + n;
            };
            for (Line l : lines()) if (l.looseEmpty()) add.accept(new String[]{l.productId(), l.emptyKind(), l.emptyBrand()}, l.qty());
            for (TankReturn t : tankReturns()) if (t.looseEmpty()) add.accept(new String[]{t.productId(), t.emptyKind(), t.emptyBrand()}, t.qty());
            List<Object> out = new ArrayList<>();
            for (Object[] v : by.values()) out.add(Json.obj("weight", v[0], "label", v[1], "kind", v[2], "qty", v[3]));
            return out;
        }
    }

    /** Deletes an expense entered by mistake; returns it, or null when not found. */
    Expense deleteExpense(String id) {
        synchronized (lock) {
            Expense e = expenses().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
            if (e == null) return null;
            if ("advance".equals(e.category()) && advancesOwed().getOrDefault(e.employee(), 0.0) < e.amount() - 0.005) {
                throw new Conflict("Part of this advance was already taken off " + e.employee() + "'s salary or paid back. Delete that first.");
            }
            expenses.remove(id);
            return e;
        }
    }

    // ---------------------------------------------------------------- starting balances

    /** The receipt id a customer's starting balance is paid on, and the bill key of what a supplier was owed. */
    static String openingKey(String id) { return "O" + id; }

    /** The starting balances, or null before they're entered. */
    Opening opening() {
        synchronized (lock) {
            List<String[]> rows = openings.rows();
            String[] head = rows.stream().filter(r -> "start".equals(col(r, 2))).findFirst().orElse(null);
            if (head == null) return null;
            LocalDateTime start = parseTime(col(head, 1));
            if (start == null) return null;
            double cash = 0;
            Map<String, int[]> stock = new LinkedHashMap<>();
            Map<String, Map<String, Integer>> refiller = new LinkedHashMap<>(), tanks = new LinkedHashMap<>(), kept = new LinkedHashMap<>();
            Map<String, Double> money = new LinkedHashMap<>(), sup = new LinkedHashMap<>(), adv = new LinkedHashMap<>();
            for (String[] r : rows) {
                String ref = col(r, 3), pid = col(r, 4);
                int qty = (int) num(col(r, 5), 0);
                double amount = round(num(col(r, 6), 0));
                switch (col(r, 2)) {
                    case "cash" -> cash += amount;
                    case "loaded" -> stock.computeIfAbsent(pid, k -> new int[2])[0] += qty;
                    case "empty" -> stock.computeIfAbsent(pid, k -> new int[2])[1] += qty;
                    case "refiller" -> refiller.computeIfAbsent(ref, k -> new LinkedHashMap<>()).merge(pid, qty, Integer::sum);
                    case "customer-money" -> money.merge(ref, amount, Double::sum);
                    case "customer-tanks" -> tanks.computeIfAbsent(ref, k -> new LinkedHashMap<>()).merge(pid, qty, Integer::sum);
                    case "deposit" -> kept.computeIfAbsent(ref, k -> new LinkedHashMap<>()).merge(pid, qty, Integer::sum);
                    case "supplier" -> sup.merge(ref, amount, Double::sum);
                    case "advance" -> adv.merge(ref, amount, Double::sum);
                    default -> {
                    }
                }
            }
            return new Opening(start.toLocalDate(), round(cash), stock, refiller, money, tanks, kept, sup, adv, col(head, 8), col(head, 9));
        }
    }

    /** True when the starting balances mention this customer ("customer") or product ("product"). */
    private boolean inOpening(String what, String id) {
        for (String[] r : openings.rows()) {
            String kind = col(r, 2);
            if (what.equals("product") && col(r, 4).equals(id)) return true;
            if (what.equals("customer") && (kind.startsWith("customer-") || kind.equals("deposit")) && col(r, 3).equals(id)) return true;
        }
        return false;
    }

    /** What's still unpaid of each customer's starting balance. */
    Map<String, Double> openingOwed() {
        synchronized (lock) {
            Opening o = opening();
            if (o == null) return Map.of();
            Map<String, Double> left = new LinkedHashMap<>(o.customerMoney());
            for (Payment p : payments()) {
                if (p.receiptId().startsWith("O") && left.containsKey(p.receiptId().substring(1))) left.merge(p.receiptId().substring(1), -p.amount(), Double::sum);
            }
            left.replaceAll((k, v) -> round(Math.max(0, v)));
            left.values().removeIf(v -> v <= 0);
            return left;
        }
    }

    /** The day records start (the starting balances' date), or null before they're entered. */
    LocalDate startDate() {
        Opening o = opening();
        return o == null ? null : o.start();
    }

    /**
     * Saves the starting balances (replacing earlier ones). Tanks at a refiller go on a start trip per supplier,
     * so they're received like any other trip. Refused when nothing would add up any more: records dated before
     * the start, or less than what has since been paid, sold, given back or received.
     */
    void saveOpening(Opening o, String staff) {
        synchronized (lock) {
            LocalDateTime first = firstRecord();
            if (first != null && first.toLocalDate().isBefore(o.start())) {
                throw new Conflict("Records go back to " + first.toLocalDate() + ". The start date can't be after that.");
            }
            List<String[]> oldOpenings = openings.copy(), oldRefills = refills.copy();
            String at = o.time().format(TIME), savedAt = LocalDateTime.now().withNano(0).format(TIME);
            // One start trip per supplier with tanks there; earlier ones are reused so receipts against them stay linked.
            List<String[]> trips = refills.copy();
            Map<String, String> tripOf = new HashMap<>();
            for (String[] r : trips) if ("1".equals(col(r, 5))) tripOf.putIfAbsent(col(r, 2), col(r, 0));
            Set<String> used = new HashSet<>();
            List<String[]> rows = new ArrayList<>();
            int[] id = {1};
            java.util.function.Consumer<String[]> add = r -> {
                String[] row = new String[10];
                row[0] = String.valueOf(id[0]++);
                row[1] = at;
                for (int i = 0; i < 8; i++) row[i + 2] = r.length > i ? r[i] : "";
                rows.add(row);
            };
            add.accept(new String[]{"start", "", "", "", "", "", staff, savedAt});
            if (o.cash() > 0) add.accept(new String[]{"cash", "", "", "", money(o.cash())});
            o.stock().forEach((pid, v) -> {
                if (v[0] > 0) add.accept(new String[]{"loaded", "", pid, String.valueOf(v[0])});
                if (v[1] > 0) add.accept(new String[]{"empty", "", pid, String.valueOf(v[1])});
            });
            for (var e : o.atRefiller().entrySet()) {
                if (e.getValue().values().stream().mapToInt(Integer::intValue).sum() <= 0) continue;
                String trip = tripOf.get(e.getKey());
                if (trip == null) {
                    trip = nextId(trips);
                    trips.add(new String[]{trip, at, e.getKey(), staff, "Already at the refiller on " + o.start(), "1"});
                    tripOf.put(e.getKey(), trip);
                }
                used.add(trip);
                for (var t : e.getValue().entrySet()) {
                    if (t.getValue() > 0) add.accept(new String[]{"refiller", e.getKey(), t.getKey(), String.valueOf(t.getValue()), "", trip});
                }
            }
            o.customerMoney().forEach((cid, v) -> { if (v > 0) add.accept(new String[]{"customer-money", cid, "", "", money(v)}); });
            o.customerTanks().forEach((cid, m) -> m.forEach((pid, n) -> { if (n > 0) add.accept(new String[]{"customer-tanks", cid, pid, String.valueOf(n)}); }));
            o.deposits().forEach((cid, m) -> m.forEach((pid, n) -> { if (n > 0) add.accept(new String[]{"deposit", cid, pid, String.valueOf(n)}); }));
            o.suppliers().forEach((sid, v) -> { if (v > 0) add.accept(new String[]{"supplier", sid, "", "", money(v)}); });
            o.advances().forEach((name, v) -> { if (v > 0) add.accept(new String[]{"advance", name, "", "", money(v)}); });
            // Start trips no longer needed go, unless something was already received on them (checked below).
            Set<String> referenced = new HashSet<>();
            for (Movement m : movements()) referenced.add(m.refillId());
            for (SupplierPayment sp : supplierPayments()) referenced.add(sp.refillId());
            trips.removeIf(r -> "1".equals(col(r, 5)) && !used.contains(col(r, 0)) && !referenced.contains(col(r, 0)));
            refills.save(trips);
            openings.save(rows);
            String problem = openingProblem();
            if (problem != null) {
                openings.save(oldOpenings);
                refills.save(oldRefills);
                throw new Conflict(problem);
            }
        }
    }

    /** Why the records no longer add up (checked after the starting balances change), or null. */
    private String openingProblem() {
        Map<String, Product> prods = productMap();
        Function<String, String> label = pid -> prods.containsKey(pid) ? prods.get(pid).label() : "product #" + pid;
        Function<String, String> who = cid -> {
            Customer c = customer(cid);
            return c == null ? "customer #" + cid : c.name();
        };
        for (var e : stock().entrySet()) {
            Stock s = e.getValue();
            if (s.loaded() < 0) return "Too few " + label.apply(e.getKey()) + " with load: " + -s.loaded() + " more were sold or moved since the start.";
            if (s.empty() < 0) return "Too few empty " + label.apply(e.getKey()) + ": " + -s.empty() + " more were sent or moved since the start.";
            if (s.atRefiller() < 0) return "Too few " + label.apply(e.getKey()) + " at the refiller: " + -s.atRefiller() + " more already came back.";
        }
        for (Refill rf : refills()) {
            if (!rf.start()) continue;
            for (RefillItem i : refillItems(rf.id())) {
                if (i.outstanding() < 0) return "More " + label.apply(i.productId()) + " already came back from that refiller than you entered.";
            }
        }
        Opening o = opening();
        for (Customer c : customers()) if (owedCount(c.id()) < 0) return who.apply(c.id()) + " has already returned more tanks than that.";
        for (var e : depositsHeldRaw().entrySet()) {
            for (var t : e.getValue().entrySet()) {
                if (t.getValue() < 0) return "More of " + who.apply(e.getKey()) + "'s kept " + label.apply(t.getKey()) + " tanks were already given back or used.";
            }
        }
        Map<String, Double> paidStart = new HashMap<>();
        for (Payment p : payments()) if (p.receiptId().startsWith("O")) paidStart.merge(p.receiptId().substring(1), p.amount(), Double::sum);
        for (var e : paidStart.entrySet()) {
            double had = o == null ? 0 : o.customerMoney().getOrDefault(e.getKey(), 0.0);
            if (e.getValue() > had + 0.005) return who.apply(e.getKey()) + " has already paid ₱" + money(e.getValue()) + " on the starting balance.";
        }
        for (Bill b : bills()) {
            if (b.paid() > b.cost() + 0.005 && "start".equals(b.kind())) {
                Supplier s = supplier(b.supplierId());
                return "₱" + money(b.paid()) + " was already paid to " + (s == null ? "that supplier" : s.name()) + " on the starting balance.";
            }
        }
        for (SupplierPayment sp : supplierPayments()) {
            if (sp.refillId().startsWith("O") && (o == null || !o.suppliers().containsKey(sp.refillId().substring(1)))) {
                return "A payment was already made on a supplier's starting balance, so it can't be removed.";
            }
        }
        Map<String, Double> adv = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (o != null) o.advances().forEach((k, v) -> adv.merge(k, v, Double::sum));
        for (Expense e : expenses()) if (e.advanceEffect() != 0) adv.merge(e.employee(), e.advanceEffect(), Double::sum);
        for (var e : adv.entrySet()) if (e.getValue() < -0.005) return "More of " + e.getKey() + "'s advances were already taken off a salary.";
        return null;
    }

    /** When the earliest record (other than the starting balances) was made, or null when there are none. */
    LocalDateTime firstRecord() {
        synchronized (lock) {
            List<LocalDateTime> times = new ArrayList<>();
            receipts().forEach(r -> times.add(r.time()));
            payments().forEach(p -> times.add(p.time()));
            movements().forEach(m -> times.add(m.time()));
            refills().stream().filter(r -> !r.start()).forEach(r -> times.add(r.time()));
            supplierPayments().forEach(p -> times.add(p.time()));
            tankReturns().forEach(t -> times.add(t.time()));
            paybacks().forEach(p -> times.add(p.time()));
            expenses().forEach(e -> times.add(e.time()));
            deposits().forEach(d -> times.add(d.time()));
            return times.stream().filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        }
    }

    // ---------------------------------------------------------------- customers' tanks kept at the store

    List<Deposit> deposits() {
        synchronized (lock) {
            List<Deposit> out = new ArrayList<>();
            for (String[] r : deposits.rows()) {
                out.add(new Deposit(col(r, 0), col(r, 1), parseTime(col(r, 1)), col(r, 2), col(r, 3), (int) Math.max(1, num(col(r, 4), 1)),
                        col(r, 5), col(r, 6), col(r, 7), col(r, 8)));
            }
            return out;
        }
    }

    /** Customers' own tanks the store keeps, per customer and product (starting balance + left − given back − used). */
    Map<String, Map<String, Integer>> depositsHeld() {
        Map<String, Map<String, Integer>> out = depositsHeldRaw();
        out.values().forEach(m -> m.values().removeIf(n -> n <= 0));
        out.values().removeIf(Map::isEmpty);
        return out;
    }

    private Map<String, Map<String, Integer>> depositsHeldRaw() {
        synchronized (lock) {
            Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
            Opening o = opening();
            if (o != null) o.deposits().forEach((cid, m) -> m.forEach((pid, n) -> out.computeIfAbsent(cid, k -> new LinkedHashMap<>()).merge(pid, n, Integer::sum)));
            for (Deposit d : deposits()) {
                out.computeIfAbsent(d.customerId(), k -> new LinkedHashMap<>()).merge(d.productId(), "in".equals(d.kind()) ? d.qty() : -d.qty(), Integer::sum);
            }
            return out;
        }
    }

    /** A customer leaves their own tanks with the store (kind "in"), or takes them back ("back"). */
    Deposit addDeposit(Customer c, Product p, int qty, String kind, String staff, String note) {
        synchronized (lock) {
            if (!kind.equals("in") && !kind.equals("back")) throw new Conflict("Choose whether the tank is left or given back.");
            if (kind.equals("back")) {
                int held = depositsHeld().getOrDefault(c.id(), Map.of()).getOrDefault(p.id(), 0);
                if (held <= 0) throw new Conflict("The store doesn't keep any of " + c.name() + "'s " + p.label() + " tanks.");
                if (qty > held) throw new Conflict("The store keeps only " + held + " of " + c.name() + "'s " + p.label() + " tanks.");
            }
            List<String[]> rows = deposits.copy();
            String id = nextId(rows);
            String time = now();
            rows.add(new String[]{id, time, c.id(), p.id(), String.valueOf(qty), kind, staff, note == null ? "" : note, ""});
            deposits.save(rows);
            return new Deposit(id, time, parseTime(time), c.id(), p.id(), qty, kind, staff, note, "");
        }
    }

    // ---------------------------------------------------------------- undoing a record entered by mistake

    /** What an undo took out: a sentence for staff and the activity log, the staff and time of the record, and the rows removed. */
    record Undone(String what, String staff, LocalDateTime time, String rows) {}

    static final List<String> UNDO_KINDS = List.of("refill", "receive", "movement", "payment", "return", "deposit", "supplier-payment", "payback");

    /**
     * Who recorded something and when, so the API can check who may undo it, or null when it doesn't exist.
     * kind: one of UNDO_KINDS; id: the record's id (a trip id for "refill"; any of the trip's receive movements for "receive").
     */
    Undone undoTarget(String kind, String id) {
        synchronized (lock) {
            String[] row = switch (kind) {
                case "refill" -> find(refills, id);
                case "receive", "movement" -> find(movements, id);
                case "payment" -> find(payments, id);
                case "return" -> find(tankReturns, id);
                case "deposit" -> find(deposits, id);
                case "supplier-payment" -> find(supplierPayments, id);
                case "payback" -> find(paybacks, id);
                default -> null;
            };
            if (row == null) return null;
            int staffCol = switch (kind) {
                case "refill" -> 3;
                case "receive", "movement", "supplier-payment", "deposit" -> 6;
                case "payment", "return" -> 5;
                case "payback" -> 4;
                default -> 0;
            };
            return new Undone("", col(row, staffCol), parseTime(col(row, 1)), "");
        }
    }

    /**
     * Takes out a record entered by mistake, with everything that belongs to it, as if it had never been entered:
     * a refill trip (its stock changes and payments), one receiving of a trip, a stock change (a purchase with its
     * payments), a customer payment (every part of it), a tank return, a kept-tank record, a supplier payment (every
     * bill it settled) or a staff payback. Refused, with nothing changed, when later records depend on it (the
     * tanks were already sold, a payment would be more than the bill, ...).
     */
    Undone undo(String kind, String id) {
        return undo(kind, id, true);
    }

    /** check false: leave checking that the records add up to the caller (an edit checks once the new record is in). */
    private Undone undo(String kind, String id, boolean check) {
        synchronized (lock) {
            Map<Table, List<String[]>> before = new LinkedHashMap<>();
            for (Table t : List.of(refills, movements, payments, tankReturns, deposits, supplierPayments, paybacks)) before.put(t, t.copy());
            String problemBefore = undoProblem();
            Map<String, Product> prods = productMap();
            Function<String, String> label = pid -> prods.containsKey(pid) ? prods.get(pid).label() : "product #" + pid;
            StringBuilder removed = new StringBuilder();
            String what;
            String staff;
            LocalDateTime time;
            switch (kind) {
                case "refill" -> {
                    String[] trip = find(refills, id);
                    if (trip == null) throw new Conflict("That refill trip is no longer there.");
                    if ("1".equals(col(trip, 5))) throw new Conflict("These are the tanks at the refiller on the start date. Change them in Dashboard → Starting balances.");
                    Supplier s = supplier(col(trip, 2));
                    List<String> parts = new ArrayList<>();
                    for (RefillItem i : refillItems(id)) parts.add(i.sent() + " × " + label.apply(i.productId()));
                    double paid = refillPaid(id);
                    what = "Refill trip #" + id + " to " + (s == null ? "the refiller" : s.name()) + " (" + String.join(", ", parts) + ")"
                            + (paid > 0 ? " and its ₱" + money(paid) + " in payments" : "");
                    staff = col(trip, 3);
                    time = parseTime(col(trip, 1));
                    take(refills, r -> col(r, 0).equals(id), removed);
                    take(movements, r -> col(r, 10).equals(id), removed);
                    take(supplierPayments, r -> col(r, 3).equals(id), removed);
                }
                case "receive" -> {
                    String[] mv = find(movements, id);
                    if (mv == null) throw new Conflict("That record is no longer there.");
                    String trip = col(mv, 10), at = col(mv, 1);
                    if (trip.isEmpty() || !List.of("refill-receive", "refill-reject").contains(col(mv, 3))) throw new Conflict("That isn't tanks received from a refiller.");
                    Predicate<String[]> same = r -> col(r, 10).equals(trip) && col(r, 1).equals(at) && List.of("refill-receive", "refill-reject").contains(col(r, 3));
                    List<String> parts = new ArrayList<>();
                    for (String[] r : movements.rows()) {
                        if (same.test(r)) parts.add((int) Math.abs(num(col(r, 9), 0)) + " × " + label.apply(col(r, 2)) + ("refill-reject".equals(col(r, 3)) ? " unfilled" : ""));
                    }
                    Predicate<String[]> paidThen = r -> col(r, 3).equals(trip) && col(r, 1).equals(at) && col(r, 6).equals(col(mv, 6));
                    double paid = supplierPayments.rows().stream().filter(paidThen).mapToDouble(r -> num(col(r, 5), 0)).sum();
                    what = "Receiving " + String.join(", ", parts) + " on trip #" + trip + (paid > 0 ? " and the ₱" + money(paid) + " paid then" : "")
                            + ". The tanks are at the refiller again";
                    staff = col(mv, 6);
                    time = parseTime(at);
                    take(movements, same, removed);
                    take(supplierPayments, paidThen, removed);
                }
                case "movement" -> {
                    String[] mv = find(movements, id);
                    if (mv == null) throw new Conflict("That stock change is no longer there.");
                    String type = col(mv, 3);
                    if (type.startsWith("refill-")) throw new Conflict("This is part of refill trip #" + col(mv, 10) + ". Undo the trip instead.");
                    int loaded = (int) num(col(mv, 4), 0), empty = (int) num(col(mv, 5), 0);
                    what = switch (type) {
                        case "purchase" -> loaded + " new " + label.apply(col(mv, 2)) + " bought";
                        case "count" -> "Stock count of " + label.apply(col(mv, 2));
                        case "damaged" -> "Write-off of " + label.apply(col(mv, 2));
                        default -> "Stock change to " + label.apply(col(mv, 2));
                    } + " (" + signed(loaded) + " with load, " + signed(empty) + " empty)";
                    staff = col(mv, 6);
                    time = parseTime(col(mv, 1));
                    take(movements, r -> col(r, 0).equals(id), removed);
                    if ("purchase".equals(type)) take(supplierPayments, r -> col(r, 3).equals("P" + id), removed);
                }
                case "payment" -> {
                    String[] p = find(payments, id);
                    if (p == null) throw new Conflict("That payment is no longer there.");
                    // One payment can be split over several receipts: take out every part of it.
                    Predicate<String[]> same = r -> col(r, 1).equals(col(p, 1)) && col(r, 3).equals(col(p, 3)) && col(r, 5).equals(col(p, 5))
                            && col(r, 6).equals(col(p, 6)) && col(r, 7).equals(col(p, 7));
                    double amount = payments.rows().stream().filter(same).mapToDouble(r -> num(col(r, 4), 0)).sum();
                    Customer c = customer(col(p, 3));
                    what = "Payment of ₱" + money(amount) + " from " + (c == null ? "customer #" + col(p, 3) : c.name());
                    staff = col(p, 5);
                    time = parseTime(col(p, 1));
                    take(payments, same, removed);
                }
                case "return" -> {
                    String[] t = find(tankReturns, id);
                    if (t == null) throw new Conflict("That tank return is no longer there.");
                    Customer c = customer(col(t, 2));
                    what = (c == null ? "Customer #" + col(t, 2) : c.name()) + " returning " + col(t, 4) + " × " + label.apply(col(t, 3));
                    staff = col(t, 5);
                    time = parseTime(col(t, 1));
                    take(tankReturns, r -> col(r, 0).equals(id), removed);
                }
                case "deposit" -> {
                    String[] d = find(deposits, id);
                    if (d == null) throw new Conflict("That record is no longer there.");
                    if ("used".equals(col(d, 5))) throw new Conflict("These kept tanks were the empties on receipt #" + col(d, 8) + ". Edit or void that receipt instead.");
                    Customer c = customer(col(d, 2));
                    String name = c == null ? "customer #" + col(d, 2) : c.name();
                    what = ("back".equals(col(d, 5)) ? "Giving back " + col(d, 4) + " of " + name + "'s own " : name + " leaving " + col(d, 4) + " own ") + label.apply(col(d, 3)) + " tanks";
                    staff = col(d, 6);
                    time = parseTime(col(d, 1));
                    take(deposits, r -> col(r, 0).equals(id), removed);
                }
                case "supplier-payment" -> {
                    String[] p = find(supplierPayments, id);
                    if (p == null) throw new Conflict("That payment is no longer there.");
                    // One payment can settle several bills from several sources: take out every part of it.
                    Predicate<String[]> same = r -> col(r, 1).equals(col(p, 1)) && col(r, 2).equals(col(p, 2)) && col(r, 6).equals(col(p, 6))
                            && col(r, 7).equals(col(p, 7)) && col(r, 10).equals(col(p, 10));
                    double amount = supplierPayments.rows().stream().filter(same).mapToDouble(r -> num(col(r, 5), 0)).sum();
                    Supplier s = supplier(col(p, 2));
                    what = "Payment of ₱" + money(amount) + " to " + (s == null ? "a supplier" : s.name());
                    staff = col(p, 6);
                    time = parseTime(col(p, 1));
                    take(supplierPayments, same, removed);
                }
                case "payback" -> {
                    String[] p = find(paybacks, id);
                    if (p == null) throw new Conflict("That payback is no longer there.");
                    what = "Paying back ₱" + money(num(col(p, 3), 0)) + " to " + col(p, 2);
                    staff = col(p, 4);
                    time = parseTime(col(p, 1));
                    take(paybacks, r -> col(r, 0).equals(id), removed);
                }
                default -> throw new Conflict("That can't be undone here.");
            }
            String problem = check ? undoProblem() : null;
            if (problem != null && !problem.equals(problemBefore)) {
                before.forEach((t, rows) -> { if (t.rows().size() != rows.size()) t.save(rows); });
                throw new Conflict("Can't undo this. " + problem + " Undo or correct those later records first.");
            }
            return new Undone(what, staff, time, removed.toString().trim());
        }
    }

    /**
     * Corrects a record: takes it out (as undo does) and enters the corrected one with create, as one change. If
     * create is refused, or the records no longer add up afterwards, everything is put back as it was.
     */
    <T> T replacing(String kind, String id, java.util.concurrent.Callable<T> create) throws Exception {
        synchronized (lock) {
            if (undoTarget(kind, id) == null) throw new Conflict("That record is no longer there. Someone may have undone or changed it already.");
            Map<Table, List<String[]>> before = new LinkedHashMap<>();
            for (Table t : all) if (t != users) before.put(t, t.copy());
            String problemBefore = undoProblem();
            Runnable restore = () -> before.forEach((t, rows) -> { if (!sameRows(t.rows(), rows)) t.save(rows); });
            try {
                undo(kind, id, false);
                T out = create.call();
                String problem = undoProblem();
                if (problem != null && !problem.equals(problemBefore)) throw new Conflict("Can't save this change. " + problem);
                return out;
            } catch (Exception | Error e) {
                restore.run();
                throw e;
            }
        }
    }

    private static boolean sameRows(List<String[]> a, List<String[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    private static String signed(int n) { return (n > 0 ? "+" : "") + n; }

    private static String[] find(Table t, String id) {
        for (String[] r : t.rows()) if (col(r, 0).equals(id)) return r;
        return null;
    }

    /** Removes the matching rows, noting them (file name and CSV) for the activity log. */
    private static void take(Table t, Predicate<String[]> match, StringBuilder removed) {
        List<String[]> gone = t.rows().stream().filter(match).toList();
        if (gone.isEmpty()) return;
        removed.append(t.path.getFileName()).append(": ").append(Csv.format(gone).replace("\r", "").replace('\n', '|')).append(' ');
        t.removeWhere(match);
    }

    /** Why the records don't add up, or null: what the starting balances check, plus money paid to staff and suppliers. */
    private String undoProblem() {
        Map<String, Product> prods = productMap();
        for (var e : stock().entrySet()) {
            String name = prods.containsKey(e.getKey()) ? prods.get(e.getKey()).label() : "product #" + e.getKey();
            Stock s = e.getValue();
            if (s.loaded() < 0) return -s.loaded() + " of these " + name + " with load were already sold or moved.";
            if (s.empty() < 0) return -s.empty() + " of these empty " + name + " were already sent out or moved.";
            if (s.atRefiller() < 0) return -s.atRefiller() + " of these " + name + " already came back from the refiller.";
        }
        String p = openingProblem();
        if (p != null) return p;
        for (var e : owedToStaff().entrySet()) {
            if (e.getValue() < -0.005) return e.getKey() + " was already paid back ₱" + money(-e.getValue()) + " more than they put in.";
        }
        for (Bill b : bills()) {
            if (b.paid() > b.cost() + 0.005 && !"start".equals(b.kind())) {
                return "₱" + money(b.paid()) + " was paid on " + b.label().toLowerCase(Locale.ROOT) + ", which would then cost only ₱" + money(b.cost()) + ".";
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- web users

    List<User> users() {
        synchronized (lock) {
            List<User> out = new ArrayList<>();
            for (String[] r : users.rows()) out.add(new User(col(r, 0), col(r, 1), col(r, 2), col(r, 3), "1".equals(col(r, 4))));
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
            rows.add(new String[]{username, salt, hash, now(), ""});
            users.save(rows);
            return true;
        }
    }

    boolean setPassword(String username, String salt, String hash) {
        synchronized (lock) {
            User u = user(username);
            return u != null && users.replace(username, new String[]{username, salt, hash, u.created(), u.dashboard() ? "1" : ""});
        }
    }

    /** Lets a staff member see (or stops them seeing) the summary dashboard in the POS. */
    boolean setDashboard(String username, boolean on) {
        synchronized (lock) {
            User u = user(username);
            return u != null && users.replace(username, new String[]{username, u.salt(), u.hash(), u.created(), on ? "1" : ""});
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
        LocalDateTime t = LocalDateTime.now().withNano(0);
        LocalDate day = RECORD_DATE.get();
        if (day != null && !day.equals(t.toLocalDate())) t = day.atTime(t.toLocalTime());
        return t.format(TIME);
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

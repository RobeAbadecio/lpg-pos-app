import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * CSV-backed storage shared with the desktop Swing app (same folder, same column order).
 *
 * Customers.csv       id,firstName,lastName,contactNo,address
 * LPGs.csv            id,brand,price,weight[,reorderLevel]
 * Transactions.csv    id,customerId,lpgId,dateTime[,qty,unitPrice,staff,returnedEmpty]
 * StockMovements.csv  id,dateTime,lpgId,type,fullDelta,emptyDelta,staff,note
 *
 * Trailing columns are web-only. The Swing app reads the leading ones and ignores the rest;
 * when they are missing we fall back to qty 1 at the product's current price (what the Swing
 * app shows), a reorder level of 5, and an empty cylinder taken back on each sale.
 *
 * Stock is never stored as a number: full and empty cylinders on hand are the sum of the
 * stock movements (deliveries, counts, write-offs) minus/plus every sale. Editing or deleting
 * a sale therefore corrects the stock automatically.
 *
 * Files are re-read whenever they change on disk, so edits made in the desktop app show
 * up on the web without a restart.
 */
final class DataStore {
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    record Customer(String id, String firstName, String lastName, String contact, String address) {
        String name() { return (firstName + " " + lastName).trim(); }
    }

    record Product(String id, String brand, double price, double weight, int reorderLevel) {
        String label() { return brand + " " + fmtKg(weight) + " kg"; }
    }

    /**
     * unitPrice is null for rows written by the desktop app (priced from the product).
     * returnedEmpty: the customer handed back an empty cylinder (a refill swap) rather than
     * buying a new tank.
     */
    record Txn(String id, String customerId, String productId, String rawTime, LocalDateTime time,
               int qty, Double unitPrice, String staff, boolean returnedEmpty) {}

    /** type: delivery | count | damaged | other */
    record Movement(String id, String rawTime, LocalDateTime time, String productId, String type,
                    int fullDelta, int emptyDelta, String staff, String note) {}

    record Stock(int full, int empty) {}

    /** A sale or stock change that would leave fewer than zero cylinders on hand. */
    static final class StockException extends RuntimeException {
        StockException(String message) {
            super(message);
        }
    }

    record User(String username, String salt, String hash, String created) {}

    private final Path dir;
    private final Object lock = new Object();
    private final Table customers, products, transactions, users, movements;

    DataStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        customers = new Table("Customers.csv");
        products = new Table("LPGs.csv");
        transactions = new Table("Transactions.csv");
        users = new Table("WebUsers.csv");
        movements = new Table("StockMovements.csv");
    }

    Path dir() { return dir; }

    // ---------------------------------------------------------------- customers

    List<Customer> customers() {
        synchronized (lock) {
            List<Customer> out = new ArrayList<>();
            for (String[] r : customers.rows()) {
                out.add(new Customer(col(r, 0), col(r, 1), col(r, 2), col(r, 3), col(r, 4)));
            }
            return out;
        }
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

    /** Returns the number of transactions blocking deletion, 0 when deleted, -1 when not found. */
    int deleteCustomer(String id) {
        synchronized (lock) {
            long used = transactions().stream().filter(t -> t.customerId().equals(id)).count();
            if (used > 0) return (int) used;
            return customers.remove(id) ? 0 : -1;
        }
    }

    // ---------------------------------------------------------------- products

    List<Product> products() {
        synchronized (lock) {
            List<Product> out = new ArrayList<>();
            for (String[] r : products.rows()) {
                out.add(new Product(col(r, 0), col(r, 1), num(col(r, 2), 0), num(col(r, 3), 0), (int) num(col(r, 4), 5)));
            }
            return out;
        }
    }

    Product addProduct(String brand, double price, double weight, int reorderLevel) {
        synchronized (lock) {
            List<String[]> rows = products.copy();
            String id = nextId(rows);
            rows.add(new String[]{id, brand, money(price), fmtKg(weight), String.valueOf(reorderLevel)});
            products.save(rows);
            return new Product(id, brand, price, weight, reorderLevel);
        }
    }

    boolean updateProduct(String id, String brand, double price, double weight, int reorderLevel) {
        synchronized (lock) {
            return products.replace(id, new String[]{id, brand, money(price), fmtKg(weight), String.valueOf(reorderLevel)});
        }
    }

    /**
     * Products with sales can't be deleted. A deleted product's stock movements go with it, so a
     * product added later under the same id doesn't inherit its stock.
     */
    int deleteProduct(String id) {
        synchronized (lock) {
            long used = transactions().stream().filter(t -> t.productId().equals(id)).count();
            if (used > 0) return (int) used;
            if (!products.remove(id)) return -1;
            movements.removeWhere(r -> col(r, 2).equals(id));
            return 0;
        }
    }

    // ---------------------------------------------------------------- transactions

    List<Txn> transactions() {
        synchronized (lock) {
            List<Txn> out = new ArrayList<>();
            for (String[] r : transactions.rows()) {
                if (r.length < 4) continue; // same guard as TransactionPanel
                String raw = col(r, 3);
                LocalDateTime time = null;
                try {
                    time = LocalDateTime.parse(raw, TIME);
                } catch (DateTimeParseException ignored) {
                    // keep the row; it just won't land in a dated bucket
                }
                int qty = (int) Math.max(1, num(col(r, 4), 1));
                Double unit = col(r, 5).isEmpty() ? null : num(col(r, 5), 0);
                out.add(new Txn(col(r, 0), col(r, 1), col(r, 2), raw, time, qty, unit, col(r, 6), !"0".equals(col(r, 7))));
            }
            return out;
        }
    }

    Txn addTransaction(String customerId, Product product, int qty, String staff, boolean returnedEmpty) {
        synchronized (lock) {
            requireFull(product, qty, 0);
            List<String[]> rows = transactions.copy();
            String id = nextId(rows);
            LocalDateTime now = LocalDateTime.now().withNano(0);
            rows.add(txnRow(id, customerId, product.id(), now.format(TIME), qty, product.price(), staff, returnedEmpty));
            transactions.save(rows);
            return new Txn(id, customerId, product.id(), now.format(TIME), now, qty, product.price(), staff, returnedEmpty);
        }
    }

    /** Edits keep the original sale time and seller; the price is re-taken only if the product changed. */
    boolean updateTransaction(String id, String customerId, Product product, int qty, boolean returnedEmpty) {
        synchronized (lock) {
            Txn old = transactions().stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
            if (old == null) return false;
            boolean sameProduct = old.productId().equals(product.id());
            requireFull(product, qty, sameProduct ? old.qty() : 0);
            double unit = sameProduct && old.unitPrice() != null ? old.unitPrice() : product.price();
            return transactions.replace(id, txnRow(id, customerId, product.id(), old.rawTime(), qty, unit, old.staff(), returnedEmpty));
        }
    }

    /** Throws unless qty full cylinders are on hand (alreadyCounted: cylinders this sale already holds). */
    private void requireFull(Product product, int qty, int alreadyCounted) {
        int available = stock().getOrDefault(product.id(), new Stock(0, 0)).full() + alreadyCounted;
        if (qty > available) {
            throw new StockException(available <= 0
                    ? product.label() + " is out of stock. Record a delivery in Inventory first."
                    : "Only " + available + " × " + product.label() + " left in stock.");
        }
    }

    boolean deleteTransaction(String id) {
        synchronized (lock) {
            return transactions.remove(id);
        }
    }

    private static String[] txnRow(String id, String cust, String lpg, String time, int qty, double unit, String staff,
                                   boolean returnedEmpty) {
        return new String[]{id, cust, lpg, time, String.valueOf(qty), money(unit), staff == null ? "" : staff,
                returnedEmpty ? "1" : "0"};
    }

    /** Amount actually charged; desktop-app rows are priced at the product's current price. */
    static double amount(Txn t, Map<String, Product> products) {
        double unit = t.unitPrice() != null ? t.unitPrice()
                : Optional.ofNullable(products.get(t.productId())).map(Product::price).orElse(0.0);
        return unit * t.qty();
    }

    // ---------------------------------------------------------------- inventory

    List<Movement> movements() {
        synchronized (lock) {
            List<Movement> out = new ArrayList<>();
            for (String[] r : movements.rows()) {
                LocalDateTime time = null;
                try {
                    time = LocalDateTime.parse(col(r, 1), TIME);
                } catch (DateTimeParseException ignored) {
                }
                out.add(new Movement(col(r, 0), col(r, 1), time, col(r, 2), col(r, 3),
                        (int) num(col(r, 4), 0), (int) num(col(r, 5), 0), col(r, 6), col(r, 7)));
            }
            return out;
        }
    }

    /** Full and empty cylinders on hand per product id. */
    Map<String, Stock> stock() {
        synchronized (lock) {
            Map<String, int[]> sums = new HashMap<>();
            for (Movement m : movements()) {
                int[] s = sums.computeIfAbsent(m.productId(), k -> new int[2]);
                s[0] += m.fullDelta();
                s[1] += m.emptyDelta();
            }
            for (Txn t : transactions()) {
                int[] s = sums.computeIfAbsent(t.productId(), k -> new int[2]);
                s[0] -= t.qty();
                if (t.returnedEmpty()) s[1] += t.qty();
            }
            Map<String, Stock> out = new HashMap<>();
            sums.forEach((id, s) -> out.put(id, new Stock(s[0], s[1])));
            return out;
        }
    }

    /**
     * Sets the on-hand counts to what was physically counted, recording the difference. Read and
     * write happen under one lock so a sale landing at the same moment can't skew the delta.
     * Returns the counts before the change, or null when nothing changed.
     */
    Stock adjustTo(Product product, String type, int full, int empty, String staff, String note) {
        synchronized (lock) {
            Stock before = stock().getOrDefault(product.id(), new Stock(0, 0));
            if (before.full() == full && before.empty() == empty) return null;
            addMovement(product, type, full - before.full(), empty - before.empty(), staff, note);
            return before;
        }
    }

    /** Records a stock change; refuses one that would leave a negative count. */
    Movement addMovement(Product product, String type, int fullDelta, int emptyDelta, String staff, String note) {
        synchronized (lock) {
            Stock now = stock().getOrDefault(product.id(), new Stock(0, 0));
            if (now.full() + fullDelta < 0) throw new StockException("Only " + now.full() + " full cylinders on hand.");
            if (now.empty() + emptyDelta < 0) throw new StockException("Only " + now.empty() + " empty cylinders on hand.");
            List<String[]> rows = movements.copy();
            String id = nextId(rows);
            String time = LocalDateTime.now().withNano(0).format(TIME);
            rows.add(new String[]{id, time, product.id(), type, String.valueOf(fullDelta), String.valueOf(emptyDelta),
                    staff == null ? "" : staff, note == null ? "" : note});
            movements.save(rows);
            return new Movement(id, time, LocalDateTime.parse(time, TIME), product.id(), type, fullDelta, emptyDelta, staff, note);
        }
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
            rows.add(new String[]{username, salt, hash, LocalDateTime.now().withNano(0).format(TIME)});
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

    private static String nextId(List<String[]> rows) {
        int max = 0;
        for (String[] r : rows) {
            try {
                max = Math.max(max, Integer.parseInt(col(r, 0)));
            } catch (NumberFormatException ignored) {
            }
        }
        return String.valueOf(max + 1);
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

        boolean removeWhere(java.util.function.Predicate<String[]> match) {
            List<String[]> next = copy();
            boolean removed = next.removeIf(match);
            if (removed) save(next);
            return removed;
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

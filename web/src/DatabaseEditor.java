import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Business-record corrections, invoked only by the loopback admin API under the store lock. */
final class DatabaseEditor {
    static final Map<String, String> SCHEMAS = new LinkedHashMap<>();
    static {
        SCHEMAS.put("Customers", "id,firstName,lastName,contactNo,address");
        SCHEMAS.put("LPGs", "id,brand,price,weight,reorderLevel,tankPrice,refillCost,tankCost,swapFee");
        SCHEMAS.put("Transactions", "id,customerId,lpgId,dateTime,qty,unitPrice,staff,returnedEmpty,receiptId,emptyLpgId,remark,owesTank,swapFee");
        SCHEMAS.put("Receipts", "id,dateTime,customerId,staff,discount,paidAtSale,note");
        SCHEMAS.put("Payments", "id,dateTime,receiptId,customerId,amount,staff,note");
        SCHEMAS.put("StockMovements", "id,dateTime,lpgId,type,loadedDelta,emptyDelta,staff,note,damagedDelta,refillerDelta,refillId,unitCost");
        SCHEMAS.put("Suppliers", "id,name,contact,brand,note");
        SCHEMAS.put("Refills", "id,dateTime,supplierId,staff,note");
        SCHEMAS.put("SupplierPayments", "id,dateTime,supplierId,refillId,kind,amount,staff,note,source,paidBy");
        SCHEMAS.put("StaffPaybacks", "id,dateTime,staff,amount,recordedBy,note");
        SCHEMAS.put("TankReturns", "id,dateTime,customerId,lpgId,qty,staff,remark");
    }
    static String[] fields(String table) {
        String schema = SCHEMAS.get(table);
        if (schema == null) throw new Http.Error(404, "Unknown business table");
        return schema.split(",");
    }
    static Map<String, List<String[]>> snapshot(Path dir) throws IOException {
        Map<String, List<String[]>> out = new LinkedHashMap<>();
        for (String table : SCHEMAS.keySet()) {
            Path file = dir.resolve(table + ".csv");
            out.put(table, Files.exists(file) ? Csv.parse(Files.readString(file)) : new ArrayList<>());
        }
        return out;
    }
    static String revision(Map<String, List<String[]>> data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var e : data.entrySet()) {
                digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Csv.format(e.getValue()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static Map<String, Object> read(Path dir, String table) throws IOException {
        String[] names = fields(table);
        var data = snapshot(dir);
        return Json.obj("table", table, "fields", List.of(names), "rows", data.get(table).stream().map(Arrays::asList).toList(), "revision", revision(data));
    }
    static String value(String[] row, int i) { return i < row.length ? row[i] : ""; }
    static boolean validTime(String value) {
        try { java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT)); return true; }
        catch (java.time.format.DateTimeParseException e) { return false; }
    }
    static void validateRow(String table, String[] row) {
        String[] names = fields(table);
        if (row.length != names.length || row[0].isBlank() || row[0].length() > 80)
            throw new Http.Error(400, "A record ID and all table fields are required");
        for (int i = 0; i < names.length; i++) {
            String name = names[i], v = row[i];
            if (v.length() > 4000 || v.indexOf('\0') >= 0) throw new Http.Error(400, name + " is too long or contains invalid characters");
            if (name.equals("dateTime") && (v.isBlank() || !validTime(v)))
                throw new Http.Error(400, "Enter a valid date/time: yyyy-MM-dd HH:mm:ss");
            if (Set.of("returnedEmpty", "owesTank").contains(name) && !v.isEmpty() && !v.equals("0") && !v.equals("1"))
                throw new Http.Error(400, name + " must be 0, 1, or blank");
            if (name.equals("source") && !Set.of("", "sales", "staff").contains(v)) throw new Http.Error(400, "Source must be sales or staff");
            if (Set.of("price", "weight", "reorderLevel", "tankPrice", "refillCost", "tankCost", "swapFee", "qty", "unitPrice", "discount", "paidAtSale", "amount", "loadedDelta", "emptyDelta", "damagedDelta", "refillerDelta", "unitCost").contains(name)) {
                if (v.isEmpty()) {
                    if (Set.of("qty", "amount", "weight", "price").contains(name)) throw new Http.Error(400, name + " is required");
                    continue;
                }
                try {
                    double n = Double.parseDouble(v);
                    if (!Double.isFinite(n) || Math.abs(n) > 1_000_000_000 || (!name.endsWith("Delta") && n < 0)
                            || (Set.of("qty", "reorderLevel", "loadedDelta", "emptyDelta", "damagedDelta", "refillerDelta").contains(name) && n != Math.rint(n))
                            || (Set.of("qty", "weight", "amount").contains(name) && n <= 0)) throw new NumberFormatException();
                } catch (NumberFormatException e) { throw new Http.Error(400, "Invalid " + name); }
            }
        }
        if (table.equals("StockMovements") && !Set.of("count", "damaged", "other", "delivery", "purchase", "condition", "refill-send", "refill-receive", "refill-reject").contains(row[3]))
            throw new Http.Error(400, "Invalid stock movement type");
        if (table.equals("SupplierPayments") && !Set.of("refill", "purchase").contains(row[4])) throw new Http.Error(400, "Payment kind must be refill or purchase");
        if (table.equals("StaffPaybacks") && row[2].isBlank()) throw new Http.Error(400, "Staff name is required");
        if (table.equals("LPGs") && row[1].isBlank()) throw new Http.Error(400, "Brand is required");
        if (table.equals("Suppliers") && row[1].isBlank()) throw new Http.Error(400, "Supplier name is required");
        if (table.equals("SupplierPayments") && row[8].equals("staff") && row[9].isBlank()) throw new Http.Error(400, "Staff-funded payments need paidBy");
        if (table.equals("Transactions") && !row[9].isEmpty() && row[11].equals("1")) throw new Http.Error(400, "A sale cannot receive an empty and owe a tank at the same time");
    }
    static Set<String> ids(Map<String, List<String[]>> data, String table) {
        Set<String> ids = new HashSet<>();
        for (String[] row : data.get(table)) ids.add(value(row, 0));
        return ids;
    }
    static void ref(Map<String, Double> errors, String key, String id, Set<String> ids, boolean optional) {
        if ((!optional || !id.isEmpty()) && !ids.contains(id)) errors.put(key + " references missing #" + id, 1.0);
    }
    /** Existing inconsistencies may be repaired incrementally, but cannot be increased. */
    static Map<String, Double> problems(Map<String, List<String[]>> data) throws IOException {
        Map<String, Double> errors = new LinkedHashMap<>();
        Set<String> customers = ids(data, "Customers"), products = ids(data, "LPGs"), suppliers = ids(data, "Suppliers"), trips = ids(data, "Refills");
        Set<String> receipts = new HashSet<>();
        Map<String, String> receiptCustomer = new HashMap<>();
        for (String[] row : data.get("Transactions")) {
            String rid = value(row, 8).isEmpty() ? "S" + row[0] : row[8];
            receipts.add(rid);
            String previous = receiptCustomer.putIfAbsent(rid, value(row, 1));
            if (previous != null && !previous.equals(value(row, 1))) errors.put("Receipt #" + rid + " has mixed customers", 1.0);
        }
        for (var e : data.entrySet()) {
            String[] names = fields(e.getKey());
            Set<String> seen = new HashSet<>();
            for (String[] row : e.getValue()) {
                String key = e.getKey() + " #" + value(row, 0);
                if (value(row, 0).isBlank() || !seen.add(row[0])) errors.put(key + " has duplicate/blank ID", 1.0);
                for (int i = 1; i < names.length; i++) {
                    String v = value(row, i);
                    switch (names[i]) {
                        case "customerId" -> ref(errors, key, v, customers, false);
                        case "lpgId" -> ref(errors, key, v, products, false);
                        case "emptyLpgId" -> ref(errors, key, v, products, true);
                        case "supplierId" -> ref(errors, key, v, suppliers, false);
                        case "receiptId" -> ref(errors, key, v, receipts, e.getKey().equals("Transactions"));
                        case "refillId" -> {
                            if (!v.isEmpty()) {
                                if (e.getKey().equals("SupplierPayments") && v.startsWith("P")) ref(errors, key, v.substring(1), ids(data, "StockMovements"), false);
                                else ref(errors, key, v, trips, false);
                            }
                        }
                    }
                }
                if (e.getKey().equals("Receipts")) {
                    ref(errors, key, row[0], receipts, false);
                    if (!Objects.equals(value(row, 2), receiptCustomer.get(row[0]))) errors.put(key + " customer differs from sale", 1.0);
                }
                if (e.getKey().equals("Payments") && !Objects.equals(value(row, 3), receiptCustomer.get(value(row, 2)))) errors.put(key + " customer differs from receipt", 1.0);
            }
        }
        Path stage = Files.createTempDirectory("lpg-record-validation-");
        try {
            for (var e : data.entrySet()) Files.writeString(stage.resolve(e.getKey() + ".csv"), Csv.format(e.getValue()));
            DataStore trial = new DataStore(stage);
            for (var e : trial.stock().entrySet()) {
                DataStore.Stock s = e.getValue();
                String[] labels = {"loaded", "empty", "damaged", "at refiller"};
                int[] counts = {s.loaded(), s.empty(), s.damaged(), s.atRefiller()};
                for (int i = 0; i < counts.length; i++) if (counts[i] < 0) errors.put("Product #" + e.getKey() + " has negative " + labels[i] + " stock", (double) -counts[i]);
            }
            Map<String, Integer> owed = new HashMap<>();
            for (DataStore.Line l : trial.lines()) if (l.owesTank()) owed.merge(l.customerId(), l.qty(), Integer::sum);
            for (DataStore.TankReturn t : trial.tankReturns()) owed.merge(t.customerId(), -t.qty(), Integer::sum);
            owed.forEach((id, n) -> { if (n < 0) errors.put("Customer #" + id + " returned more tanks than owed", (double) -n); });
            var rs = trial.receipts(); var paid = trial.paidByReceipt(rs);
            for (DataStore.Receipt r : rs) {
                if (r.discount() > r.subtotal() + .005) errors.put("Receipt #" + r.id() + " discount exceeds subtotal", r.discount() - r.subtotal());
                double extra = paid.getOrDefault(r.id(), 0.0) - r.total();
                if (extra > .005) errors.put("Receipt #" + r.id() + " payments exceed total", extra);
            }
            for (DataStore.Bill bill : trial.bills()) {
                double extra = bill.paid() - bill.cost();
                if (extra > .005) errors.put("Supplier bill #" + bill.key() + " payments exceed cost", extra);
            }
            trial.owedToStaff().forEach((id, n) -> { if (n < -.005) errors.put("Staff " + id + " reimbursement exceeds advances", -n); });
        } finally {
            try (var files = Files.list(stage)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(stage);
        }
        return errors;
    }
    record Change(List<String[]> rows, String backup, String message) {}
    static Change prepare(Path dir, String table, String mode, String id, String revision, String[] row, String reason) throws IOException {
        fields(table);
        if (!Set.of("add", "edit", "delete").contains(mode)) throw new Http.Error(400, "Choose add, edit or delete");
        if (reason.isBlank() || reason.length() > 200) throw new Http.Error(400, "Enter a correction reason (up to 200 characters)");
        var before = snapshot(dir);
        if (!revision(before).equals(revision)) throw new Http.Error(409, "Records changed since you opened this table. Reload before saving.");
        var next = new ArrayList<>(before.get(table));
        int index = -1;
        for (int i = 0; i < next.size(); i++) if (value(next.get(i), 0).equals(id)) index = i;
        if (mode.equals("add")) {
            validateRow(table, row);
            if (next.stream().anyMatch(r -> r[0].equals(row[0]))) throw new Http.Error(409, "That ID already exists");
            next.add(row);
        } else {
            if (index < 0) throw new Http.Error(404, "Record no longer exists");
            if (mode.equals("delete")) next.remove(index);
            else {
                if (next.get(index).length > fields(table).length) throw new Http.Error(409, "This record has newer fields; update the editor before editing it");
                validateRow(table, row);
                if (!row[0].equals(id)) throw new Http.Error(400, "Existing record IDs cannot be changed");
                next.set(index, row);
            }
        }
        var after = new LinkedHashMap<>(before); after.put(table, next);
        var existing = problems(before);
        for (var e : problems(after).entrySet()) if (e.getValue() > existing.getOrDefault(e.getKey(), 0.0) + .005)
            throw new Http.Error(409, e.getKey() + ". Correct the related records first.");
        // Snapshot every business table before any write; credentials are excluded.
        Path backup = dir.resolve("admin-backups").resolve(UUID.randomUUID().toString());
        Files.createDirectories(backup);
        for (var e : before.entrySet()) Files.writeString(backup.resolve(e.getKey() + ".csv"), Csv.format(e.getValue()), StandardOpenOption.CREATE_NEW);
        String message = mode + " " + table + " #" + (mode.equals("add") ? row[0] : id) + ": " + reason;
        Files.writeString(backup.resolve("correction.txt"), DataStore.TIME.format(java.time.LocalDateTime.now()) + "\n" + message + "\n", StandardOpenOption.CREATE_NEW);
        return new Change(next, backup.toString(), message);
    }
}

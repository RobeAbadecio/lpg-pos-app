import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Platform data in SQLite (platform.db): businesses, sign-in accounts and the audit log. Each business's own records
 * (sales, stock, customers...) stay in its CSV folder, see Tenants.
 *
 * Accounts: one row per username, unique across all businesses (sign-in needs no store code). Roles "owner" (the
 * Business Admin, at most one active per business, enforced by an index) and "staff". The password hash of an
 * active account lives in its business's WebUsers.csv like before; a pending account keeps it here until approved.
 *
 * One connection, every method synchronized: there is one server process, and SQLite serialises writes anyway.
 */
final class Platform implements AutoCloseable {
    static final List<String> BUSINESS_STATUSES = List.of("pending", "active", "suspended", "deactivated", "rejected");
    static final List<String> ACCOUNT_STATUSES = List.of("pending", "active", "disabled");
    private static final SecureRandom RANDOM = new SecureRandom();

    record Business(long id, String code, String name, String contactName, String phone, String email, String address,
                    String status, String created, String decided) {}

    record Account(String username, long businessId, String role, String status, String fullName, String created,
                   String pendingSalt, String pendingHash) {
        boolean active() { return status.equals("active"); }
        boolean owner() { return role.equals("owner"); }
    }

    record Audit(long id, String time, String actor, Long businessId, String action, String detail) {}

    private static final String[] SCHEMA = {
            """
            CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE businesses (
              id INTEGER PRIMARY KEY,
              code TEXT NOT NULL UNIQUE,
              name TEXT NOT NULL CHECK (length(name) BETWEEN 2 AND 80),
              contact_name TEXT NOT NULL DEFAULT '',
              phone TEXT NOT NULL DEFAULT '',
              email TEXT NOT NULL DEFAULT '',
              address TEXT NOT NULL DEFAULT '',
              status TEXT NOT NULL CHECK (status IN ('pending','active','suspended','deactivated','rejected')),
              created TEXT NOT NULL,
              decided TEXT NOT NULL DEFAULT ''
            );
            CREATE TABLE accounts (
              username TEXT PRIMARY KEY COLLATE NOCASE CHECK (username = lower(username)),
              business_id INTEGER NOT NULL REFERENCES businesses(id),
              role TEXT NOT NULL CHECK (role IN ('owner','staff')),
              status TEXT NOT NULL CHECK (status IN ('pending','active','disabled')),
              full_name TEXT NOT NULL DEFAULT '',
              created TEXT NOT NULL,
              pending_salt TEXT NOT NULL DEFAULT '',
              pending_hash TEXT NOT NULL DEFAULT ''
            );
            CREATE INDEX accounts_business ON accounts(business_id);
            CREATE UNIQUE INDEX one_active_owner ON accounts(business_id) WHERE role = 'owner' AND status = 'active';
            CREATE TABLE audit (
              id INTEGER PRIMARY KEY,
              time TEXT NOT NULL,
              actor TEXT NOT NULL,
              business_id INTEGER,
              action TEXT NOT NULL,
              detail TEXT NOT NULL DEFAULT ''
            );
            CREATE INDEX audit_business ON audit(business_id, id);
            """,
    };

    private final Connection db;
    private final Path file;

    Platform(Path file) throws SQLException {
        this.file = file;
        db = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = FULL");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
        }
        migrate();
    }

    Path file() { return file; }

    /** Applies the schema steps not yet applied (PRAGMA user_version counts them), each in one transaction. */
    private synchronized void migrate() throws SQLException {
        int version;
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            version = rs.getInt(1);
        }
        for (int v = version; v < SCHEMA.length; v++) {
            final int next = v + 1;
            final String step = SCHEMA[v];
            tx(() -> {
                try (Statement st = db.createStatement()) {
                    for (String sql : step.split(";")) if (!sql.isBlank()) st.execute(sql);
                    st.execute("PRAGMA user_version = " + next);
                }
                return null;
            });
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        db.close();
    }

    // ---------------------------------------------------------------- settings

    synchronized String setting(String key) {
        return query("SELECT value FROM settings WHERE key = ?", rs -> rs.getString(1), key).stream().findFirst().orElse(null);
    }

    synchronized void setSetting(String key, String value) {
        update("INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value);
    }

    // ---------------------------------------------------------------- businesses

    synchronized List<Business> businesses() {
        return query("SELECT * FROM businesses ORDER BY id", Platform::business);
    }

    synchronized Business business(long id) {
        return query("SELECT * FROM businesses WHERE id = ?", Platform::business, id).stream().findFirst().orElse(null);
    }

    synchronized Business businessByCode(String code) {
        return query("SELECT * FROM businesses WHERE code = ?", Platform::business, code.toUpperCase(Locale.ROOT))
                .stream().findFirst().orElse(null);
    }

    /** A new business with its owner account, both pending until the platform approves them. Returns the business id. */
    synchronized long register(String name, String contactName, String phone, String email, String address,
                               String username, String salt, String hash, String actor) {
        return tx(() -> {
            if (accountRow(username) != null) throw new Http.Error(409, "That username is taken");
            long id = createBusiness(name, contactName, phone, email, address, "pending");
            insertAccount(username, id, "owner", "pending", contactName, salt, hash);
            audit(actor, id, "Registered business", name + " (owner " + username + ")");
            return id;
        });
    }

    /** A business created directly as active (used when migrating the original single-store data). */
    synchronized long createActive(String name, String actor) {
        return tx(() -> {
            long id = createBusiness(name, "", "", "", "", "active");
            audit(actor, id, "Created business", name);
            return id;
        });
    }

    private long createBusiness(String name, String contactName, String phone, String email, String address, String status) throws SQLException {
        String code;
        do code = newCode(); while (!query("SELECT 1 FROM businesses WHERE code = ?", rs -> 1, code).isEmpty());
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO businesses(code, name, contact_name, phone, email, address, status, created) "
                + "VALUES(?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, code, name, contactName, phone, email, address, status, now());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Approves, suspends, reactivates or deactivates a business. Approving a pending business also activates its
     * pending owner. Rejecting is only for pending businesses; it frees the owner's username.
     */
    synchronized void setBusinessStatus(long id, String status, String actor) {
        if (!BUSINESS_STATUSES.contains(status) || status.equals("pending")) throw new Http.Error(400, "Unknown status");
        tx(() -> {
            Business b = business(id);
            if (b == null) throw new Http.Error(404, "No such business");
            if (b.status().equals(status)) return null;
            if (status.equals("rejected") && !b.status().equals("pending")) throw new Http.Error(409, "Only a pending registration can be rejected");
            if (b.status().equals("rejected")) throw new Http.Error(409, "This registration was rejected");
            update("UPDATE businesses SET status = ?, decided = ? WHERE id = ?", status, now(), id);
            if (b.status().equals("pending") && status.equals("active")) {
                update("UPDATE accounts SET status = 'active' WHERE business_id = ? AND role = 'owner' AND status = 'pending'", id);
            }
            if (status.equals("rejected")) update("DELETE FROM accounts WHERE business_id = ? AND status = 'pending'", id);
            audit(actor, id, "Business " + label(b.status(), status), b.name());
            return null;
        });
    }

    private static String label(String from, String to) {
        return switch (to) {
            case "active" -> from.equals("pending") ? "approved" : "reactivated";
            case "rejected" -> "rejected";
            default -> to;
        };
    }

    synchronized void updateProfile(long id, String name, String contactName, String phone, String email, String address, String actor) {
        tx(() -> {
            if (update("UPDATE businesses SET name = ?, contact_name = ?, phone = ?, email = ?, address = ? WHERE id = ?",
                    name, contactName, phone, email, address, id) == 0) throw new Http.Error(404, "No such business");
            audit(actor, id, "Updated business profile", name);
            return null;
        });
    }

    // ---------------------------------------------------------------- accounts

    synchronized Account account(String username) {
        return accountRow(username.toLowerCase(Locale.ROOT));
    }

    private Account accountRow(String username) {
        return query("SELECT * FROM accounts WHERE username = ?", Platform::account, username).stream().findFirst().orElse(null);
    }

    synchronized List<Account> accounts(long businessId) {
        return query("SELECT * FROM accounts WHERE business_id = ? ORDER BY role = 'owner' DESC, status, username", Platform::account, businessId);
    }

    /** Adds a staff account (pending when they signed up themselves, active when their owner created it). */
    synchronized void addStaff(long businessId, String username, String fullName, String status, String salt, String hash, String actor) {
        tx(() -> {
            if (accountRow(username) != null) throw new Http.Error(409, "That username is taken");
            insertAccount(username, businessId, "staff", status, fullName, salt, hash);
            audit(actor, businessId, status.equals("pending") ? "Staff signed up" : "Created staff account", username);
            return null;
        });
    }

    /** Adds an account taken over from the single-store WebUsers.csv (already has its hash there). */
    synchronized void importAccount(long businessId, String username, String role, String actor) {
        tx(() -> {
            if (accountRow(username) != null) throw new Http.Error(409, "Username " + username + " exists twice");
            insertAccount(username, businessId, role, "active", "", "", "");
            audit(actor, businessId, "Imported account", username + " (" + role + ")");
            return null;
        });
    }

    private void insertAccount(String username, long businessId, String role, String status, String fullName, String salt, String hash) {
        update("INSERT INTO accounts(username, business_id, role, status, full_name, created, pending_salt, pending_hash) VALUES(?,?,?,?,?,?,?,?)",
                username, businessId, role, status, fullName, now(), salt, hash);
    }

    /**
     * Changes an account's status within its business. Owners can't be disabled here (the platform changes owners
     * with setOwner), and a disabled account can't be set back to pending.
     */
    synchronized Account setAccountStatus(long businessId, String username, String status, String actor) {
        if (!List.of("active", "disabled").contains(status)) throw new Http.Error(400, "Unknown status");
        return tx(() -> {
            Account a = accountRow(username);
            if (a == null || a.businessId() != businessId) throw new Http.Error(404, "No such account");
            if (a.owner()) throw new Http.Error(409, "The store owner's account can't be changed here");
            if (a.status().equals(status)) return a;
            update("UPDATE accounts SET status = ? WHERE username = ?", status, username);
            audit(actor, businessId, (a.status().equals("pending") ? "Approved staff " : status.equals("active") ? "Enabled staff " : "Disabled staff ") + username, "");
            return accountRow(username);
        });
    }

    /** Rejects a pending sign-up: the row goes, so the username is free again. */
    synchronized void rejectPending(long businessId, String username, String actor) {
        tx(() -> {
            Account a = accountRow(username);
            if (a == null || a.businessId() != businessId || !a.status().equals("pending") || a.owner()) throw new Http.Error(404, "No pending sign-up with that username");
            update("DELETE FROM accounts WHERE username = ?", username);
            audit(actor, businessId, "Rejected staff sign-up " + username, "");
            return null;
        });
    }

    /** Removes a staff account (not the owner). Its past records keep the username. */
    synchronized void deleteAccount(long businessId, String username, String actor) {
        tx(() -> {
            Account a = accountRow(username);
            if (a == null || a.businessId() != businessId) throw new Http.Error(404, "No such account");
            if (a.owner()) throw new Http.Error(409, "Make someone else the owner first");
            update("DELETE FROM accounts WHERE username = ?", username);
            audit(actor, businessId, "Deleted account " + username, "");
            return null;
        });
    }

    /** A business the platform admin sets up directly: active at once, with an active owner. */
    synchronized long createWithOwner(String name, String contactName, String phone, String email, String address,
                                      String username, String salt, String hash, String actor) {
        return tx(() -> {
            if (accountRow(username) != null) throw new Http.Error(409, "That username is taken");
            long id = createBusiness(name, contactName, phone, email, address, "active");
            insertAccount(username, id, "owner", "active", contactName, salt, hash);
            audit(actor, id, "Created business", name + " (owner " + username + ")");
            return id;
        });
    }

    /** Makes an active account of this business its owner; the old owner becomes staff. Atomic. */
    synchronized void setOwner(long businessId, String username, String actor) {
        tx(() -> {
            Account a = accountRow(username);
            if (a == null || a.businessId() != businessId) throw new Http.Error(404, "No such account");
            if (!a.active()) throw new Http.Error(409, "Only an active account can be the owner");
            if (a.owner()) return null;
            update("UPDATE accounts SET role = 'staff' WHERE business_id = ? AND role = 'owner'", businessId);
            update("UPDATE accounts SET role = 'owner' WHERE username = ?", username);
            audit(actor, businessId, "Made " + username + " the store owner", "");
            return null;
        });
    }

    /** Called once the pending hash has been copied to the business's WebUsers.csv. */
    synchronized void clearPending(String username) {
        update("UPDATE accounts SET pending_salt = '', pending_hash = '' WHERE username = ?", username);
    }

    // ---------------------------------------------------------------- audit

    synchronized void audit(String actor, Long businessId, String action, String detail) {
        update("INSERT INTO audit(time, actor, business_id, action, detail) VALUES(?,?,?,?,?)",
                now(), actor, businessId, action, detail == null ? "" : detail);
    }

    synchronized List<Audit> auditLog(Long businessId, int limit) {
        RowMapper<Audit> map = rs -> new Audit(rs.getLong("id"), rs.getString("time"), rs.getString("actor"),
                rs.getObject("business_id") == null ? null : rs.getLong("business_id"), rs.getString("action"), rs.getString("detail"));
        return businessId == null
                ? query("SELECT * FROM audit ORDER BY id DESC LIMIT ?", map, limit)
                : query("SELECT * FROM audit WHERE business_id = ? ORDER BY id DESC LIMIT ?", map, businessId, limit);
    }

    // ---------------------------------------------------------------- backup

    /** A consistent copy of the database while it is in use (SQLite's VACUUM INTO). Replaces target. */
    synchronized void backup(Path target) {
        try {
            Files.deleteIfExists(target);
            Files.createDirectories(target.toAbsolutePath().getParent());
            try (Statement st = db.createStatement()) {
                st.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Platform backup failed: " + e.getMessage(), e);
        }
    }

    /** Runs SQLite's own consistency check; "ok" when healthy. */
    synchronized String integrity() {
        return query("PRAGMA integrity_check", rs -> rs.getString(1)).stream().findFirst().orElse("?");
    }

    // ---------------------------------------------------------------- util

    interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    interface Work<T> { T run() throws Exception; }

    /** Runs work (which may call other Platform methods) in one transaction: all of it is saved, or none. */
    synchronized <T> T inTransaction(Work<T> work) {
        return tx(work);
    }

    /** Runs work in one transaction (nested calls join the outer one). */
    private <T> T tx(Work<T> work) {
        try {
            boolean outer = db.getAutoCommit();
            if (outer) db.setAutoCommit(false);
            try {
                T out = work.run();
                if (outer) db.commit();
                return out;
            } catch (Exception e) {
                if (outer) db.rollback();
                throw e;
            } finally {
                if (outer) db.setAutoCommit(true);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> List<T> query(String sql, RowMapper<T> map, Object... args) {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(map.map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int update(String sql, Object... args) {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getMessage() != null && (e.getMessage().contains("one_active_owner") || e.getMessage().contains("accounts.business_id"))) throw new Http.Error(409, "A store can have only one active owner");
            throw new IllegalStateException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }

    private static Business business(ResultSet rs) throws SQLException {
        return new Business(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("contact_name"),
                rs.getString("phone"), rs.getString("email"), rs.getString("address"), rs.getString("status"),
                rs.getString("created"), rs.getString("decided"));
    }

    private static Account account(ResultSet rs) throws SQLException {
        return new Account(rs.getString("username"), rs.getLong("business_id"), rs.getString("role"), rs.getString("status"),
                rs.getString("full_name"), rs.getString("created"), rs.getString("pending_salt"), rs.getString("pending_hash"));
    }

    /** Store code staff type when they sign up: 6 letters/digits without look-alikes. */
    private static String newCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static String now() {
        return LocalDateTime.now().withNano(0).format(DataStore.TIME);
    }
}

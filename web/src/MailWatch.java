import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.*;

/**
 * Checks payments against MariBank's "money received" emails.
 *
 * Every POLL_MINUTES the server signs in to a mailbox (a Gmail account that gets the MariBank alerts forwarded) with an
 * app password, read-only, and reads new emails from alerts@maribank.com.ph. Each trusted one (see BankMail) is saved
 * once in bank_emails, then matched:
 *   - same reference as a waiting payment: "matched". With auto-approve on, the payment is approved when the email's
 *     amount is exactly the invoice's (each open invoice has its own centavos) and it was paid after the invoice existed.
 *   - else, when exactly one open invoice asks exactly that amount: suggested for that invoice. The admin can approve it
 *     with one click (for owners who paid but didn't send the reference). Never automatic.
 * A reference is used once (unique index), so the same transfer can't pay twice.
 *
 * The app password is kept in dataDir/mail-password (owner read/write only), outside the backups. The mailbox's
 * address and the other settings are in platform.db. Shares Platform's connection and lock.
 */
final class MailWatch {
    static final int POLL_MINUTES = 2;
    static final int FIRST_DAYS = 14;
    static final String ACTOR = "bank email";

    record Email(long id, String messageId, String received, String time, int amount, String reference, String sender,
                 String status, String problem, Long invoiceId, Long paymentId, String created) {}

    private final Platform platform;
    private final Billing billing;
    private final Path passwordFile;
    /** host:port of the mailbox; tests point this at a fake server. */
    volatile String host = "imap.gmail.com";
    volatile int port = 993;

    MailWatch(Platform platform, Billing billing) {
        this.platform = platform;
        this.billing = billing;
        this.passwordFile = platform.file().toAbsolutePath().getParent().resolve("mail-password");
        billing.afterSubmit = this::matchQuietly;
    }

    // ---------------------------------------------------------------- settings

    boolean on() { return "1".equals(platform.setting("mail_on")); }

    boolean auto() { return "1".equals(platform.setting("mail_auto")); }

    String user() { return Objects.toString(platform.setting("mail_user"), ""); }

    boolean hasPassword() { return Files.isRegularFile(passwordFile); }

    Map<String, Object> settings() {
        return Json.obj("on", on(), "auto", auto(), "user", user(), "password", hasPassword(),
                "lastCheck", Objects.toString(platform.setting("mail_last_check"), ""),
                "lastError", Objects.toString(platform.setting("mail_last_error"), ""),
                "everyMinutes", POLL_MINUTES);
    }

    /** password: null or "" keeps the saved one. A new address starts reading again from FIRST_DAYS ago. */
    void saveSettings(boolean on, boolean auto, String user, String password, String actor) throws IOException {
        user = user.trim().toLowerCase(Locale.ROOT);
        if (!user.isEmpty() && !user.matches("[a-z0-9._%+-]{1,64}@[a-z0-9.-]{1,190}\\.[a-z]{2,24}")) throw new Http.Error(400, "Mailbox: enter an email address");
        String pw = password == null ? "" : password.replace(" ", ""); // Google shows app passwords in groups of four
        if (!pw.isEmpty() && (pw.length() < 8 || pw.length() > 200 || !pw.matches("\\p{Graph}+"))) throw new Http.Error(400, "App password: 8 or more letters, no line breaks");
        if (on && (user.isEmpty() || (pw.isEmpty() && !hasPassword()))) throw new Http.Error(400, "Enter the mailbox address and its app password first");
        if (!pw.isEmpty()) writePassword(pw);
        final String u = user;
        synchronized (platform) {
            platform.tx(() -> {
                if (!u.equals(user())) {
                    platform.setSetting("mail_last_uid", "0");
                    platform.setSetting("mail_uid_validity", "0");
                }
                platform.setSetting("mail_user", u);
                platform.setSetting("mail_on", on ? "1" : "0");
                platform.setSetting("mail_auto", auto ? "1" : "0");
                platform.audit(actor, null, "Saved MariBank email settings", (on ? "on" : "off") + (auto ? ", auto-approve" : ", suggest only")
                        + (u.isEmpty() ? "" : ", " + u) + (pw.isEmpty() ? "" : ", new app password"));
                return null;
            });
        }
    }

    private void writePassword(String pw) throws IOException {
        Path tmp = passwordFile.resolveSibling(".mail-password.tmp");
        Files.deleteIfExists(tmp);
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(tmp);
        }
        Files.writeString(tmp, pw);
        Files.move(tmp, passwordFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---------------------------------------------------------------- reading the mailbox

    /** Timer: reads the mailbox when it's turned on. Errors are kept for the admin page, never thrown. */
    void tick() {
        if (!on()) return;
        try {
            poll();
        } catch (Exception e) {
            // already recorded by poll()
        }
    }

    /** Reads new MariBank emails and matches them. Returns how many new emails were saved. */
    synchronized int poll() throws IOException {
        String user = user();
        if (user.isEmpty() || !hasPassword()) throw new Http.Error(400, "Enter the mailbox address and its app password first");
        int added = 0;
        try (Imap imap = new Imap(host, port)) {
            imap.login(user, Files.readString(passwordFile).trim());
            long validity = imap.examine("INBOX");
            long last = 0;
            try {
                if (Long.parseLong(Objects.toString(platform.setting("mail_uid_validity"), "0")) == validity) {
                    last = Long.parseLong(Objects.toString(platform.setting("mail_last_uid"), "0"));
                }
            } catch (NumberFormatException ignored) {
            }
            for (long uid : imap.search(BankMail.SENDER, last, LocalDate.now().minusDays(FIRST_DAYS))) {
                Imap.Message m = imap.fetch(uid);
                if (m.raw() != null && save(m.raw(), "uid:" + validity + ":" + uid)) added++;
                platform.setSetting("mail_uid_validity", Long.toString(validity));
                platform.setSetting("mail_last_uid", Long.toString(uid));
            }
            if (last == 0) {
                platform.setSetting("mail_uid_validity", Long.toString(validity));
            }
            platform.setSetting("mail_last_check", Platform.now());
            platform.setSetting("mail_last_error", "");
        } catch (IOException | RuntimeException e) {
            platform.setSetting("mail_last_check", Platform.now());
            platform.setSetting("mail_last_error", Objects.toString(e.getMessage(), e.toString()));
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
        match();
        return added;
    }

    /** Saves one email (once, by Message-ID). Returns true when it was new. */
    boolean save(byte[] raw, String fallbackId) {
        BankMail.Result r = BankMail.read(raw);
        BankMail.Transfer t = r.transfer();
        if (t == null) return false; // another kind of MariBank email (OTP, news...)
        String id = t.messageId() == null ? fallbackId : t.messageId();
        synchronized (platform) {
            return platform.tx(() -> {
                int n = platform.update("INSERT OR IGNORE INTO bank_emails(message_id, received, time, amount, reference, sender, status, problem, created) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)", id, t.received(), t.time(), t.amount(), t.reference(), t.from(),
                        r.trusted() ? "new" : "untrusted", r.problem(), Platform.now());
                if (n == 1 && !r.trusted()) {
                    platform.audit(ACTOR, null, "Ignored an untrusted MariBank email", Billing.peso(t.amount()) + ", ref " + t.reference() + ": " + r.problem());
                }
                return n == 1;
            });
        }
    }

    // ---------------------------------------------------------------- matching

    private void matchQuietly() {
        try {
            match();
        } catch (RuntimeException e) {
            System.err.println("  Bank email match failed: " + e.getMessage());
        }
    }

    /** Links new emails to waiting payments (same reference) or suggests an invoice (same amount). Auto-approves if on. */
    void match() {
        List<Long> approve = new ArrayList<>();
        synchronized (platform) {
            platform.tx(() -> {
                for (Email e : emails("SELECT * FROM bank_emails WHERE status IN ('new','matched') ORDER BY id")) {
                    List<Long> pending = platform.query("SELECT id FROM payments WHERE reference = ? AND status = 'pending'", rs -> rs.getLong(1), e.reference());
                    if (!pending.isEmpty()) {
                        Billing.Payment p = billing.payment(pending.get(0));
                        platform.update("UPDATE bank_emails SET status = 'matched', payment_id = ?, invoice_id = ? WHERE id = ?", p.id(), p.invoiceId(), e.id());
                        Billing.Invoice inv = billing.invoice(p.invoiceId());
                        if (auto() && inv != null && inv.status().equals("open") && inv.amount() == e.amount() && !paidBefore(e, inv)) approve.add(p.id());
                        continue;
                    }
                    List<Long> invoices = platform.query("SELECT id FROM invoices WHERE status = 'open' AND amount = ? "
                            + "AND id NOT IN (SELECT invoice_id FROM payments WHERE status = 'pending')", rs -> rs.getLong(1), e.amount());
                    Long suggest = invoices.size() == 1 ? invoices.get(0) : null;
                    if (suggest != null && paidBefore(e, billing.invoice(suggest))) suggest = null;
                    platform.update("UPDATE bank_emails SET status = 'new', payment_id = NULL, invoice_id = ? WHERE id = ?", suggest, e.id());
                }
                return null;
            });
        }
        for (long id : approve) {
            try {
                billing.approve(id, ACTOR);
            } catch (Http.Error ignored) {
                // the admin decided it at the same moment
            }
        }
    }

    /** Paid more than a day before the invoice was made: an old transfer, not this invoice's. */
    private static boolean paidBefore(Email e, Billing.Invoice inv) {
        return e.time().substring(0, 10).compareTo(LocalDate.parse(inv.created().substring(0, 10)).minusDays(1).toString()) < 0;
    }

    /** Admin approves a suggested email: makes the payment for its invoice and approves it, in one transaction. */
    void apply(long emailId, String actor) {
        synchronized (platform) {
            platform.tx(() -> {
                Email e = email(emailId);
                if (e == null) throw new Http.Error(404, "No such email");
                if (!e.status().equals("new") || e.invoiceId() == null) throw new Http.Error(409, "This email has no invoice to pay");
                Billing.Invoice inv = billing.invoice(e.invoiceId());
                if (inv == null || !inv.status().equals("open") || inv.amount() != e.amount()) throw new Http.Error(409, "The invoice changed; refresh the page");
                if (!platform.query("SELECT 1 FROM payments WHERE reference = ? AND status != 'rejected'", rs -> 1, e.reference()).isEmpty()) {
                    throw new Http.Error(409, "This reference was already sent");
                }
                platform.update("INSERT INTO payments(invoice_id, business_id, reference, amount, paid_on, submitted_by, submitted, status) "
                        + "VALUES(?,?,?,?,?,?,?, 'pending')", inv.id(), inv.businessId(), e.reference(), e.amount(), e.time().substring(0, 10), ACTOR, Platform.now());
                long pid = platform.query("SELECT last_insert_rowid()", rs -> rs.getLong(1)).get(0);
                billing.approve(pid, actor);
                return null;
            });
        }
    }

    void ignore(long emailId, String actor) {
        synchronized (platform) {
            platform.tx(() -> {
                Email e = email(emailId);
                if (e == null) throw new Http.Error(404, "No such email");
                if (platform.update("UPDATE bank_emails SET status = 'ignored', invoice_id = NULL WHERE id = ? AND status = 'new'", emailId) != 1) {
                    throw new Http.Error(409, "Only an unmatched email can be ignored");
                }
                platform.audit(actor, null, "Marked a MariBank email as not a fee", Billing.peso(e.amount()) + ", ref " + e.reference());
                return null;
            });
        }
    }

    // ---------------------------------------------------------------- reads

    Email email(long id) {
        return emails("SELECT * FROM bank_emails WHERE id = ?", id).stream().findFirst().orElse(null);
    }

    /** The trusted email with this payment's reference, if any. */
    Email forReference(String reference) {
        return emails("SELECT * FROM bank_emails WHERE reference = ? AND status != 'untrusted'", reference).stream().findFirst().orElse(null);
    }

    /** Unmatched ones first, then the latest 30 others. */
    List<Email> recent() {
        List<Email> out = new ArrayList<>(emails("SELECT * FROM bank_emails WHERE status = 'new' ORDER BY id DESC"));
        out.addAll(emails("SELECT * FROM bank_emails WHERE status != 'new' ORDER BY id DESC LIMIT 30"));
        return out;
    }

    private List<Email> emails(String sql, Object... args) {
        synchronized (platform) {
            return platform.query(sql, rs -> new Email(rs.getLong("id"), rs.getString("message_id"), rs.getString("received"), rs.getString("time"),
                    rs.getInt("amount"), rs.getString("reference"), rs.getString("sender"), rs.getString("status"), rs.getString("problem"),
                    nullableLong(rs, "invoice_id"), nullableLong(rs, "payment_id"), rs.getString("created")), args);
        }
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    static Map<String, Object> emailOut(Email e) {
        return Json.obj("id", e.id(), "time", e.time(), "received", e.received(), "amount", e.amount(), "reference", e.reference(),
                "from", e.sender(), "status", e.status(), "problem", e.problem(), "invoiceId", e.invoiceId(), "paymentId", e.paymentId());
    }
}

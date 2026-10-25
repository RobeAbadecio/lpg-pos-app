import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Monthly subscription per business, paid by QR Ph / InstaPay QR (to MariBank) and checked by the platform admin.
 *
 * A business pays first: until its first payment is approved (paid_until empty) it is "unpaid" and can't save
 * records. The admin can give free days instead (setting billing_trial_days, 0 by default).
 *
 * A business is paid up to paid_until (a date, inclusive). From DUE_SOON_DAYS before that date it has one open invoice
 * for the next month. Each open invoice gets its own amount (the price plus 1–99 centavos), so a payment in the MariBank
 * history can be told apart from the others. After paid_until come the grace days (the POS works, with a warning);
 * after those the business is overdue: it can still sign in, look at its records and pay, but can't save anything.
 *
 * The owner sends the payment's reference number (and a screenshot, if they like); the admin approves or rejects it.
 * The database does the safety work: a reference can be used once, an invoice has at most one payment waiting, and
 * approving is one conditional update, so a payment can't be applied twice.
 *
 * Shares Platform's connection and lock. Amounts are centavos. Business 1 (the platform owner's own store) is exempt.
 */
final class Billing {
    static final int DUE_SOON_DAYS = 7;
    static final int MAX_IMAGE = 2 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    record Invoice(long id, long businessId, String periodStart, String periodEnd, int amount, String status, String created, String paid) {}

    record Payment(long id, long invoiceId, long businessId, String reference, int amount, String paidOn, String receipt,
                   String submittedBy, String submitted, String status, String decided, String decidedBy, String note) {}

    /**
     * free, unpaid (never paid yet, POS locked), active, due (invoice waiting, still paid up), grace (past paid_until,
     * POS works) or overdue (POS locked).
     */
    record State(String state, String paidUntil, String lockDate, Invoice invoice) {
        boolean locked() { return state.equals("overdue") || state.equals("unpaid"); }
    }

    record Image(byte[] bytes, String ext) {
        String mime() { return ext.equals("png") ? "image/png" : "image/jpeg"; }
    }

    private final Platform platform;
    private final Path files;

    Billing(Platform platform) throws IOException {
        this.platform = platform;
        this.files = platform.file().toAbsolutePath().getParent().resolve("platform-files");
        Files.createDirectories(files.resolve("receipts"));
    }

    Path files() { return files; }

    // ---------------------------------------------------------------- settings

    boolean on() { return "1".equals(platform.setting("billing_on")); }

    int price() { return intSetting(platform, "billing_price", 1000); }

    int graceDays() { return intSetting(platform, "billing_grace_days", 7); }

    static int trialDays(Platform p) { return intSetting(p, "billing_trial_days", 0); }

    /** paid_until for a business that starts today: the last free day, or "" (pay first) when there are no free days. */
    static String trialEnd(Platform p) {
        int days = trialDays(p);
        return days == 0 ? "" : today().plusDays(days - 1).toString();
    }

    Map<String, Object> settings() {
        return Json.obj("on", on(), "price", price(), "graceDays", graceDays(), "trialDays", trialDays(platform),
                "payee", Objects.toString(platform.setting("billing_payee"), ""),
                "note", Objects.toString(platform.setting("billing_note"), ""),
                "qr", qrFile() != null);
    }

    /**
     * Saves the billing settings. Turning billing on starts every paying business that isn't paid up from today: it pays
     * first (or gets the free days, if any).
     */
    void saveSettings(boolean on, int price, int graceDays, int trialDays, String payee, String note, String actor) {
        if (price < 100 || price > 10_000_00) throw new Http.Error(400, "Price: ₱1 to ₱10,000");
        if (graceDays < 0 || graceDays > 60) throw new Http.Error(400, "Grace days: 0 to 60");
        if (trialDays < 0 || trialDays > 90) throw new Http.Error(400, "Free days: 0 to 90");
        synchronized (platform) {
            platform.tx(() -> {
                boolean was = on();
                platform.setSetting("billing_price", Integer.toString(price));
                platform.setSetting("billing_grace_days", Integer.toString(graceDays));
                platform.setSetting("billing_trial_days", Integer.toString(trialDays));
                platform.setSetting("billing_payee", payee);
                platform.setSetting("billing_note", note);
                platform.setSetting("billing_on", on ? "1" : "0");
                if (on && !was) {
                    platform.update("UPDATE businesses SET paid_until = ? WHERE billing_exempt = 0 AND status IN ('active','suspended') "
                            + "AND (paid_until = '' OR paid_until < ?)", trialEnd(platform), today().toString());
                }
                platform.audit(actor, null, "Saved billing settings", (on ? "on" : "off") + ", " + peso(price) + "/month, "
                        + graceDays + " grace days, " + trialDays + " free days");
                return null;
            });
        }
    }

    Path qrFile() {
        String name = platform.setting("billing_qr");
        if (name == null) return null;
        Path p = files.resolve(name);
        return Files.isRegularFile(p) ? p : null;
    }

    void saveQr(Image img, String actor) throws IOException {
        Path target = files.resolve("payment-qr." + img.ext());
        Path tmp = files.resolve(".payment-qr.tmp");
        Files.write(tmp, img.bytes());
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Path old = qrFile();
        platform.setSetting("billing_qr", target.getFileName().toString());
        if (old != null && !old.equals(target)) Files.deleteIfExists(old);
        platform.audit(actor, null, "Uploaded the payment QR code", "");
    }

    // ---------------------------------------------------------------- state and invoices

    State state(Platform.Business b) {
        if (!on() || b.billingExempt()) return new State("free", b.paidUntil(), null, null);
        synchronized (platform) {
            if (b.paidUntil().isEmpty()) return new State("unpaid", "", null, ensureInvoice(b.id()));
            LocalDate paidUntil = LocalDate.parse(b.paidUntil());
            LocalDate lock = paidUntil.plusDays(graceDays() + 1);
            LocalDate t = today();
            String s = !t.isAfter(paidUntil) ? (t.isBefore(paidUntil.minusDays(DUE_SOON_DAYS - 1)) ? "active" : "due")
                    : t.isBefore(lock) ? "grace" : "overdue";
            Invoice inv = s.equals("active") ? openInvoice(b.id()) : ensureInvoice(b.id());
            return new State(s, paidUntil.toString(), lock.toString(), inv);
        }
    }

    /** Creates the business's next invoice if none is open (only call when it's due). */
    private Invoice ensureInvoice(long businessId) {
        return platform.tx(() -> {
            Invoice open = openInvoice(businessId);
            if (open != null) return open;
            Platform.Business b = platform.business(businessId);
            LocalDate start = nextPeriodStart(b);
            Set<Integer> taken = new HashSet<>(q("SELECT amount FROM invoices WHERE status = 'open'", rs -> rs.getInt(1)));
            int price = price();
            List<Integer> free = new ArrayList<>();
            for (int c = 1; c <= 99; c++) if (!taken.contains(price + c)) free.add(price + c);
            int amount = free.isEmpty() ? price : free.get(RANDOM.nextInt(free.size()));
            platform.update("INSERT INTO invoices(business_id, period_start, period_end, amount, status, created) VALUES(?,?,?,?, 'open', ?)",
                    businessId, start.toString(), start.plusMonths(1).minusDays(1).toString(), amount, Platform.now());
            return openInvoice(businessId);
        });
    }

    /** The month a payment made now pays for: right after paid_until, or from today when never paid or locked. */
    private LocalDate nextPeriodStart(Platform.Business b) {
        if (b.paidUntil().isEmpty()) return today();
        LocalDate paidUntil = LocalDate.parse(b.paidUntil());
        LocalDate lock = paidUntil.plusDays(graceDays() + 1);
        return today().isBefore(lock) ? paidUntil.plusDays(1) : today();
    }

    /** Daily: makes sure every business that is due has its invoice. */
    void ensureInvoices() {
        if (!on()) return;
        for (Platform.Business b : platform.businesses()) if (b.status().equals("active")) state(b);
    }

    Invoice openInvoice(long businessId) {
        return q("SELECT * FROM invoices WHERE business_id = ? AND status = 'open'", Billing::invoice, businessId)
                .stream().findFirst().orElse(null);
    }

    Invoice invoice(long id) {
        return q("SELECT * FROM invoices WHERE id = ?", Billing::invoice, id).stream().findFirst().orElse(null);
    }

    List<Invoice> invoices(long businessId) {
        return q("SELECT * FROM invoices WHERE business_id = ? AND status != 'void' ORDER BY id DESC LIMIT 24", Billing::invoice, businessId);
    }

    // ---------------------------------------------------------------- payments

    /** The owner says they paid an invoice. Returns the new payment's id. */
    long submit(long businessId, long invoiceId, String reference, int amount, String paidOn, Image receipt, String actor) {
        synchronized (platform) {
            return platform.tx(() -> {
                Invoice inv = invoice(invoiceId);
                if (inv == null || inv.businessId() != businessId) throw new Http.Error(404, "No such invoice");
                if (!inv.status().equals("open")) throw new Http.Error(409, "This invoice is already paid");
                if (!q("SELECT 1 FROM payments WHERE invoice_id = ? AND status = 'pending'", rs -> 1, invoiceId).isEmpty()) {
                    throw new Http.Error(409, "A payment for this invoice is already waiting to be checked");
                }
                if (!q("SELECT 1 FROM payments WHERE reference = ? AND status != 'rejected'", rs -> 1, reference).isEmpty()) {
                    throw new Http.Error(409, "This reference number was already sent. Check the number on your payment receipt.");
                }
                platform.update("INSERT INTO payments(invoice_id, business_id, reference, amount, paid_on, submitted_by, submitted, status) "
                        + "VALUES(?,?,?,?,?,?,?, 'pending')", invoiceId, businessId, reference, amount, paidOn, actor, Platform.now());
                long id = q("SELECT last_insert_rowid()", rs -> rs.getLong(1)).get(0);
                if (receipt != null) {
                    String name = "receipts/" + id + "." + receipt.ext();
                    Files.write(files.resolve(name), receipt.bytes());
                    platform.update("UPDATE payments SET receipt = ? WHERE id = ?", name, id);
                }
                platform.audit(actor, businessId, "Sent a payment", peso(amount) + ", ref " + reference + " (invoice " + invoiceNo(inv) + ")");
                return id;
            });
        }
    }

    Payment payment(long id) {
        return q("SELECT * FROM payments WHERE id = ?", Billing::payment, id).stream().findFirst().orElse(null);
    }

    List<Payment> payments(long businessId) {
        return q("SELECT * FROM payments WHERE business_id = ? ORDER BY id DESC LIMIT 24", Billing::payment, businessId);
    }

    /** Waiting payments first (oldest first), then the latest decided ones. */
    List<Payment> queue() {
        List<Payment> out = new ArrayList<>(q("SELECT * FROM payments WHERE status = 'pending' ORDER BY id", Billing::payment));
        out.addAll(q("SELECT * FROM payments WHERE status != 'pending' ORDER BY id DESC LIMIT 30", Billing::payment));
        return out;
    }

    /** Things the admin should look at before approving. */
    List<String> warnings(Payment p) {
        List<String> out = new ArrayList<>();
        Invoice inv = invoice(p.invoiceId());
        if (inv != null && inv.amount() != p.amount()) out.add("Amount is " + peso(p.amount()) + ", the invoice asks " + peso(inv.amount()));
        if (!q("SELECT 1 FROM payments WHERE reference = ? AND id != ?", rs -> 1, p.reference(), p.id()).isEmpty()) {
            out.add("This reference was sent before (and rejected)");
        }
        try {
            LocalDate d = LocalDate.parse(p.paidOn());
            if (inv != null && d.isBefore(LocalDate.parse(inv.created().substring(0, 10)))) out.add("Paid before the invoice was made");
        } catch (DateTimeParseException ignored) {
        }
        return out;
    }

    /**
     * Approves a waiting payment: the invoice is paid and the business is paid up for one more month. One conditional
     * update decides, so the same payment (or invoice) can never be applied twice.
     */
    void approve(long paymentId, String actor) {
        synchronized (platform) {
            platform.tx(() -> {
                Payment p = payment(paymentId);
                if (p == null) throw new Http.Error(404, "No such payment");
                if (platform.update("UPDATE payments SET status = 'approved', decided = ?, decided_by = ? WHERE id = ? AND status = 'pending'",
                        Platform.now(), actor, paymentId) != 1) throw new Http.Error(409, "This payment was already decided");
                if (platform.update("UPDATE invoices SET status = 'paid', paid = ? WHERE id = ? AND status = 'open'", Platform.now(), p.invoiceId()) != 1) {
                    throw new Http.Error(409, "This invoice is already paid");
                }
                Platform.Business b = platform.business(p.businessId());
                LocalDate start = nextPeriodStart(b);
                LocalDate until = start.plusMonths(1).minusDays(1);
                platform.update("UPDATE invoices SET period_start = ?, period_end = ? WHERE id = ?", start.toString(), until.toString(), p.invoiceId());
                platform.update("UPDATE businesses SET paid_until = ? WHERE id = ?", until.toString(), b.id());
                platform.audit(actor, b.id(), "Approved payment", peso(p.amount()) + ", ref " + p.reference() + "; paid until " + until);
                return null;
            });
        }
    }

    void reject(long paymentId, String note, String actor) {
        synchronized (platform) {
            platform.tx(() -> {
                Payment p = payment(paymentId);
                if (p == null) throw new Http.Error(404, "No such payment");
                if (platform.update("UPDATE payments SET status = 'rejected', decided = ?, decided_by = ?, note = ? WHERE id = ? AND status = 'pending'",
                        Platform.now(), actor, note, paymentId) != 1) throw new Http.Error(409, "This payment was already decided");
                platform.audit(actor, p.businessId(), "Rejected payment", "ref " + p.reference() + (note.isEmpty() ? "" : ": " + note));
                return null;
            });
        }
    }

    // ---------------------------------------------------------------- admin changes per business

    void setExempt(long businessId, boolean exempt, String actor) {
        synchronized (platform) {
            platform.tx(() -> {
                Platform.Business b = platform.business(businessId);
                if (b == null) throw new Http.Error(404, "No such business");
                platform.update("UPDATE businesses SET billing_exempt = ? WHERE id = ?", exempt ? 1 : 0, businessId);
                if (!exempt && (b.paidUntil().isEmpty() || LocalDate.parse(b.paidUntil()).isBefore(today()))) {
                    platform.update("UPDATE businesses SET paid_until = ? WHERE id = ?", trialEnd(platform), businessId);
                }
                if (exempt) voidOpen(businessId);
                platform.audit(actor, businessId, exempt ? "Made the store free of charge" : "Started charging the store", b.name());
                return null;
            });
        }
    }

    /** For payments made another way (cash): sets the paid-up date directly. */
    void setPaidUntil(long businessId, LocalDate until, String actor) {
        synchronized (platform) {
            platform.tx(() -> {
                Platform.Business b = platform.business(businessId);
                if (b == null) throw new Http.Error(404, "No such business");
                platform.update("UPDATE businesses SET paid_until = ? WHERE id = ?", until.toString(), businessId);
                if (until.isAfter(today().plusDays(DUE_SOON_DAYS))) voidOpen(businessId);
                platform.audit(actor, businessId, "Set paid until " + until, b.paidUntil().isEmpty() ? "" : "was " + b.paidUntil());
                return null;
            });
        }
    }

    /** Cancels the open invoice unless a payment for it is waiting. */
    private void voidOpen(long businessId) {
        platform.update("UPDATE invoices SET status = 'void' WHERE business_id = ? AND status = 'open' "
                + "AND id NOT IN (SELECT invoice_id FROM payments WHERE status = 'pending')", businessId);
    }

    /** A read under Platform's lock (the one connection is not shared between threads). */
    private <T> List<T> q(String sql, Platform.RowMapper<T> map, Object... args) {
        synchronized (platform) {
            return platform.query(sql, map, args);
        }
    }

    // ---------------------------------------------------------------- JSON

    Map<String, Object> summary(Platform.Business b) {
        State s = state(b);
        return Json.obj("state", s.state(), "paidUntil", s.paidUntil(), "lockDate", s.lockDate(),
                "invoice", s.invoice() == null ? null : invoiceOut(s.invoice()));
    }

    static Map<String, Object> invoiceOut(Invoice i) {
        return Json.obj("id", i.id(), "number", invoiceNo(i), "periodStart", i.periodStart(), "periodEnd", i.periodEnd(),
                "amount", i.amount(), "status", i.status(), "created", i.created(), "paid", i.paid());
    }

    static Map<String, Object> paymentOut(Payment p) {
        return Json.obj("id", p.id(), "invoiceId", p.invoiceId(), "businessId", p.businessId(), "reference", p.reference(),
                "amount", p.amount(), "paidOn", p.paidOn(), "receipt", !p.receipt().isEmpty(), "submittedBy", p.submittedBy(),
                "submitted", p.submitted(), "status", p.status(), "decided", p.decided(), "note", p.note());
    }

    static String invoiceNo(Invoice i) { return String.format("INV-%05d", i.id()); }

    // ---------------------------------------------------------------- input

    /** InstaPay / bank reference: letters and digits, spaces and dashes dropped. */
    static String reference(String raw) {
        String r = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!r.matches("[A-Z0-9]{6,32}")) throw new Http.Error(400, "Reference number: 6–32 letters or digits, as shown on your payment receipt");
        return r;
    }

    /** "10.37" or "₱10.37" → 1037. */
    static int centavos(String raw, String label) {
        try {
            BigDecimal v = new BigDecimal(raw.replaceAll("[₱,\\s]", "")).setScale(2, RoundingMode.UNNECESSARY);
            if (v.signum() <= 0 || v.compareTo(BigDecimal.valueOf(100_000)) > 0) throw new NumberFormatException();
            return v.movePointRight(2).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new Http.Error(400, label + ": enter an amount like 10.37");
        }
    }

    static LocalDate date(String raw, String label) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new Http.Error(400, label + ": pick a date");
        }
    }

    /** A PNG or JPEG sent as a data: URL. The file type is checked from its content, not from what the browser says. */
    static Image image(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:") || comma < 0 || !dataUrl.substring(0, comma).endsWith(";base64")) throw new Http.Error(400, "Picture: send a PNG or JPEG image");
        byte[] b;
        try {
            b = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new Http.Error(400, "Picture: the file is damaged");
        }
        if (b.length > MAX_IMAGE) throw new Http.Error(413, "Picture is too big (at most 2 MB)");
        if (b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return new Image(b, "png");
        if (b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return new Image(b, "jpg");
        throw new Http.Error(400, "Picture: send a PNG or JPEG image");
    }

    static String peso(int centavos) {
        return "₱" + BigDecimal.valueOf(centavos, 2).toPlainString();
    }

    /** Tests move the calendar with this; null means the real date. */
    static volatile LocalDate testToday;

    static LocalDate today() { return testToday != null ? testToday : LocalDate.now(); }

    private static int intSetting(Platform p, String key, int fallback) {
        try {
            return Integer.parseInt(p.setting(key));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Invoice invoice(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Invoice(rs.getLong("id"), rs.getLong("business_id"), rs.getString("period_start"), rs.getString("period_end"),
                rs.getInt("amount"), rs.getString("status"), rs.getString("created"), rs.getString("paid"));
    }

    private static Payment payment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Payment(rs.getLong("id"), rs.getLong("invoice_id"), rs.getLong("business_id"), rs.getString("reference"),
                rs.getInt("amount"), rs.getString("paid_on"), rs.getString("receipt"), rs.getString("submitted_by"),
                rs.getString("submitted"), rs.getString("status"), rs.getString("decided"), rs.getString("decided_by"), rs.getString("note"));
    }
}

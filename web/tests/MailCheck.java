import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/**
 * Check of the MariBank email matching (5.2.0) on a throw-away data folder, with a fake IMAP mailbox: reading the
 * emails (quoted-printable and base64), trusting only what Gmail says MariBank signed, read-only mailbox commands,
 * once-only saving, matching by reference and exact amount, auto-approve on and off, amount-only suggestions and
 * the admin's one-click approve, wrong amounts, rejected payments, "not a fee", and the app password kept private.
 * The emails are made up; real ones can be checked with:  java -cp build:lib/* BankMail file.eml
 *
 *   javac -d /tmp/lpg-check -cp "web/lib/*" web/src/*.java web/tests/*.java
 *   java -cp "/tmp/lpg-check:web/lib/*" MailCheck
 */
public class MailCheck {
    static final String PASSWORD = "abcd efgh ijkl mnop";
    static final String GOOD_AUTH = "mx.google.com;\r\n       dkim=pass header.i=@maribank.com.ph header.s=ib-smtp1 header.b=foZFyIwp;\r\n"
            + "       spf=pass (google.com: domain of x@maribank.com.ph designates 1.2.3.4 as permitted sender) smtp.mailfrom=\"x@maribank.com.ph\";\r\n"
            + "       dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=maribank.com.ph";

    static void check(boolean ok, String what) { PlatformCheck.check(ok, what); }

    public static void main(String[] args) throws Exception {
        parsing();

        Path data = Files.createTempDirectory("lpg-mail-check-");
        String salt = Auth.newSalt();
        Files.writeString(data.resolve("WebUsers.csv"), "robe," + salt + "," + Auth.hash("robe-pass-1", salt) + ",2026-01-01 08:00:00,1\n");
        Platform platform = new Platform(data.resolve("platform.db"));
        Tenants tenants = new Tenants(data, platform);
        tenants.migrateLegacy(data, "Robe's LPG");
        Billing billing = new Billing(platform);
        MailWatch mail = new MailWatch(platform, billing);
        Auth auth = new Auth();
        Path pub = Path.of("web/public");
        PosGate gate = new PosGate(platform, billing, tenants, auth, pub.resolve("app"), pub.resolve("shared"), 1, null);
        HttpServer pos = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pos.createContext("/", Http.wrap(gate));
        pos.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        pos.start();
        HttpServer adm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int adminPort = adm.getAddress().getPort();
        adm.createContext("/", Http.wrap(new AdminApi(platform, billing, mail, tenants, gate, auth, new Tunnel(1, u -> {}), pub.resolve("admin"), pub.resolve("shared"), 1, adminPort)));
        adm.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        adm.start();
        FakeImap box = new FakeImap();
        Imap.plainForTests = true;
        mail.host = "127.0.0.1";
        mail.port = box.port();
        try {
            run("http://localhost:" + pos.getAddress().getPort(), "http://localhost:" + adminPort, platform, billing, mail, box, data);
        } finally {
            pos.stop(0);
            adm.stop(0);
            box.close();
            Imap.plainForTests = false;
        }
        System.out.println("PASS: " + PlatformCheck.pass + " MariBank email checks (reading, trust, mailbox, matching, auto-approve)");
    }

    // ---------------------------------------------------------------- reading one email

    static void parsing() {
        BankMail.Result ok = BankMail.read(eml("<a1@x>", "alerts@maribank.com.ph", GOOD_AUTH, "10.37", "943019", false));
        check(ok.trusted(), "a signed MariBank email is trusted: " + ok.problem());
        BankMail.Transfer t = ok.transfer();
        check(t.amount() == 1037 && t.reference().equals("943019") && t.time().equals("2026-10-25 11:49"), "amount, reference and time read: " + t);
        check(t.from().equals("GoTyme Bank - 6428"), "payer's bank read");
        check(t.messageId().equals("<a1@x>"), "message id read");
        check(BankMail.read(eml("<a2@x>", "alerts@maribank.com.ph", GOOD_AUTH, "1,010.37", "943020", true)).transfer().amount() == 101037,
                "base64 body and thousands read");

        check(!BankMail.read(eml("<b@x>", "alerts@maribank-ph.com", GOOD_AUTH, "10.37", "943019", false)).trusted(), "another sender is not trusted");
        check(!BankMail.read(eml("<c@x>", "alerts@maribank.com.ph", null, "10.37", "943019", false)).trusted(), "no Gmail check: not trusted");
        String failing = "mx.google.com; dkim=fail header.i=@maribank.com.ph; dmarc=fail (p=REJECT) header.from=maribank.com.ph";
        check(!BankMail.read(eml("<d@x>", "alerts@maribank.com.ph", failing, "10.37", "943019", false)).trusted(), "failed signature: not trusted");
        // a faker writes a passing header lower down; Gmail's own (topmost) one says it failed
        byte[] forged = (("Authentication-Results: " + failing + "\r\n") + new String(eml("<e@x>", "alerts@maribank.com.ph", GOOD_AUTH, "10.37", "943019", false),
                StandardCharsets.ISO_8859_1)).getBytes(StandardCharsets.ISO_8859_1);
        check(!BankMail.read(forged).trusted(), "a faked header below Gmail's is not believed");
        String other = "mx.google.com; dkim=pass header.i=@evil.example; dmarc=pass (p=NONE) header.from=evil.example";
        check(!BankMail.read(eml("<f@x>", "alerts@maribank.com.ph", other, "10.37", "943019", false)).trusted(), "someone else's signature: not trusted");
        String lookalike = "mx.google.com; dkim=pass header.i=@maribank.com.ph.evil.example; dmarc=pass (p=NONE) header.from=maribank.com.ph.evil.example";
        check(!BankMail.read(eml("<f2@x>", "alerts@maribank.com.ph", lookalike, "10.37", "943019", false)).trusted(), "a look-alike domain's signature: not trusted");
        check(BankMail.address("\"<alerts@maribank.com.ph>\" <evil@example.com>").equals("evil@example.com"), "the real address, not one written in the name");
        String notGmail = "evil.example; dkim=pass header.i=@maribank.com.ph; dmarc=pass header.from=maribank.com.ph";
        check(!BankMail.read(eml("<g@x>", "alerts@maribank.com.ph", notGmail, "10.37", "943019", false)).trusted(), "a check by another server: not trusted");
        byte[] otp = ("From: alerts@maribank.com.ph\r\nSubject: Your OTP\r\nContent-Type: text/plain\r\n\r\nYour code is 123456").getBytes();
        check(BankMail.read(otp).transfer() == null, "other MariBank emails are skipped");
        check(BankMail.address("MariBank PH Alerts <Alerts@MariBank.com.ph>").equals("alerts@maribank.com.ph"), "sender address read");
        try {
            Imap.quote("x\r\nA2 DELETE INBOX");
            check(false, "line breaks refused in IMAP strings");
        } catch (IllegalArgumentException e) {
            check(true, "line breaks refused in IMAP strings");
        }
    }

    // ---------------------------------------------------------------- end to end

    static void run(String posUrl, String adminUrl, Platform platform, Billing billing, MailWatch mail, FakeImap box, Path data) throws Exception {
        PlatformCheck.Browser admin = new PlatformCheck.Browser(adminUrl);
        check(admin.status("POST", "/api/admin/setup", "password", "admin-secret-1") == 200, "admin password set");
        check(new PlatformCheck.Browser(adminUrl).status("PUT", "/api/mail", "on", "0", "auto", "0", "user", "", "password", "") == 401, "email settings need admin sign-in");
        check(admin.status("PUT", "/api/billing", "on", "1", "price", "10.00", "graceDays", "7", "trialDays", "0", "payee", "R. A.", "note", "") == 200, "billing on");

        long biz2 = business(admin, posUrl, platform, "Gas Two", "ana");
        long biz3 = business(admin, posUrl, platform, "Gas Three", "cyd");
        PlatformCheck.Browser ana = login(posUrl, "ana"), cyd = login(posUrl, "cyd");
        Billing.Invoice inv2 = billing.state(platform.business(biz2)).invoice();
        Billing.Invoice inv3 = billing.state(platform.business(biz3)).invoice();
        check(inv2.amount() != inv3.amount(), "two open invoices, two amounts");
        String today = LocalDate.now().toString();
        String time = String.format("%d %s %d 11:49", LocalDate.now().getDayOfMonth(),
                LocalDate.now().getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH), LocalDate.now().getYear());

        // ---- settings
        check(admin.status("PUT", "/api/mail", "on", "1", "auto", "0", "user", "lpg.pay@gmail.com", "password", "") == 400, "can't turn on without the app password");
        check(admin.status("PUT", "/api/mail", "on", "0", "auto", "0", "user", "not an email", "password", "") == 400, "a bad address is refused");
        check(admin.status("PUT", "/api/mail", "on", "1", "auto", "0", "user", "lpg.pay@gmail.com", "password", PASSWORD) == 200, "email reading turned on");
        Path pw = data.resolve("mail-password");
        check(Files.readString(pw).equals(PASSWORD.replace(" ", "")), "app password saved without the spaces");
        check(Files.getPosixFilePermissions(pw).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "app password file is private");
        String overview = admin.send("GET", "/api/billing").body();
        check(!overview.contains("abcdefgh") && overview.contains("\"password\":true"), "the admin page never shows the app password");
        check(!platform.query("SELECT value FROM settings", rs -> rs.getString(1)).toString().contains("abcdefgh"), "app password is not in platform.db");
        Path backup = Backups.run(platform, new Tenants(data, platform));
        try (var s = Files.walk(backup)) {
            check(s.noneMatch(p -> p.getFileName().toString().contains("mail-password")), "app password is not in backups");
        }

        // ---- wrong password: the error is kept for the admin page
        box.password = "something-else";
        var refused = admin.send("POST", "/api/mail/check");
        check(refused.statusCode() == 502, "a refused sign-in is reported: " + refused.statusCode() + " " + refused.body());
        check(admin.send("GET", "/api/billing").body().contains("Sign-in failed"), "the admin sees why");
        box.password = PASSWORD.replace(" ", "");

        // ---- an email arrives before the owner says anything: suggested for the invoice with that amount
        box.add(eml("<m1@x>", "alerts@maribank.com.ph", GOOD_AUTH, money(inv2.amount()), "845017", false, time));
        box.add(("From: Someone <friend@gmail.com>\r\nSubject: hi\r\n\r\nhello").getBytes()); // not from MariBank: not even fetched
        check(mail.poll() == 1, "one new MariBank email read");
        check(box.log.stream().anyMatch(l -> l.contains("EXAMINE")) && box.log.stream().noneMatch(l -> l.matches("a\\d+ (SELECT|STORE|COPY|MOVE|EXPUNGE|UID STORE).*")),
                "the mailbox is opened read-only and nothing is changed");
        check(box.log.stream().filter(l -> l.contains("FETCH")).allMatch(l -> l.contains("BODY.PEEK")), "emails stay unread");
        MailWatch.Email e1 = mail.forReference("845017");
        check(e1 != null && e1.status().equals("new") && Objects.equals(e1.invoiceId(), inv2.id()), "suggested for Gas Two's invoice by its exact amount");
        check(billing.state(platform.business(biz2)).state().equals("unpaid"), "a suggestion alone pays nothing");
        check(mail.poll() == 0, "the same email is not read twice");

        // the owner sends the same reference: matched, but suggest-only waits for the admin
        check(ana.status("POST", "/api/billing/pay", "invoice", "" + inv2.id(), "reference", "845017", "amount", money(inv2.amount()), "paidOn", today) == 201, "owner sends the reference");
        e1 = mail.forReference("845017");
        check(e1.status().equals("matched") && e1.paymentId() != null, "matched to the owner's payment at once");
        String queue = admin.send("GET", "/api/billing").body();
        check(queue.contains("\"bank\":{") && !queue.contains("No MariBank email with this reference yet"), "the queue shows the MariBank email");
        check(billing.payment(e1.paymentId()).status().equals("pending"), "suggest only: still waiting for the admin");
        check(admin.status("POST", "/api/payments/" + e1.paymentId() + "/approve") == 200, "admin approves");
        check(mail.forReference("845017").status().equals("used"), "the email is used up");
        check(billing.state(platform.business(biz2)).state().equals("active"), "Gas Two is paid");

        // ---- auto-approve on: reference + exact amount approves with no one clicking
        check(admin.status("PUT", "/api/mail", "on", "1", "auto", "1", "user", "lpg.pay@gmail.com", "password", "") == 200, "auto-approve on, password kept");
        check(Files.readString(pw).equals(PASSWORD.replace(" ", "")), "an empty password field keeps the saved one");
        check(cyd.status("POST", "/api/billing/pay", "invoice", "" + inv3.id(), "reference", "917007", "amount", money(inv3.amount()), "paidOn", today) == 201, "Gas Three sends a reference");
        check(admin.send("GET", "/api/billing").body().contains("No MariBank email with this reference yet"), "no email yet is pointed out");
        box.add(eml("<m2@x>", "alerts@maribank.com.ph", GOOD_AUTH, money(inv3.amount()), "917007", false, time));
        mail.poll();
        check(billing.state(platform.business(biz3)).state().equals("active"), "auto-approved when the email arrived");
        check(platform.auditLog(biz3, 5).stream().anyMatch(a -> a.actor().equals("bank email") && a.action().equals("Approved payment")), "the audit log says the bank email approved it");

        // a resend of the same transfer (another message id) changes nothing
        box.add(eml("<m2-again@x>", "alerts@maribank.com.ph", GOOD_AUTH, money(inv3.amount()), "917007", false, time));
        check(mail.poll() == 0, "the same reference is saved once");

        // ---- a fake email with a waiting payment's reference does nothing
        Billing.testToday = LocalDate.now().plusMonths(1).minusDays(3); // both stores are due again
        try {
            Billing.Invoice next2 = billing.state(platform.business(biz2)).invoice();
            Billing.Invoice next3 = billing.state(platform.business(biz3)).invoice();
            check(next2 != null && next3 != null, "next month's invoices");
            check(ana.status("POST", "/api/billing/pay", "invoice", "" + next2.id(), "reference", "555111", "amount", money(next2.amount()), "paidOn", Billing.today().toString()) == 201, "owner pays next month");
            box.add(eml("<fake@x>", "alerts@maribank.com.ph", "mx.google.com; dkim=none; dmarc=fail (p=REJECT) header.from=maribank.com.ph",
                    money(next2.amount()), "555111", false, time));
            mail.poll();
            check(billing.payment(pendingId(platform, next2.id())).status().equals("pending"), "an untrusted email approves nothing");
            check(admin.send("GET", "/api/billing").body().contains("\"status\":\"untrusted\""), "the admin sees the untrusted email");

            // wrong amount with the right reference: matched, not auto-approved, pointed out
            box.add(eml("<m3@x>", "alerts@maribank.com.ph", GOOD_AUTH, "10.00", "555111", false, time));
            mail.poll();
            long p2 = pendingId(platform, next2.id());
            check(billing.payment(p2).status().equals("pending"), "a different amount is not auto-approved");
            check(admin.send("GET", "/api/billing").body().contains("The MariBank email shows ₱10.00"), "the different amount is pointed out");
            check(admin.status("POST", "/api/payments/" + p2 + "/reject", "note", "Paid ₱10.00 only") == 200, "admin rejects it");
            check(mail.forReference("555111").status().equals("new"), "a rejected payment frees its email");

            // owner didn't send anything: one click approves from the email
            box.add(eml("<m4@x>", "alerts@maribank.com.ph", GOOD_AUTH, money(next3.amount()), "777222", false, time));
            mail.poll();
            MailWatch.Email e4 = mail.forReference("777222");
            check(Objects.equals(e4.invoiceId(), next3.id()) && e4.status().equals("new"), "amount-only: suggested, never auto-approved");
            check(admin.status("POST", "/api/bank-emails/" + e4.id() + "/apply") == 200, "admin approves it from the email");
            check(admin.status("POST", "/api/bank-emails/" + e4.id() + "/apply") == 409, "only once");
            check(billing.invoice(next3.id()).status().equals("paid"), "Gas Three's next month is paid");
            check(billing.payments(biz3).get(0).submittedBy().equals("bank email"), "the payment says it came from the email");

            // an amount no invoice asks: "not a fee"
            box.add(eml("<m5@x>", "alerts@maribank.com.ph", GOOD_AUTH, "250.00", "888333", false, time));
            mail.poll();
            MailWatch.Email e5 = mail.forReference("888333");
            check(e5.invoiceId() == null, "no invoice asks ₱250.00: no suggestion");
            check(admin.status("POST", "/api/bank-emails/" + e5.id() + "/apply") == 409, "nothing to approve");
            check(admin.status("POST", "/api/bank-emails/" + e5.id() + "/ignore") == 200, "marked as not a fee");
            check(mail.forReference("888333").status().equals("ignored"), "ignored");
        } finally {
            Billing.testToday = null;
        }

        // ---- a new mailbox address starts over; the folder's UIDVALIDITY change too
        box.validity++;
        check(mail.poll() == 0, "a new UIDVALIDITY re-reads the folder without saving anything twice");
        check(platform.setting("mail_last_error").isEmpty(), "the last check worked");
        check(admin.status("PUT", "/api/mail", "on", "0", "auto", "0", "user", "lpg.pay@gmail.com", "password", "") == 200, "turned off");
        int before = box.log.size();
        mail.tick();
        check(box.log.size() == before, "off: the mailbox isn't opened");
    }

    static long pendingId(Platform platform, long invoiceId) {
        return platform.query("SELECT id FROM payments WHERE invoice_id = ? ORDER BY id DESC", rs -> rs.getLong(1), invoiceId).get(0);
    }

    static long business(PlatformCheck.Browser admin, String posUrl, Platform platform, String name, String owner) throws Exception {
        check(admin.status("POST", "/api/businesses", "name", name, "contactName", "X", "username", owner, "password", owner + "-pass-1234") == 201, "admin adds " + name);
        return platform.businesses().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow().id();
    }

    static PlatformCheck.Browser login(String posUrl, String user) throws Exception {
        PlatformCheck.Browser b = new PlatformCheck.Browser(posUrl);
        check(b.status("POST", "/api/login", "username", user, "password", user + "-pass-1234") == 200, user + " signs in");
        return b;
    }

    static String money(int centavos) {
        return java.math.BigDecimal.valueOf(centavos, 2).toPlainString();
    }

    // ---------------------------------------------------------------- made-up emails

    static byte[] eml(String id, String from, String auth, String amount, String ref, boolean base64) {
        return eml(id, from, auth, amount, ref, base64, "25 Oct 2026 11:49");
    }

    /** Shaped like MariBank's real alert: one HTML part, quoted-printable (or base64). */
    static byte[] eml(String id, String from, String auth, String amount, String ref, boolean base64, String time) {
        String html = "<!DOCTYPE html><html><head><style>a { text-decoration: none; }</style></head><body><table><tr><td>"
                + "Dear JUAN D.,<br>You have received a funds transfer to your account.</td></tr>"
                + "<tr><td>Transaction time:</td><td>" + time + "</td></tr><tr><td>Transfer from:</td><td>GoTyme Bank - 6428</td></tr>"
                + "<tr><td>Transfer to:</td><td>JUAN D. - 1234</td></tr><tr><td>Transfer amount:</td><td>PHP&nbsp;" + amount + "</td></tr>"
                + "<tr><td>Reference No:</td><td>" + ref + "</td></tr></table>Yours Sincerely, MariBank Philippines</body></html>";
        String body = base64 ? Base64.getMimeEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)) : qp(html);
        String b = "=--1893797503_1792900191222_21133663";
        return ("Delivered-To: lpg.pay@gmail.com\r\n"
                + (auth == null ? "" : "Authentication-Results: " + auth + "\r\n")
                + "Date: Sun, 25 Oct 2026 03:49:51 +0000 (UTC)\r\nFrom: MariBank PH Alerts <" + from + ">\r\nTo: lpg.pay@gmail.com\r\n"
                + "Message-ID: " + id + "\r\nSubject: MariBank Transfer Notification\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/alternative; boundary=\"" + b + "\"\r\n\r\n--" + b + "\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: " + (base64 ? "base64" : "quoted-printable") + "\r\n\r\n"
                + body + "\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8);
    }

    static String qp(String s) {
        StringBuilder out = new StringBuilder();
        int col = 0;
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            String piece = (c >= 33 && c <= 126 && c != '=' && c != '.') ? String.valueOf((char) c) : String.format("=%02X", c);
            if (col + piece.length() > 75) {
                out.append("=\r\n");
                col = 0;
            }
            out.append(piece);
            col += piece.length();
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- a fake IMAP mailbox

    static final class FakeImap implements Closeable {
        final ServerSocket server = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
        final List<byte[]> messages = new CopyOnWriteArrayList<>();
        final List<String> log = new CopyOnWriteArrayList<>();
        volatile String password = PASSWORD.replace(" ", "");
        volatile long validity = 7;

        FakeImap() throws IOException {
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        session(s);
                    } catch (IOException ignored) {
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() { return server.getLocalPort(); }

        void add(byte[] m) { messages.add(m); }

        private void session(Socket s) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            out.write("* OK fake IMAP ready\r\n".getBytes());
            String line;
            while ((line = in.readLine()) != null) {
                log.add(line);
                String[] p = line.split(" ", 2);
                String tag = p[0], cmd = p.length > 1 ? p[1] : "";
                String up = cmd.toUpperCase(Locale.ROOT);
                StringBuilder r = new StringBuilder();
                if (up.startsWith("LOGIN ")) {
                    r.append(cmd.endsWith("\"" + password + "\"") ? tag + " OK signed in\r\n" : tag + " NO [AUTHENTICATIONFAILED] Invalid credentials\r\n");
                } else if (up.startsWith("EXAMINE ")) {
                    r.append("* ").append(messages.size()).append(" EXISTS\r\n* OK [UIDVALIDITY ").append(validity).append("] UIDs valid\r\n")
                            .append(tag).append(" OK [READ-ONLY] done\r\n");
                } else if (up.startsWith("UID SEARCH ")) {
                    long after = 0;
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("UID (\\d+):\\*").matcher(up);
                    if (m.find()) after = Long.parseLong(m.group(1)) - 1;
                    r.append("* SEARCH");
                    for (int i = 0; i < messages.size(); i++) {
                        String head = new String(messages.get(i), StandardCharsets.ISO_8859_1);
                        if (i + 1 > after && head.contains("<alerts@maribank.com.ph>")) r.append(' ').append(i + 1);
                    }
                    r.append("\r\n").append(tag).append(" OK done\r\n");
                } else if (up.startsWith("UID FETCH ")) {
                    int uid = Integer.parseInt(cmd.split(" ")[2]);
                    byte[] msg = messages.get(uid - 1);
                    out.write(("* " + uid + " FETCH (UID " + uid + " BODY[] {" + msg.length + "}\r\n").getBytes());
                    out.write(msg);
                    r.append(")\r\n").append(tag).append(" OK done\r\n");
                } else if (up.startsWith("LOGOUT")) {
                    out.write(("* BYE\r\n" + tag + " OK bye\r\n").getBytes());
                    out.flush();
                    return;
                } else {
                    r.append(tag).append(" BAD unknown\r\n");
                }
                out.write(r.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end check of monthly billing over real HTTP, on a throw-away data folder: billing off and on, free days,
 * invoices with their own amounts, sending a payment, duplicate references, approving once only (also when clicked
 * many times at once), rejecting, grace days and the overdue lock, free stores, the QR code and receipt pictures,
 * and backups that include the pictures. The calendar is moved with Billing.testToday.
 *
 *   javac -d /tmp/lpg-check -cp "web/lib/*" web/src/*.java web/tests/*.java
 *   java -cp "/tmp/lpg-check:web/lib/*" BillingCheck
 */
public class BillingCheck {
    static final String PNG = "data:image/png;base64," + Base64.getEncoder().encodeToString(
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
    static final String FAKE = "data:image/png;base64," + Base64.getEncoder().encodeToString("<script>alert(1)</script>".getBytes());

    static void check(boolean ok, String what) { PlatformCheck.check(ok, what); }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("lpg-billing-check-");
        String salt = Auth.newSalt();
        Files.writeString(data.resolve("WebUsers.csv"), "robe," + salt + "," + Auth.hash("robe-pass-1", salt) + ",2026-01-01 08:00:00,1\n");
        Files.writeString(data.resolve("Customers.csv"), "1,Old,Customer,0917,Town\n");
        Platform platform = new Platform(data.resolve("platform.db"));
        Tenants tenants = new Tenants(data, platform);
        tenants.migrateLegacy(data, "Robe's LPG");
        check(platform.business(1).billingExempt(), "the platform owner's own store is free");

        Billing billing = new Billing(platform);
        Auth auth = new Auth();
        Path pub = Path.of("web/public");
        PosGate gate = new PosGate(platform, billing, tenants, auth, pub.resolve("app"), pub.resolve("shared"), 1, null);
        HttpServer pos = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pos.createContext("/", Http.wrap(gate));
        pos.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        pos.start();
        HttpServer adm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int adminPort = adm.getAddress().getPort();
        adm.createContext("/", Http.wrap(new AdminApi(platform, billing, tenants, gate, auth, new Tunnel(1, u -> {}), pub.resolve("admin"), pub.resolve("shared"), 1, adminPort)));
        adm.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        adm.start();
        try {
            run("http://localhost:" + pos.getAddress().getPort(), "http://localhost:" + adminPort, platform, tenants, billing, data);
        } finally {
            pos.stop(0);
            adm.stop(0);
            Billing.testToday = null;
        }
        System.out.println("PASS: " + PlatformCheck.pass + " billing checks (invoices, payments, approval, lock, QR, backups)");
    }

    static void run(String posUrl, String adminUrl, Platform platform, Tenants tenants, Billing billing, Path data) throws Exception {
        LocalDate day0 = LocalDate.now();
        PlatformCheck.Browser admin = new PlatformCheck.Browser(adminUrl);
        check(admin.status("POST", "/api/admin/setup", "password", "admin-secret-1") == 200, "admin password set");
        check(new PlatformCheck.Browser(adminUrl).status("GET", "/api/billing") == 401, "billing settings need admin sign-in");

        // ---- billing off: a new business is approved and works, nothing is owed
        PlatformCheck.Browser ana = new PlatformCheck.Browser(posUrl);
        check(ana.status("POST", "/api/register", "storeName", "Gas Two", "contactName", "Ana Cruz", "phone", "09171234567",
                "username", "ana", "password", "ana-pass-123") == 201, "business registers");
        long biz2 = platform.businesses().stream().filter(b -> b.name().equals("Gas Two")).findFirst().orElseThrow().id();
        check(admin.status("POST", "/api/businesses/" + biz2 + "/status", "status", "active") == 200, "admin approves");
        check(platform.business(biz2).paidUntil().isEmpty(), "no free days by default: nothing paid yet");
        check(ana.status("POST", "/api/login", "username", "ana", "password", "ana-pass-123") == 200, "owner signs in");
        check(ana.send("GET", "/api/me").body().contains("\"state\":\"free\""), "billing off: nothing owed");
        check(ana.status("POST", "/api/customers", "firstName", "Bea", "lastName", "Two", "contact", "", "address", "") == 201, "POS works");

        // ---- staff can't see billing
        check(ana.status("POST", "/api/team", "username", "ben", "fullName", "Ben", "password", "ben-pass-123") == 201, "owner adds staff");
        PlatformCheck.Browser ben = new PlatformCheck.Browser(posUrl);
        check(ben.status("POST", "/api/login", "username", "ben", "password", "ben-pass-123") == 200, "staff signs in");
        check(ben.status("GET", "/api/billing") == 403, "staff can't open billing");

        // ---- settings
        check(admin.status("PUT", "/api/billing", "on", "1", "price", "abc", "graceDays", "7", "trialDays", "7", "payee", "", "note", "") == 400, "bad price refused");
        check(admin.status("PUT", "/api/billing", "on", "1", "price", "10.00", "graceDays", "7", "trialDays", "0",
                "payee", "R. Abadecio (MariBank)", "note", "Put your store name in the message") == 200, "billing turned on");
        check(admin.send("GET", "/api/billing").body().contains("\"price\":1000"), "price saved in centavos");

        // ---- pay first: an existing business is locked until its first payment
        check(ana.send("GET", "/api/me").body().contains("\"state\":\"unpaid\""), "billing on: existing business must pay first");
        HttpResponse<String> first = ben.send("POST", "/api/customers", "firstName", "Not", "lastName", "Yet", "contact", "", "address", "");
        check(first.statusCode() == 402 && first.body().contains("hasn't been paid"), "unpaid: nothing new is saved");
        check(ben.status("GET", "/api/data") == 200, "unpaid: records can still be seen");
        String bill = ana.send("GET", "/api/billing").body();
        check(bill.contains("\"periodStart\":\"" + day0 + "\""), "first invoice starts today");
        long inv = num(bill, "\"invoice\":\\{\"id\":(\\d+)");
        int amount = (int) num(bill, "\"invoice\":\\{[^}]*\"amount\":(\\d+)");
        check(amount > 1000 && amount < 1100, "amount is ₱10 plus a few centavos: " + amount);
        check(ana.send("GET", "/api/billing").body().contains("\"id\":" + inv + ","), "same invoice next time");
        check(ana.send("GET", "/api/me").body().contains("\"state\":\"free\"") == false, "me shows the billing state");

        // a third business, set up by the admin, gets a different amount
        HttpResponse<String> add3 = admin.send("POST", "/api/businesses", "name", "Gas Three", "contactName", "Cy", "username", "cyrus", "password", "cy-pass-1234");
        check(add3.statusCode() == 201, "admin adds a business: " + add3.body());
        long biz3 = platform.businesses().stream().filter(b -> b.name().equals("Gas Three")).findFirst().orElseThrow().id();
        check(billing.state(platform.business(biz3)).state().equals("unpaid"), "a new business pays first");
        Billing.Invoice inv3 = billing.state(platform.business(biz3)).invoice();
        check(inv3 != null && inv3.amount() != amount, "open invoices never share an amount");
        check(billing.state(platform.business(1)).state().equals("free"), "store 1 stays free");

        // ---- sending a payment
        String paid = Billing.today().toString();
        check(ana.status("POST", "/api/billing/pay", "invoice", "" + inv, "reference", "12", "amount", "10.37", "paidOn", paid) == 400, "short reference refused");
        check(ana.status("POST", "/api/billing/pay", "invoice", "" + inv, "reference", "GT 1234 5678", "amount", "10.37", "paidOn", Billing.today().plusDays(1).toString()) == 400, "future date refused");
        check(ana.status("POST", "/api/billing/pay", "invoice", "" + inv, "reference", "GT 1234 5678", "amount", "10.37", "paidOn", paid, "receipt", FAKE) == 400, "a non-picture is refused");
        check(ana.status("POST", "/api/billing/pay", "invoice", "" + inv3.id(), "reference", "GT12345678", "amount", "10.37", "paidOn", paid) == 404, "can't pay another business's invoice");
        check(ana.status("POST", "/api/billing/pay", "invoice", "" + inv, "reference", "GT 1234-5678", "amount", "10.99", "paidOn", paid, "receipt", PNG) == 201, "payment sent with a receipt");
        check(ana.status("POST", "/api/billing/pay", "invoice", "" + inv, "reference", "GT99999999", "amount", "10.99", "paidOn", paid) == 409, "one payment waiting per invoice");
        PlatformCheck.Browser cy = new PlatformCheck.Browser(posUrl);
        check(cy.status("POST", "/api/login", "username", "cyrus", "password", "cy-pass-1234") == 200, "third owner signs in");
        HttpResponse<String> dup = cy.send("POST", "/api/billing/pay", "invoice", "" + inv3.id(), "reference", "gt12345678", "amount", "10.00", "paidOn", paid);
        check(dup.statusCode() == 409 && dup.body().contains("already sent"), "a reference can be used once, across businesses");

        // ---- the admin's queue
        String queue = admin.send("GET", "/api/billing").body();
        check(queue.contains("\"reference\":\"GT12345678\"") && queue.contains("\"status\":\"pending\""), "payment waits in the queue");
        check(queue.contains("the invoice asks"), "a wrong amount is pointed out");
        long pay1 = num(queue, "\"id\":(\\d+),\"invoiceId\":" + inv);
        HttpResponse<String> pic = admin.send("GET", "/api/payments/" + pay1 + "/receipt");
        check(pic.statusCode() == 200 && pic.headers().firstValue("Content-Type").orElse("").equals("image/png"), "admin sees the receipt picture");
        check(ana.status("GET", "/api/payments/" + pay1 + "/receipt") != 200, "the receipt isn't served on the POS side");

        // reject: the reference is free again, the invoice stays open
        check(admin.status("POST", "/api/payments/" + pay1 + "/reject", "note", "Wrong amount") == 200, "admin rejects");
        check(admin.status("POST", "/api/payments/" + pay1 + "/approve") == 409, "a rejected payment can't be approved");
        check(ana.send("GET", "/api/billing").body().contains("Wrong amount"), "owner sees why");
        check(ana.status("POST", "/api/billing/pay", "invoice", "" + inv, "reference", "GT12345678", "amount", "" + amount / 100.0, "paidOn", paid) == 201, "owner sends it again");

        // approve, many clicks at once: applied exactly once
        long pay2 = billing.payments(biz2).get(0).id();
        String before = platform.business(biz2).paidUntil();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> clicks = new ArrayList<>();
        for (int i = 0; i < 8; i++) clicks.add(pool.submit(() -> admin.status("POST", "/api/payments/" + pay2 + "/approve")));
        int ok = 0;
        for (Future<Integer> f : clicks) if (f.get() == 200) ok++;
        pool.shutdown();
        check(ok == 1, "approved once, however many clicks: " + ok);
        String after = platform.business(biz2).paidUntil();
        check(before.isEmpty() && after.equals(day0.plusMonths(1).minusDays(1).toString()), "first payment: one month from today: " + after);
        check(billing.invoice(inv).status().equals("paid"), "invoice paid");
        check(ana.send("GET", "/api/me").body().contains("\"state\":\"active\""), "active after paying");
        check(ben.status("POST", "/api/customers", "firstName", "Paid", "lastName", "Up", "contact", "", "address", "") == 201, "POS works after paying");

        // ---- due soon: the next invoice, a week before
        LocalDate until = LocalDate.parse(after);
        Billing.testToday = until.minusDays(3);
        String next = ana.send("GET", "/api/billing").body();
        check(next.contains("\"state\":\"due\"") && next.contains("\"periodStart\":\"" + until.plusDays(1) + "\""), "a week before the end: next month's invoice");

        // ---- grace, then the lock
        Billing.testToday = until.plusDays(1);
        check(ana.send("GET", "/api/me").body().contains("\"state\":\"grace\""), "after the paid date: grace");
        check(ben.status("POST", "/api/customers", "firstName", "Grace", "lastName", "Day", "contact", "", "address", "") == 201, "POS works in grace");
        Billing.testToday = until.plusDays(8);
        check(ana.send("GET", "/api/me").body().contains("\"state\":\"overdue\""), "after the grace days: overdue");
        HttpResponse<String> locked = ben.send("POST", "/api/customers", "firstName", "Late", "lastName", "Day", "contact", "", "address", "");
        check(locked.statusCode() == 402 && locked.body().contains("Ask the store owner"), "overdue: nothing new is saved");
        check(ben.status("GET", "/api/data") == 200, "overdue: records can still be seen");
        check(ana.status("GET", "/api/team") == 200, "overdue: the owner can still manage staff");
        String late = ana.send("GET", "/api/billing").body();
        long inv2 = num(late, "\"invoice\":\\{\"id\":(\\d+)");
        check(ana.status("POST", "/api/billing/pay", "invoice", "" + inv2, "reference", "GT55556666", "amount", "10.50", "paidOn", Billing.today().toString()) == 201, "overdue owner can pay");
        check(admin.status("POST", "/api/payments/" + billing.payments(biz2).get(0).id() + "/approve") == 200, "admin approves");
        check(platform.business(biz2).paidUntil().equals(Billing.today().plusMonths(1).minusDays(1).toString()), "after a lock, the month starts on the day of approval");
        check(ben.status("POST", "/api/customers", "firstName", "Back", "lastName", "Again", "contact", "", "address", "") == 201, "POS works again");

        // ---- free days, if the admin gives them
        check(admin.status("PUT", "/api/billing", "on", "1", "price", "10.00", "graceDays", "7", "trialDays", "14", "payee", "", "note", "") == 200, "admin gives 14 free days");
        HttpResponse<String> add4 = admin.send("POST", "/api/businesses", "name", "Gas Four", "contactName", "Di", "username", "diane", "password", "di-pass-1234");
        long biz4 = num(add4.body(), "\"id\":(\\d+)");
        check(platform.business(biz4).paidUntil().equals(Billing.today().plusDays(13).toString()) && !billing.state(platform.business(biz4)).locked(), "free days: works at once");

        // ---- admin tools: free of charge, paid until
        check(admin.status("POST", "/api/businesses/" + biz3 + "/billing", "exempt", "1") == 200, "admin makes a store free");
        check(billing.state(platform.business(biz3)).state().equals("free") && billing.openInvoice(biz3) == null, "free store: open invoice cancelled");
        check(admin.status("POST", "/api/businesses/" + biz3 + "/billing", "exempt", "0") == 200, "admin starts charging again");
        check(!billing.state(platform.business(biz3)).locked(), "starting to charge again keeps the free days");
        check(admin.status("POST", "/api/businesses/" + biz3 + "/billing", "paidUntil", "2099-12-31") == 200, "admin sets a paid-until date (cash)");
        check(billing.state(platform.business(biz3)).state().equals("active"), "paid far ahead: active");

        // ---- QR code
        check(ana.status("GET", "/api/billing/qr") == 404, "no QR yet");
        check(admin.status("POST", "/api/billing/qr", "image", FAKE) == 400, "QR must be a real picture");
        check(admin.status("POST", "/api/billing/qr", "image", PNG) == 200, "admin uploads the QR");
        HttpResponse<String> qr = ana.send("GET", "/api/billing/qr");
        check(qr.statusCode() == 200 && qr.headers().firstValue("Cache-Control").orElse("").contains("no-store"), "owner sees the QR");
        check(ben.status("GET", "/api/billing/qr") == 403, "staff don't");

        // ---- billing off again: nobody is locked
        Billing.testToday = until.plusYears(1);
        check(admin.status("PUT", "/api/billing", "on", "0", "price", "10", "graceDays", "7", "trialDays", "7", "payee", "", "note", "") == 200, "billing off");
        check(ben.status("POST", "/api/customers", "firstName", "Off", "lastName", "Again", "contact", "", "address", "") == 201, "billing off: never locked");

        // ---- audit and backups
        String audit = admin.send("GET", "/api/platform/audit?business=" + biz2).body();
        check(audit.contains("Approved payment") && audit.contains("Rejected payment") && audit.contains("Sent a payment"), "billing in the audit log");
        Path backup = Backups.run(platform, tenants);
        check(Files.exists(backup.resolve("platform-files/payment-qr.png")) && Files.exists(backup.resolve("platform-files/receipts/" + pay1 + ".png")), "backup includes the pictures");
    }

    static long num(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        check(m.find(), "found " + regex + " in " + body.substring(0, Math.min(400, body.length())));
        return Long.parseLong(m.group(1));
    }
}

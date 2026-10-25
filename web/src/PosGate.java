import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The public POS server: static files, sign-in, business and staff sign-up, and the store owner's Team page. Every
 * other API call goes to the PosApi of the signed-in person's own business. The business is taken from the server-side
 * session and re-checked against the platform on every call, so a disabled account or suspended business stops at once.
 */
final class PosGate implements Http.Handler {
    static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,32}");
    private static final Pattern PHONE = Pattern.compile("[0-9 +()-]{7,20}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s]{2,}");

    private final Platform platform;
    private final Billing billing;
    private final Tenants tenants;
    private final Auth auth;
    private final Path appRoot, sharedRoot;
    private final String autoUser;
    /** Per-port name, since browsers share cookies across ports of the same host (e.g. demo vs real POS). */
    private final String COOKIE;
    private final Map<Long, PosApi> apis = new ConcurrentHashMap<>();

    PosGate(Platform platform, Billing billing, Tenants tenants, Auth auth, Path appRoot, Path sharedRoot, int port, String autoUser) {
        this.platform = platform;
        this.billing = billing;
        this.tenants = tenants;
        this.auth = auth;
        this.appRoot = appRoot;
        this.sharedRoot = sharedRoot;
        this.autoUser = autoUser;
        this.COOKIE = "lpg_session_" + port;
    }

    @Override
    public void handle(Http.Req r) throws IOException {
        if (!r.path.startsWith("/api/")) {
            Http.serveStatic(r, appRoot, sharedRoot);
            return;
        }
        Http.requireCsrfHeader(r);
        switch (r.method + " " + r.path) {
            case "POST /api/login" -> {
                login(r);
                return;
            }
            case "POST /api/logout" -> {
                Auth.Session s = auth.get(r.cookie(COOKIE));
                if (s != null) tenants.get(s.businessId).log().add(s.username, r.clientIp(), "Signed out");
                auth.end(r.cookie(COOKIE));
                r.addHeader("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                r.json(200, Json.obj("ok", true));
                return;
            }
            case "POST /api/register" -> {
                register(r);
                return;
            }
            case "POST /api/join" -> {
                join(r);
                return;
            }
            default -> {
            }
        }

        Auth.Session s = session(r);
        Platform.Account a = platform.account(s.username);
        String[] seg = r.path.substring("/api/".length()).split("/");
        switch (seg[0]) {
            case "me" -> {
                if (seg.length == 1 && r.method.equals("GET")) {
                    r.json(200, me(s, a));
                    return;
                }
            }
            case "team" -> {
                team(r, s, a, seg);
                return;
            }
            case "business" -> {
                business(r, s, a);
                return;
            }
            case "billing" -> {
                billing(r, s, a, seg);
                return;
            }
            default -> {
            }
        }
        // Not paid yet, or overdue: records can still be looked at (and passwords changed), but nothing new is saved.
        Billing.State bill = r.method.equals("GET") || seg[0].equals("me") ? null : billing.state(platform.business(s.businessId));
        if (bill != null && bill.locked()) {
            String why = bill.state().equals("unpaid") ? "hasn't been paid yet" : "is overdue";
            throw new Http.Error(402, a.owner()
                    ? "Your LPG POS subscription " + why + ", so new records can't be saved. Pay on the Billing page (account menu) to continue."
                    : "The store's LPG POS subscription " + why + ", so new records can't be saved. Ask the store owner to pay.");
        }
        apis.computeIfAbsent(s.businessId, id -> {
            Tenants.Tenant t = tenants.get(id);
            return new PosApi(t.store(), auth, t.log());
        }).handle(r, s);
    }

    /** The caller's session, if their account and business are still active (otherwise it ends here). */
    private Auth.Session session(Http.Req r) {
        Auth.Session s = auth.get(r.cookie(COOKIE));
        if (s == null && autoUser != null) {
            Platform.Account a = platform.account(autoUser);
            if (a != null && a.active()) {
                s = auth.create(a.username(), a.businessId(), r.clientIp(), r.via(), r.header("User-Agent"));
                setSessionCookie(r, s);
                tenants.get(a.businessId()).log().add(a.username(), r.clientIp(), "Opened the demo POS (no sign-in)");
            }
        }
        if (s == null) throw new Http.Error(401, "Please sign in");
        String problem = blocked(platform.account(s.username), s.businessId);
        if (problem != null) {
            auth.end(s.token);
            throw new Http.Error(401, problem);
        }
        return s;
    }

    /** Why this account can't use the POS right now, or null when it can. */
    private String blocked(Platform.Account a, long businessId) {
        if (a == null || a.businessId() != businessId) return "Please sign in";
        if (a.status().equals("pending")) return a.owner()
                ? "Your business registration is waiting for approval. We'll let you know once it's approved."
                : "Your account is waiting for the store owner's approval.";
        if (!a.active()) return "This account has been disabled. Ask the store owner.";
        Platform.Business b = platform.business(businessId);
        if (b == null) return "Please sign in";
        return switch (b.status()) {
            case "active" -> null;
            case "pending" -> "Your business registration is waiting for approval. We'll let you know once it's approved.";
            case "suspended" -> b.name() + " is suspended. Contact LPG POS support.";
            default -> b.name() + " is no longer active. Contact LPG POS support.";
        };
    }

    // ---------------------------------------------------------------- sign-in

    private void login(Http.Req r) throws IOException {
        String ip = r.clientIp();
        String username = r.param("username").toLowerCase(Locale.ROOT);
        String password = r.form().getOrDefault("password", "");
        if (auth.throttled(ip, username)) throw new Http.Error(429, "Too many failed attempts. Wait 15 minutes and try again.");
        Platform.Account a = USERNAME.matcher(username).matches() ? platform.account(username) : null;
        DataStore.User user = null;
        if (a != null) {
            tenants.activate(a);
            user = a.pendingHash().isEmpty() || a.active()
                    ? tenants.get(a.businessId()).store().user(a.username())
                    : new DataStore.User(a.username(), a.pendingSalt(), a.pendingHash(), a.created(), false);
        }
        if (!Auth.verify(password, user)) {
            auth.recordFailure(ip, username);
            if (a != null) tenants.get(a.businessId()).log().add(username, ip, "Failed sign-in (" + r.via() + ")");
            throw new Http.Error(401, "Wrong username or password");
        }
        auth.clearFailures(ip, username);
        // The password was right: now it's safe to say why they can't come in, if they can't.
        String problem = blocked(a, a.businessId());
        if (problem != null) throw new Http.Error(403, problem);
        Auth.Session s = auth.create(a.username(), a.businessId(), ip, r.via(), r.header("User-Agent"));
        setSessionCookie(r, s);
        tenants.get(a.businessId()).log().add(a.username(), ip, "Signed in (" + r.via() + ")");
        r.json(200, me(s, a));
    }

    private void setSessionCookie(Http.Req r, Auth.Session s) {
        r.addHeader("Set-Cookie", COOKIE + "=" + s.token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                + Auth.SESSION_IDLE_MS / 1000 + (r.secure() ? "; Secure" : ""));
    }

    private Map<String, Object> me(Auth.Session s, Platform.Account a) {
        DataStore.User u = tenants.get(s.businessId).store().user(s.username);
        Platform.Business b = platform.business(s.businessId);
        Billing.State bs = billing.state(b);
        return Json.obj("username", s.username, "demo", autoUser != null, "dashboard", u != null && u.dashboard(),
                "role", a.role(), "business", Json.obj("name", b.name(), "code", a.owner() ? b.code() : null),
                "billing", Json.obj("state", bs.state(), "paidUntil", bs.paidUntil(), "lockDate", bs.lockDate()));
    }

    // ---------------------------------------------------------------- sign-up

    /** A new business and its owner (Business Admin); both wait for the platform's approval. */
    private void register(Http.Req r) throws IOException {
        if (!auth.allow("register:" + r.clientIp(), 5)) throw new Http.Error(429, "Too many sign-ups from this connection. Try again in an hour.");
        String name = text(r, "storeName", "Store name", 2, 80, true);
        String contact = text(r, "contactName", "Your name", 2, 80, true);
        String phone = text(r, "phone", "Mobile number", 0, 20, true);
        if (!PHONE.matcher(phone).matches()) throw new Http.Error(400, "Enter a valid mobile number");
        String email = text(r, "email", "Email", 0, 254, false);
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) throw new Http.Error(400, "Enter a valid email address");
        String address = text(r, "address", "Address", 0, 200, false);
        String username = username(r);
        String password = password(r);
        String salt = Auth.newSalt();
        platform.register(name, contact, phone, email, address, username, salt, Auth.hash(password, salt), username);
        r.json(201, Json.obj("ok", true));
    }

    /** A staff member asks to join a store with its code; the store owner approves them on the Team page. */
    private void join(Http.Req r) throws IOException {
        if (!auth.allow("join:" + r.clientIp(), 10)) throw new Http.Error(429, "Too many sign-ups from this connection. Try again in an hour.");
        Platform.Business b = platform.businessByCode(r.param("code"));
        if (b == null || !b.status().equals("active")) throw new Http.Error(400, "No store has that code. Ask the store owner for it.");
        String fullName = text(r, "fullName", "Your name", 2, 80, true);
        String username = username(r);
        String password = password(r);
        String salt = Auth.newSalt();
        platform.addStaff(b.id(), username, fullName, "pending", salt, Auth.hash(password, salt), username);
        tenants.get(b.id()).log().add(username, r.clientIp(), "Asked to join as staff");
        r.json(201, Json.obj("ok", true, "store", b.name()));
    }

    // ---------------------------------------------------------------- store owner

    /** Team page: the owner sees and manages their own business's accounts only. */
    private void team(Http.Req r, Auth.Session s, Platform.Account me, String[] seg) throws IOException {
        if (!me.owner()) throw new Http.Error(403, "Only the store owner can manage staff");
        long biz = s.businessId;
        DataStore store = tenants.get(biz).store();
        ActivityLog log = tenants.get(biz).log();
        if (seg.length == 1 && r.method.equals("GET")) {
            r.json(200, Json.obj("accounts", accountsOut(biz, store), "code", platform.business(biz).code()));
            return;
        }
        if (seg.length == 1 && r.method.equals("POST")) {
            String username = username(r);
            String fullName = text(r, "fullName", "Name", 0, 80, false);
            String password = password(r);
            String salt = Auth.newSalt();
            platform.addStaff(biz, username, fullName, "active", salt, Auth.hash(password, salt), s.username);
            tenants.activate(platform.account(username));
            log.add(s.username, r.clientIp(), "Created staff account " + username);
            r.json(201, Json.obj("ok", true));
            return;
        }
        if (seg.length != 3 || !r.method.equals("POST")) throw new Http.Error(404, "Unknown endpoint");
        String username = seg[1].toLowerCase(Locale.ROOT);
        Platform.Account a = platform.account(username);
        if (a == null || a.businessId() != biz) throw new Http.Error(404, "No such account");
        if (a.owner()) throw new Http.Error(409, "Change your own account from the account menu");
        String ip = r.clientIp();
        switch (seg[2]) {
            case "approve", "enable" -> {
                tenants.activate(platform.setAccountStatus(biz, username, "active", s.username));
                log.add(s.username, ip, (seg[2].equals("approve") ? "Approved staff " : "Enabled staff ") + username);
            }
            case "reject" -> {
                platform.rejectPending(biz, username, s.username);
                log.add(s.username, ip, "Rejected staff sign-up " + username);
            }
            case "disable" -> {
                platform.setAccountStatus(biz, username, "disabled", s.username);
                auth.revokeUser(username, null);
                log.add(s.username, ip, "Disabled staff " + username);
            }
            case "dashboard" -> {
                boolean on = "1".equals(r.param("on"));
                if (!a.active() || !store.setDashboard(username, on)) throw new Http.Error(409, "Approve the account first");
                platform.audit(s.username, biz, (on ? "Let " + username + " see the dashboard" : "Removed dashboard access for " + username), "");
                log.add(s.username, ip, on ? "Let " + username + " see the dashboard" : "Removed dashboard access for " + username);
            }
            case "password" -> {
                String password = password(r);
                String salt = Auth.newSalt();
                if (!a.active() || !store.setPassword(username, salt, Auth.hash(password, salt))) throw new Http.Error(409, "Approve the account first");
                auth.revokeUser(username, null);
                platform.audit(s.username, biz, "Reset password for " + username, "");
                log.add(s.username, ip, "Reset password for " + username);
            }
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
        r.json(200, Json.obj("ok", true));
    }

    List<Object> accountsOut(long biz, DataStore store) {
        Set<String> online = new HashSet<>();
        for (Auth.Session x : auth.active()) if (x.businessId == biz) online.add(x.username);
        List<Object> out = new ArrayList<>();
        for (Platform.Account a : platform.accounts(biz)) {
            DataStore.User u = store.user(a.username());
            out.add(Json.obj("username", a.username(), "fullName", a.fullName(), "role", a.role(), "status", a.status(),
                    "created", a.created(), "dashboard", u != null && u.dashboard(), "online", online.contains(a.username())));
        }
        return out;
    }

    private void business(Http.Req r, Auth.Session s, Platform.Account me) throws IOException {
        Platform.Business b = platform.business(s.businessId);
        if (r.method.equals("GET")) {
            r.json(200, Json.obj("name", b.name(), "contactName", b.contactName(), "phone", b.phone(), "email", b.email(),
                    "address", b.address(), "code", me.owner() ? b.code() : null));
            return;
        }
        if (!r.method.equals("PUT")) throw new Http.Error(404, "Unknown endpoint");
        if (!me.owner()) throw new Http.Error(403, "Only the store owner can change the store's details");
        String phone = text(r, "phone", "Mobile number", 0, 20, true);
        if (!PHONE.matcher(phone).matches()) throw new Http.Error(400, "Enter a valid mobile number");
        String email = text(r, "email", "Email", 0, 254, false);
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) throw new Http.Error(400, "Enter a valid email address");
        platform.updateProfile(s.businessId, text(r, "name", "Store name", 2, 80, true), text(r, "contactName", "Contact name", 0, 80, false),
                phone, email, text(r, "address", "Address", 0, 200, false), s.username);
        tenants.get(s.businessId).log().add(s.username, r.clientIp(), "Updated the store's details");
        r.json(200, Json.obj("ok", true));
    }

    /** Billing page (store owner): what is owed, how to pay, and sending a payment's reference number. */
    private void billing(Http.Req r, Auth.Session s, Platform.Account me, String[] seg) throws IOException {
        if (!me.owner()) throw new Http.Error(403, "Only the store owner can see billing");
        Platform.Business b = platform.business(s.businessId);
        String route = r.method + " " + String.join("/", Arrays.copyOfRange(seg, 1, seg.length));
        switch (route) {
            case "GET " -> {
                Map<String, Object> set = billing.settings();
                List<Object> invoices = new ArrayList<>(), payments = new ArrayList<>();
                for (Billing.Invoice i : billing.invoices(b.id())) invoices.add(Billing.invoiceOut(i));
                for (Billing.Payment p : billing.payments(b.id())) payments.add(Billing.paymentOut(p));
                r.json(200, Json.obj("summary", billing.summary(b), "price", set.get("price"), "graceDays", set.get("graceDays"),
                        "payee", set.get("payee"), "note", set.get("note"), "qr", set.get("qr"), "invoices", invoices, "payments", payments));
            }
            case "GET qr" -> {
                Path qr = billing.qrFile();
                if (qr == null) throw new Http.Error(404, "No QR code yet");
                r.image(qr.toString().endsWith(".png") ? "image/png" : "image/jpeg", java.nio.file.Files.readAllBytes(qr));
            }
            case "POST pay" -> {
                if (!auth.allow("pay:" + s.businessId, 10)) throw new Http.Error(429, "Too many tries. Try again in an hour.");
                r.allowLargeBody();
                long invoiceId;
                try {
                    invoiceId = Long.parseLong(r.param("invoice"));
                } catch (NumberFormatException e) {
                    throw new Http.Error(400, "No invoice picked");
                }
                String reference = Billing.reference(r.param("reference"));
                int amount = Billing.centavos(r.param("amount"), "Amount paid");
                java.time.LocalDate paidOn = Billing.date(r.param("paidOn"), "Date paid");
                if (paidOn.isAfter(Billing.today()) || paidOn.isBefore(Billing.today().minusDays(60))) throw new Http.Error(400, "Date paid: within the last 60 days");
                String pic = r.form().getOrDefault("receipt", "");
                long id = billing.submit(b.id(), invoiceId, reference, amount, paidOn.toString(), pic.isEmpty() ? null : Billing.image(pic), s.username);
                tenants.get(b.id()).log().add(s.username, r.clientIp(), "Sent payment " + Billing.peso(amount) + " (ref " + reference + ")");
                r.json(201, Json.obj("ok", true, "id", id));
            }
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
    }

    // ---------------------------------------------------------------- input checks (also used by AdminApi)

    static String username(Http.Req r) throws IOException {
        String u = r.param("username").toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(u).matches()) throw new Http.Error(400, "Username: 3–32 characters, letters, numbers, dot, dash or underscore");
        return u;
    }

    static String password(Http.Req r) throws IOException {
        String p = r.form().getOrDefault("password", "");
        if (p.length() < 8) throw new Http.Error(400, "Password must be at least 8 characters");
        if (p.length() > 200) throw new Http.Error(400, "Password is too long");
        return p;
    }

    static String text(Http.Req r, String name, String label, int min, int max, boolean required) throws IOException {
        String v = r.param(name);
        if (required && v.isEmpty()) throw new Http.Error(400, label + " is required");
        if (!v.isEmpty() && v.length() < min) throw new Http.Error(400, label + " is too short");
        if (v.length() > max) throw new Http.Error(400, label + " is too long (at most " + max + " characters)");
        return v;
    }
}

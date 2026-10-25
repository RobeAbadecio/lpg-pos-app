import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Platform admin dashboard, bound to 127.0.0.1 only, so it is reachable from this machine and nowhere else (the
 * tunnel forwards the POS port only). It also needs the platform admin password, set on first visit. The Host check
 * blocks DNS-rebinding pages from reaching it via the browser.
 *
 * The store pages (sales, stock, staff, database fixes) show one business at a time: the one picked on the
 * Businesses page, remembered in the admin's session.
 */
final class AdminApi implements Http.Handler {
    static final String ADMIN = "platform admin";

    private final Platform platform;
    private final Billing billing;
    private final MailWatch mail;
    private final Tenants tenants;
    private final PosGate gate;
    private final Auth auth;
    private final Auth adminAuth = new Auth();
    private final Map<String, Long> selected = new java.util.concurrent.ConcurrentHashMap<>();
    private final Tunnel tunnel;
    private final Path appRoot, sharedRoot;
    private final int posPort, adminPort;
    private final String COOKIE;
    private final long startedAt = System.currentTimeMillis();

    AdminApi(Platform platform, Billing billing, MailWatch mail, Tenants tenants, PosGate gate, Auth auth, Tunnel tunnel, Path appRoot, Path sharedRoot, int posPort, int adminPort) {
        this.platform = platform;
        this.billing = billing;
        this.mail = mail;
        this.tenants = tenants;
        this.gate = gate;
        this.auth = auth;
        this.tunnel = tunnel;
        this.appRoot = appRoot;
        this.sharedRoot = sharedRoot;
        this.posPort = posPort;
        this.adminPort = adminPort;
        this.COOKIE = "lpg_admin_" + adminPort;
    }

    @Override
    public void handle(Http.Req r) throws IOException {
        String host = Optional.ofNullable(r.header("Host")).orElse("").toLowerCase(Locale.ROOT);
        if (!r.fromLoopback() || r.header("CF-Connecting-IP") != null || !(host.equals("localhost:" + adminPort) || host.equals("127.0.0.1:" + adminPort)
                || host.equals("[::1]:" + adminPort))) {
            throw new Http.Error(403, "The admin dashboard only opens on this machine at http://localhost:" + adminPort);
        }
        if (!r.path.startsWith("/api/")) {
            Http.serveStatic(r, appRoot, sharedRoot);
            return;
        }
        Http.requireCsrfHeader(r);
        if (r.path.startsWith("/api/admin/")) {
            adminAccount(r, r.path.substring("/api/admin/".length()));
            return;
        }
        Auth.Session admin = adminAuth.get(r.cookie(COOKIE));
        if (admin == null) throw new Http.Error(401, "Please sign in");

        String[] seg = r.path.substring("/api/".length()).split("/");
        if (List.of("platform", "businesses", "accounts", "billing", "payments", "mail", "bank-emails").contains(seg[0])) {
            platformRoute(r, admin, seg);
            return;
        }
        Tenants.Tenant tenant = tenants.get(selectedBusiness(admin));
        DataStore store = tenant.store();
        ActivityLog log = tenant.log();
        String id = seg.length > 1 ? seg[1] : null;
        String action = seg.length > 2 ? seg[2] : null;
        String route = r.method + " " + seg[0] + (id == null ? "" : "/:id") + (action == null ? "" : "/" + action);
        switch (route) {
            case "GET database" -> r.json(200, Json.obj("tables", new ArrayList<>(DatabaseEditor.SCHEMAS.keySet())));
            case "GET database/:id" -> r.json(200, store.adminRecords(id));
            case "PUT database/:id" -> {
                String[] fields = DatabaseEditor.fields(id);
                String[] row = new String[fields.length];
                for (int i = 0; i < row.length; i++) row[i] = r.form().getOrDefault("field" + i, "");
                r.json(200, store.correctRecord(id, r.param("mode"), r.param("recordId"),
                        r.param("revision"), row, r.param("reason"), log));
            }
            case "GET overview" -> r.json(200, overview(store, log, tenant.id()));
            case "GET stats" -> r.json(200, Stats.compute(store, r.query().getOrDefault("range", "30")));
            case "GET daily" -> r.json(200, Stats.daily(store, r.query().getOrDefault("date", ""), 14));
            case "GET revenue" -> r.json(200, Stats.revenue(store, r.query().getOrDefault("period", "day")));
            case "GET supplier-summary" -> r.json(200, Stats.suppliers(store, r.query().getOrDefault("period", "day"), r.query().getOrDefault("supplier", "")));
            case "GET explain" -> r.json(200, Explain.explain(store, r.query()));
            case "GET payroll" -> r.json(200, store.payroll(java.time.LocalDate.now()));
            case "GET reports/:id" -> {
                Map<String, String> q = r.query();
                String period = q.getOrDefault("period", "day"), from = q.getOrDefault("from", ""), to = q.getOrDefault("to", "");
                LocalDateTime now = LocalDateTime.now();
                r.json(200, switch (id) {
                    case "sales" -> Reports.sales(store, period, from, to, now);
                    case "suppliers" -> Reports.suppliers(store, period, q.getOrDefault("supplier", ""), from, to, now);
                    case "money" -> Reports.money(store, period, from, to, now);
                    default -> throw new Http.Error(404, "Unknown report");
                });
            }

            case "POST users" -> {
                String username = PosGate.username(r);
                String password = PosGate.password(r);
                String salt = Auth.newSalt();
                platform.addStaff(tenant.id(), username, "", "active", salt, Auth.hash(password, salt), ADMIN);
                tenants.activate(platform.account(username));
                log.add("admin", "this machine", "Created staff account " + username);
                r.json(201, Json.obj("ok", true));
            }
            case "PUT users/:id/password" -> {
                String password = PosGate.password(r);
                String salt = Auth.newSalt();
                if (!store.setPassword(id, salt, Auth.hash(password, salt))) throw new Http.Error(404, "No such account");
                auth.revokeUser(id, null);
                platform.audit(ADMIN, tenant.id(), "Reset password for " + id, "");
                log.add("admin", "this machine", "Reset password for " + id);
                r.json(200, Json.obj("ok", true));
            }
            case "PUT users/:id/dashboard" -> {
                boolean on = "1".equals(r.param("on"));
                if (!store.setDashboard(id, on)) throw new Http.Error(404, "No such account");
                log.add("admin", "this machine", (on ? "Let " + id + " see the dashboard" : "Removed dashboard access for " + id));
                r.json(200, Json.obj("ok", true));
            }
            case "DELETE users/:id" -> {
                platform.deleteAccount(tenant.id(), id, ADMIN);
                store.deleteUser(id);
                auth.revokeUser(id, null);
                log.add("admin", "this machine", "Deleted staff account " + id);
                r.json(200, Json.obj("ok", true));
            }
            case "GET password" -> r.json(200, Json.obj("password", Auth.randomPassword()));
            case "DELETE sessions/:id" -> {
                Auth.Session s = auth.active().stream().filter(x -> x.id.equals(id) && x.businessId == tenant.id()).findFirst().orElse(null);
                if (s == null) throw new Http.Error(404, "Session already ended");
                auth.revokeById(id);
                log.add("admin", "this machine", "Signed out " + s.username + " (" + s.ip + ")");
                r.json(200, Json.obj("ok", true));
            }
            case "POST tunnel/:id" -> {
                if ("start".equals(id)) {
                    tunnel.start();
                    platform.audit(ADMIN, null, "Started public link", "");
                } else if ("stop".equals(id)) {
                    tunnel.stop();
                    platform.audit(ADMIN, null, "Stopped public link", "");
                } else {
                    throw new Http.Error(404, "Unknown endpoint");
                }
                r.json(200, tunnel.info());
            }
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
    }

    private Map<String, Object> overview(DataStore store, ActivityLog log, long businessId) {
        List<Auth.Session> sessions = auth.active().stream().filter(x -> x.businessId == businessId).toList();
        Set<String> online = new HashSet<>();
        List<Object> sessionOut = new ArrayList<>();
        for (Auth.Session s : sessions) {
            online.add(s.username);
            sessionOut.add(Json.obj("id", s.id, "username", s.username, "ip", s.ip, "via", s.via,
                    "device", device(s.userAgent), "since", iso(s.created), "lastSeen", iso(s.lastSeen)));
        }
        List<Object> users = new ArrayList<>();
        for (DataStore.User u : store.users()) {
            users.add(Json.obj("username", u.username(), "created", u.created(), "online", online.contains(u.username()), "dashboard", u.dashboard()));
        }
        List<Object> activity = new ArrayList<>();
        for (ActivityLog.Entry e : log.latest(60)) {
            activity.add(Json.obj("time", e.time(), "actor", e.actor(), "ip", e.ip(), "message", e.message()));
        }
        List<String> lan = List.of(); // Remote staff use Cloudflare; never advertise LAN access.
        Platform.Business biz = platform.business(businessId);
        return Json.obj(
                "business", Json.obj("id", biz.id(), "name", biz.name(), "status", biz.status()),
                "server", Json.obj(
                        "startedAt", iso(startedAt),
                        "uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000,
                        "localUrl", "http://localhost:" + posPort,
                        "lanUrls", lan,
                        "dataDir", store.dir().toString(),
                        "java", System.getProperty("java.version")),
                "tunnel", tunnel.info(),
                "inventory", Stats.inventory(store),
                "receivables", Stats.receivables(store),
                "tanksOut", Stats.tanksOut(store),
                "staffMoney", Stats.staffMoney(store),
                "sessions", sessionOut,
                "users", users,
                "activity", activity);
    }

    // ---------------------------------------------------------------- platform admin sign-in

    private void adminAccount(Http.Req r, String what) throws IOException {
        String salt = platform.setting("admin_salt"), hash = platform.setting("admin_hash");
        boolean set = hash != null;
        DataStore.User admin = set ? new DataStore.User(ADMIN, salt, hash, "", true) : null;
        switch (r.method + " " + what) {
            case "GET state" -> r.json(200, Json.obj("setup", !set, "signedIn", adminAuth.get(r.cookie(COOKIE)) != null));
            case "POST setup" -> {
                if (set) throw new Http.Error(409, "The admin password is already set");
                setAdminPassword(PosGate.password(r));
                platform.audit(ADMIN, null, "Set the platform admin password", "");
                signIn(r);
            }
            case "POST login" -> {
                String ip = r.clientIp();
                if (adminAuth.throttled(ip, ADMIN)) throw new Http.Error(429, "Too many failed attempts. Wait 15 minutes and try again.");
                if (!set || !Auth.verify(r.form().getOrDefault("password", ""), admin)) {
                    adminAuth.recordFailure(ip, ADMIN);
                    platform.audit(ADMIN, null, "Failed admin sign-in", "");
                    throw new Http.Error(401, "Wrong password");
                }
                adminAuth.clearFailures(ip, ADMIN);
                signIn(r);
            }
            case "POST logout" -> {
                adminAuth.end(r.cookie(COOKIE));
                r.addHeader("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                r.json(200, Json.obj("ok", true));
            }
            case "POST password" -> {
                Auth.Session s = adminAuth.get(r.cookie(COOKIE));
                if (s == null) throw new Http.Error(401, "Please sign in");
                if (!Auth.verify(r.form().getOrDefault("current", ""), admin)) throw new Http.Error(400, "Current password is wrong");
                setAdminPassword(PosGate.password(r));
                adminAuth.revokeUser(ADMIN, s.token);
                platform.audit(ADMIN, null, "Changed the platform admin password", "");
                r.json(200, Json.obj("ok", true));
            }
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
    }

    private void setAdminPassword(String password) {
        String salt = Auth.newSalt();
        platform.inTransaction(() -> {
            platform.setSetting("admin_salt", salt);
            platform.setSetting("admin_hash", Auth.hash(password, salt));
            return null;
        });
    }

    private void signIn(Http.Req r) throws IOException {
        Auth.Session s = adminAuth.create(ADMIN, 0, r.clientIp(), r.via(), r.header("User-Agent"));
        r.addHeader("Set-Cookie", COOKIE + "=" + s.token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + Auth.SESSION_IDLE_MS / 1000);
        platform.audit(ADMIN, null, "Admin signed in", "");
        r.json(200, Json.obj("ok", true));
    }

    /** The business the store pages show for this admin session: the one picked, else the first active one. */
    private long selectedBusiness(Auth.Session admin) {
        Long id = selected.get(admin.token);
        if (id != null && platform.business(id) != null) return id;
        return platform.businesses().stream().filter(b -> b.status().equals("active")).map(Platform.Business::id).findFirst()
                .orElseThrow(() -> new Http.Error(409, "No active business yet. Approve or create one on the Businesses page."));
    }

    // ---------------------------------------------------------------- businesses and accounts

    private void platformRoute(Http.Req r, Auth.Session admin, String[] seg) throws IOException {
        String route = r.method + " " + seg[0] + (seg.length > 1 ? "/:id" : "") + (seg.length > 2 ? "/" + seg[2] : "");
        String id = seg.length > 1 ? seg[1] : null;
        switch (route) {
            case "GET platform" -> r.json(200, platformOverview(admin));
            case "GET platform/:id" -> {
                if (!id.equals("audit")) throw new Http.Error(404, "Unknown endpoint");
                String b = r.query().getOrDefault("business", "");
                List<Object> out = new ArrayList<>();
                for (Platform.Audit a : platform.auditLog(b.isEmpty() ? null : businessId(b), 200)) {
                    out.add(Json.obj("time", a.time(), "actor", a.actor(), "businessId", a.businessId(), "action", a.action(), "detail", a.detail()));
                }
                r.json(200, Json.obj("audit", out));
            }
            case "POST platform/:id" -> {
                if (!id.equals("backup")) throw new Http.Error(404, "Unknown endpoint");
                r.json(200, Json.obj("file", Backups.run(platform, tenants).toString()));
            }
            case "POST businesses" -> {
                String name = PosGate.text(r, "name", "Store name", 2, 80, true);
                String contact = PosGate.text(r, "contactName", "Owner's name", 0, 80, false);
                String username = PosGate.username(r);
                String password = PosGate.password(r);
                String salt = Auth.newSalt();
                long b = platform.createWithOwner(name, contact, PosGate.text(r, "phone", "Mobile number", 0, 20, false),
                        PosGate.text(r, "email", "Email", 0, 254, false), PosGate.text(r, "address", "Address", 0, 200, false),
                        username, salt, Auth.hash(password, salt), ADMIN);
                tenants.activate(platform.account(username));
                r.json(201, Json.obj("id", b));
            }
            case "PUT businesses/:id" -> {
                long b = businessId(id);
                Platform.Business cur = platform.business(b);
                platform.updateProfile(b, PosGate.text(r, "name", "Store name", 2, 80, true), PosGate.text(r, "contactName", "Contact name", 0, 80, false),
                        PosGate.text(r, "phone", "Mobile number", 0, 20, false), PosGate.text(r, "email", "Email", 0, 254, false),
                        PosGate.text(r, "address", "Address", 0, 200, false), ADMIN);
                r.json(200, Json.obj("ok", true, "was", cur.name()));
            }
            case "POST businesses/:id/status" -> {
                long b = businessId(id);
                String status = r.param("status");
                platform.setBusinessStatus(b, status, ADMIN);
                if (status.equals("active")) for (Platform.Account a : platform.accounts(b)) tenants.activate(a);
                else for (Platform.Account a : platform.accounts(b)) auth.revokeUser(a.username(), null);
                r.json(200, Json.obj("ok", true));
            }
            case "POST businesses/:id/billing" -> {
                long b = businessId(id);
                String exempt = r.param("exempt"), until = r.param("paidUntil");
                if (!exempt.isEmpty()) billing.setExempt(b, exempt.equals("1"), ADMIN);
                if (!until.isEmpty()) billing.setPaidUntil(b, Billing.date(until, "Paid until"), ADMIN);
                r.json(200, Json.obj("ok", true));
            }
            case "GET billing" -> r.json(200, billingOverview());
            case "PUT billing" -> {
                billing.saveSettings(r.param("on").equals("1"), Billing.centavos(r.param("price"), "Price"),
                        intParam(r, "graceDays", "Grace days"), intParam(r, "trialDays", "Free days"),
                        PosGate.text(r, "payee", "Account name", 0, 120, false), PosGate.text(r, "note", "Payment note", 0, 300, false), ADMIN);
                r.json(200, Json.obj("ok", true));
            }
            case "GET billing/:id", "POST billing/:id" -> {
                if (!id.equals("qr")) throw new Http.Error(404, "Unknown endpoint");
                if (r.method.equals("POST")) {
                    r.allowLargeBody();
                    billing.saveQr(Billing.image(r.form().getOrDefault("image", "")), ADMIN);
                    r.json(200, Json.obj("ok", true));
                } else {
                    Path qr = billing.qrFile();
                    if (qr == null) throw new Http.Error(404, "No QR code yet");
                    r.image(qr.toString().endsWith(".png") ? "image/png" : "image/jpeg", java.nio.file.Files.readAllBytes(qr));
                }
            }
            case "GET payments/:id/receipt" -> {
                Billing.Payment p = billing.payment(paymentId(id));
                if (p == null || p.receipt().isEmpty()) throw new Http.Error(404, "No picture for this payment");
                r.image(p.receipt().endsWith(".png") ? "image/png" : "image/jpeg", java.nio.file.Files.readAllBytes(billing.files().resolve(p.receipt())));
            }
            case "POST payments/:id/approve" -> {
                billing.approve(paymentId(id), ADMIN);
                r.json(200, Json.obj("ok", true));
            }
            case "POST payments/:id/reject" -> {
                billing.reject(paymentId(id), PosGate.text(r, "note", "Reason", 0, 200, false), ADMIN);
                r.json(200, Json.obj("ok", true));
            }
            case "PUT mail" -> {
                mail.saveSettings(r.param("on").equals("1"), r.param("auto").equals("1"), PosGate.text(r, "user", "Mailbox", 0, 254, false),
                        r.param("password"), ADMIN);
                r.json(200, Json.obj("ok", true));
            }
            case "POST mail/:id" -> {
                if (!id.equals("check")) throw new Http.Error(404, "Unknown endpoint");
                try {
                    r.json(200, Json.obj("ok", true, "added", mail.poll()));
                } catch (IOException e) {
                    throw new Http.Error(502, "Could not read the mailbox: " + e.getMessage());
                }
            }
            case "POST bank-emails/:id/apply" -> {
                mail.apply(emailId(id), ADMIN);
                r.json(200, Json.obj("ok", true));
            }
            case "POST bank-emails/:id/ignore" -> {
                mail.ignore(emailId(id), ADMIN);
                r.json(200, Json.obj("ok", true));
            }
            case "POST businesses/:id/select" -> {
                long b = businessId(id);
                selected.put(admin.token, b);
                r.json(200, Json.obj("ok", true));
            }
            case "GET businesses/:id/accounts" -> {
                long b = businessId(id);
                r.json(200, Json.obj("accounts", gate.accountsOut(b, tenants.get(b).store())));
            }
            case "POST accounts/:id/status", "POST accounts/:id/reject", "POST accounts/:id/owner", "POST accounts/:id/password" -> {
                Platform.Account a = platform.account(id);
                if (a == null) throw new Http.Error(404, "No such account");
                long b = a.businessId();
                switch (seg[2]) {
                    case "status" -> {
                        String status = r.param("status");
                        if (a.owner() && a.status().equals("pending")) throw new Http.Error(409, "Approve the business instead");
                        Platform.Account after = platform.setAccountStatus(b, a.username(), status, ADMIN);
                        if (after.active()) tenants.activate(after);
                        else auth.revokeUser(a.username(), null);
                    }
                    case "reject" -> platform.rejectPending(b, a.username(), ADMIN);
                    case "owner" -> {
                        platform.setOwner(b, a.username(), ADMIN);
                        tenants.get(b).store().setDashboard(a.username(), true);
                    }
                    default -> {
                        String password = PosGate.password(r);
                        String salt = Auth.newSalt();
                        tenants.activate(a);
                        if (!tenants.get(b).store().setPassword(a.username(), salt, Auth.hash(password, salt))) throw new Http.Error(409, "This account isn't active yet");
                        auth.revokeUser(a.username(), null);
                        platform.audit(ADMIN, b, "Reset password for " + a.username(), "");
                    }
                }
                r.json(200, Json.obj("ok", true));
            }
            default -> throw new Http.Error(404, "Unknown endpoint");
        }
    }

    private long businessId(String raw) {
        try {
            long id = Long.parseLong(raw);
            if (platform.business(id) != null) return id;
        } catch (NumberFormatException ignored) {
        }
        throw new Http.Error(404, "No such business");
    }

    private static long paymentId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new Http.Error(404, "No such payment");
        }
    }

    private static long emailId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new Http.Error(404, "No such email");
        }
    }

    private static int intParam(Http.Req r, String name, String label) throws IOException {
        try {
            return Integer.parseInt(r.param(name));
        } catch (NumberFormatException e) {
            throw new Http.Error(400, label + ": enter a whole number");
        }
    }

    /** Billing settings and the payments queue: waiting ones first, with what to check before approving. */
    private Map<String, Object> billingOverview() {
        Map<Long, String> names = new HashMap<>();
        for (Platform.Business b : platform.businesses()) names.put(b.id(), b.name());
        List<Object> queue = new ArrayList<>();
        for (Billing.Payment p : billing.queue()) {
            Map<String, Object> out = Billing.paymentOut(p);
            Billing.Invoice inv = billing.invoice(p.invoiceId());
            out.put("business", names.get(p.businessId()));
            out.put("invoice", inv == null ? null : Billing.invoiceOut(inv));
            MailWatch.Email e = mail.forReference(p.reference());
            boolean linked = e != null && Objects.equals(e.paymentId(), p.id());
            out.put("bank", linked ? MailWatch.emailOut(e) : null);
            List<String> warnings = new ArrayList<>(p.status().equals("pending") ? billing.warnings(p) : List.of());
            if (p.status().equals("pending") && mail.on()) {
                if (!linked) warnings.add("No MariBank email with this reference yet");
                else if (inv != null && e.amount() != inv.amount()) warnings.add("The MariBank email shows " + Billing.peso(e.amount()));
            }
            out.put("warnings", warnings);
            queue.add(out);
        }
        List<Object> emails = new ArrayList<>();
        for (MailWatch.Email e : mail.recent()) {
            Map<String, Object> out = MailWatch.emailOut(e);
            Billing.Invoice inv = e.invoiceId() == null ? null : billing.invoice(e.invoiceId());
            out.put("invoice", inv == null ? null : Billing.invoiceOut(inv));
            out.put("business", inv == null ? null : names.get(inv.businessId()));
            emails.add(out);
        }
        return Json.obj("settings", billing.settings(), "payments", queue, "mail", mail.settings(), "emails", emails);
    }

    private Map<String, Object> platformOverview(Auth.Session admin) {
        Map<Long, Integer> online = new HashMap<>();
        for (Auth.Session s : auth.active()) online.merge(s.businessId, 1, Integer::sum);
        List<Object> out = new ArrayList<>();
        for (Platform.Business b : platform.businesses()) {
            List<Platform.Account> accounts = platform.accounts(b.id());
            Platform.Account owner = accounts.stream().filter(Platform.Account::owner).findFirst().orElse(null);
            out.add(Json.obj("id", b.id(), "code", b.code(), "name", b.name(), "status", b.status(), "contactName", b.contactName(),
                    "phone", b.phone(), "email", b.email(), "address", b.address(), "created", b.created(), "decided", b.decided(),
                    "owner", owner == null ? null : owner.username(),
                    "staff", accounts.stream().filter(a -> !a.owner() && a.active()).count(),
                    "pendingStaff", accounts.stream().filter(a -> !a.owner() && a.status().equals("pending")).count(),
                    "online", online.getOrDefault(b.id(), 0), "billingExempt", b.billingExempt(), "billing", billing.summary(b)));
        }
        Long sel = selected.get(admin.token);
        return Json.obj("businesses", out, "selected", sel, "health", Backups.health(platform, tenants));
    }

    private static String iso(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).withNano(0).format(DataStore.TIME);
    }

    private static String device(String ua) {
        String os = ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad" : ua.contains("Android") ? "Android"
                : ua.contains("Mac OS X") ? "Mac" : ua.contains("Windows") ? "Windows" : ua.contains("Linux") ? "Linux" : "Device";
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("Chrome/") ? "Chrome" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Safari/") ? "Safari" : "";
        return browser.isEmpty() ? os : browser + " on " + os;
    }

    static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
        }
        return out;
    }
}

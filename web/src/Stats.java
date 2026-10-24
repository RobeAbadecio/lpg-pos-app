import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Figures for the dashboards: sales for a date range, plus current stock, unpaid balances and tanks out. */
final class Stats {
    private Stats() {}

    private static final class Agg {
        double revenue;
        int count, qty;
        final Set<String> customers = new HashSet<>();

        void add(DataStore.Receipt r) {
            revenue += r.total();
            count++;
            qty += r.qty();
            customers.add(r.customerId());
        }

        Map<String, Object> json(double collected, double discounts) {
            return Json.obj("revenue", DataStore.round(revenue), "count", count, "qty", qty,
                    "avg", count == 0 ? 0.0 : revenue / count, "customers", customers.size(),
                    "collected", DataStore.round(collected), "discounts", DataStore.round(discounts));
        }
    }

    static Map<String, Object> compute(DataStore store, String range) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        List<DataStore.Receipt> receipts = store.receipts();
        List<DataStore.Payment> payments = store.payments();
        Map<String, DataStore.Product> products = store.productMap();
        Map<String, DataStore.Customer> customers = store.customers().stream()
                .collect(Collectors.toMap(DataStore.Customer::id, Function.identity(), (a, b) -> a));
        Map<String, Double> paid = store.paidByReceipt(receipts);

        LocalDateTime start, prevStart = null, prevEnd = null;
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        String bucket, compare = null;
        switch (range) {
            case "today" -> {
                start = today.atStartOfDay();
                prevStart = start.minusDays(1);
                prevEnd = now.minusDays(1);
                bucket = "hour";
                compare = "vs same time yesterday";
            }
            case "month", "quarter" -> {
                // This month or quarter so far, against the previous one up to the same point (as the revenue card).
                Periods w = new Periods(range, now);
                start = w.curStart;
                prevStart = w.prevStart;
                prevEnd = w.prevEnd;
                bucket = "day";
                compare = "vs same point last " + range;
            }
            case "7", "30", "90" -> {
                int n = Integer.parseInt(range);
                start = today.minusDays(n - 1).atStartOfDay();
                prevStart = start.minusDays(n);
                prevEnd = start;
                bucket = "day";
                compare = "vs previous " + n + " days";
            }
            default -> {
                range = "all";
                LocalDate first = receipts.stream().map(DataStore.Receipt::time).filter(Objects::nonNull)
                        .map(LocalDateTime::toLocalDate).min(Comparator.naturalOrder()).orElse(today);
                if (first.isAfter(today)) first = today;
                start = first.atStartOfDay();
                bucket = ChronoUnit.DAYS.between(first, today) > 120 ? "month" : "day";
            }
        }

        LinkedHashMap<String, Agg> series = new LinkedHashMap<>();
        switch (bucket) {
            case "hour" -> {
                for (int h = 0; h < 24; h++) series.put(String.format("%02d", h), new Agg());
            }
            case "day" -> {
                for (LocalDate d = start.toLocalDate(); !d.isAfter(today); d = d.plusDays(1)) series.put(d.toString(), new Agg());
            }
            default -> {
                for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(today)); m = m.plusMonths(1)) {
                    series.put(m.toString(), new Agg());
                }
            }
        }

        final LocalDateTime from = start, pFrom = prevStart, pTo = prevEnd;
        final boolean all = range.equals("all");
        java.util.function.Predicate<LocalDateTime> inRange = at -> all ? at == null || !at.isBefore(from)
                : at != null && !at.isBefore(from) && at.isBefore(end);
        java.util.function.Predicate<LocalDateTime> inPrev = at -> pFrom != null && at != null && !at.isBefore(pFrom) && at.isBefore(pTo);

        Agg total = new Agg(), prev = new Agg();
        double collected = 0, prevCollected = 0, discounts = 0, prevDiscounts = 0, collectedGcash = 0;
        // Profit only counts lines whose product has a refill cost; the rest are listed in uncosted.
        double cost = 0, prevCost = 0, costedRevenue = 0, prevCostedRevenue = 0, supplierPaid = 0, prevSupplierPaid = 0;
        double paidByStaff = 0, prevPaidByStaff = 0, paidBack = 0, prevPaidBack = 0;
        boolean anyCosted = false, prevAnyCosted = false;
        Set<String> uncosted = new TreeSet<>(); // products sold in range with no refill cost set
        Map<String, Agg> byStaff = new HashMap<>(), byCustomer = new HashMap<>();
        Map<String, double[]> byProduct = new HashMap<>(); // revenue, qty, receipts, cost
        for (DataStore.Receipt r : receipts) {
            LocalDateTime at = r.time();
            if (inRange.test(at)) {
                total.add(r);
                collected += r.paidAtSale();
                if (r.gcash()) collectedGcash += r.paidAtSale();
                discounts += r.discount();
                byStaff.computeIfAbsent(r.staff().isEmpty() ? "Desktop app" : r.staff(), k -> new Agg()).add(r);
                byCustomer.computeIfAbsent(r.customerId(), k -> new Agg()).add(r);
                // Spread the receipt discount over its lines so product revenue adds up to the total.
                double sub = r.subtotal(), share = sub > 0 ? r.total() / sub : 0;
                Set<String> seen = new HashSet<>();
                for (DataStore.Line l : r.lines()) {
                    double[] v = byProduct.computeIfAbsent(l.productId(), k -> new double[4]);
                    v[0] += l.amount() * share;
                    v[1] += l.qty();
                    if (seen.add(l.productId())) v[2]++;
                    double c = lineCost(l, products, uncosted);
                    if (c >= 0) {
                        anyCosted = true;
                        costedRevenue += l.amount() * share;
                        v[3] += c;
                        cost += c;
                    }
                }
                if (at != null) {
                    String key = switch (bucket) {
                        case "hour" -> String.format("%02d", at.getHour());
                        case "day" -> at.toLocalDate().toString();
                        default -> YearMonth.from(at).toString();
                    };
                    Agg a = series.get(key);
                    if (a != null) a.add(r);
                }
            } else if (inPrev.test(at)) {
                prev.add(r);
                prevCollected += r.paidAtSale();
                prevDiscounts += r.discount();
                double sub = r.subtotal(), share = sub > 0 ? r.total() / sub : 0;
                for (DataStore.Line l : r.lines()) {
                    double c = lineCost(l, products, new HashSet<>());
                    if (c >= 0) {
                        prevAnyCosted = true;
                        prevCostedRevenue += l.amount() * share;
                        prevCost += c;
                    }
                }
            }
        }
        double paidByOwner = 0, prevPaidByOwner = 0;
        for (DataStore.SupplierPayment sp : store.supplierPayments()) {
            if (inRange.test(sp.time())) {
                supplierPaid += sp.amount();
                if (sp.fromStaff()) paidByStaff += sp.amount();
                if (sp.fromOwner()) paidByOwner += sp.amount();
            } else if (inPrev.test(sp.time())) {
                prevSupplierPaid += sp.amount();
                if (sp.fromStaff()) prevPaidByStaff += sp.amount();
                if (sp.fromOwner()) prevPaidByOwner += sp.amount();
            }
        }
        for (DataStore.Payback pb : store.paybacks()) {
            if (inRange.test(pb.time())) paidBack += pb.amount();
            else if (inPrev.test(pb.time())) prevPaidBack += pb.amount();
        }
        for (DataStore.Payment p : payments) {
            if (inRange.test(p.time())) {
                collected += p.amount();
                if (p.gcash()) collectedGcash += p.amount();
            }
            else if (inPrev.test(p.time())) prevCollected += p.amount();
        }

        List<Object> seriesOut = new ArrayList<>();
        series.forEach((k, a) -> seriesOut.add(Json.obj("key", k, "revenue", DataStore.round(a.revenue), "count", a.count, "qty", a.qty)));

        List<Map.Entry<String, double[]>> prodSorted = new ArrayList<>(byProduct.entrySet());
        prodSorted.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        List<Object> productOut = new ArrayList<>();
        double[] other = new double[4];
        int otherGroups = 0;
        for (int i = 0; i < prodSorted.size(); i++) {
            var e = prodSorted.get(i);
            if (i < 8) {
                DataStore.Product p = products.get(e.getKey());
                productOut.add(Json.obj("id", e.getKey(), "label", p == null ? "Deleted product #" + e.getKey() : p.label(),
                        "revenue", DataStore.round(e.getValue()[0]), "qty", (int) e.getValue()[1], "count", (int) e.getValue()[2],
                        "profit", p == null || p.refillCost() <= 0 ? null : DataStore.round(e.getValue()[0] - e.getValue()[3])));
            } else {
                for (int k = 0; k < 4; k++) other[k] += e.getValue()[k];
                otherGroups++;
            }
        }
        if (otherGroups > 0) {
            productOut.add(Json.obj("id", "", "label", "Other (" + otherGroups + ")", "revenue", DataStore.round(other[0]),
                    "qty", (int) other[1], "count", (int) other[2]));
        }
        List<Object> staffOut = ranked(byStaff, 8, true, Function.identity());
        List<Object> customerOut = ranked(byCustomer, 5, false, id -> {
            DataStore.Customer c = customers.get(id);
            return c == null ? "Deleted customer #" + id : c.name();
        });

        List<Object> recent = new ArrayList<>();
        for (int i = receipts.size() - 1; i >= 0 && recent.size() < 12; i--) {
            DataStore.Receipt r = receipts.get(i);
            DataStore.Customer c = customers.get(r.customerId());
            double bal = DataStore.balance(r, paid);
            double got = paid.getOrDefault(r.id(), 0.0);
            recent.add(Json.obj("id", r.id(), "time", r.rawTime(),
                    "customer", c == null ? "Unknown customer #" + r.customerId() : c.name(),
                    "items", itemsText(r, products), "total", r.total(), "balance", bal,
                    "status", bal <= 0 ? "paid" : got <= 0.005 ? "unpaid" : "partial",
                    "staff", r.staff().isEmpty() ? "Desktop app" : r.staff()));
        }

        Map<String, Object> totals = total.json(collected, discounts);
        totals.put("cost", DataStore.round(cost));
        totals.put("costedRevenue", DataStore.round(costedRevenue));
        totals.put("profit", anyCosted ? DataStore.round(costedRevenue - cost) : null);
        totals.put("collectedGcash", DataStore.round(collectedGcash));
        totals.put("supplierPaid", DataStore.round(supplierPaid));
        totals.put("supplierPaidSales", DataStore.round(supplierPaid - paidByStaff - paidByOwner));
        totals.put("supplierPaidOwner", DataStore.round(paidByOwner));
        totals.put("supplierPaidStaff", DataStore.round(paidByStaff));
        totals.put("paidBackToStaff", DataStore.round(paidBack));
        Map<String, Object> previous = null;
        if (prevStart != null) {
            previous = prev.json(prevCollected, prevDiscounts);
            previous.put("cost", DataStore.round(prevCost));
            previous.put("profit", prevAnyCosted ? DataStore.round(prevCostedRevenue - prevCost) : null);
            previous.put("supplierPaid", DataStore.round(prevSupplierPaid));
            previous.put("supplierPaidSales", DataStore.round(prevSupplierPaid - prevPaidByStaff - prevPaidByOwner));
            previous.put("supplierPaidOwner", DataStore.round(prevPaidByOwner));
            previous.put("supplierPaidStaff", DataStore.round(prevPaidByStaff));
            previous.put("paidBackToStaff", DataStore.round(prevPaidBack));
        }
        return Json.obj(
                "range", range,
                "bucket", bucket,
                "start", start.toLocalDate().toString(),
                "compare", compare,
                "totals", totals,
                "previous", previous,
                "uncosted", new ArrayList<>(uncosted),
                "series", seriesOut,
                "byProduct", productOut,
                "byStaff", staffOut,
                "topCustomers", customerOut,
                "recent", recent,
                "counts", Json.obj("customers", customers.size(), "products", products.size(), "receipts", receipts.size()));
    }

    /**
     * Revenue grouped by day (last 30 days), month (last 12) or quarter (last 8). The current
     * period is compared with the previous one up to the same point, e.g. this month so far
     * against last month up to the same day and time.
     */
    static Map<String, Object> revenue(DataStore store, String period) {
        return revenue(store.receipts(), store.payments(), period, LocalDateTime.now());
    }

    /**
     * Sales (revenue) per period, plus the money that came in during it by cash and by GCash: paid at
     * the sale, or paid later on a balance (counted when it was paid).
     */
    static Map<String, Object> revenue(List<DataStore.Receipt> receipts, List<DataStore.Payment> payments, String period, LocalDateTime now) {
        Periods w = new Periods(period, now);
        LinkedHashMap<String, Agg> series = new LinkedHashMap<>();
        Map<String, double[]> in = new HashMap<>(); // key -> cash, gcash
        for (String k : w.keys) series.put(k, new Agg());
        Agg cur = new Agg(), prev = new Agg();
        double[] curIn = new double[2];
        java.util.function.BiConsumer<LocalDateTime, double[]> money = (at, v) -> {
            String k = w.key(at);
            if (k != null && series.containsKey(k)) {
                double[] t = in.computeIfAbsent(k, x -> new double[2]);
                t[0] += v[0];
                t[1] += v[1];
            }
            if (w.isCurrent(at)) {
                curIn[0] += v[0];
                curIn[1] += v[1];
            }
        };
        for (DataStore.Receipt r : receipts) {
            Agg a = series.get(w.key(r.time()));
            if (a != null) a.add(r);
            if (w.isCurrent(r.time())) cur.add(r);
            else if (w.isPrevious(r.time())) prev.add(r);
            money.accept(r.time(), r.gcash() ? new double[]{0, r.paidAtSale()} : new double[]{r.paidAtSale(), 0});
        }
        for (DataStore.Payment p : payments) money.accept(p.time(), p.gcash() ? new double[]{0, p.amount()} : new double[]{p.amount(), 0});
        List<Object> seriesOut = new ArrayList<>();
        series.forEach((k, a) -> {
            double[] t = in.getOrDefault(k, new double[2]);
            seriesOut.add(Json.obj("key", k, "revenue", DataStore.round(a.revenue), "count", a.count, "qty", a.qty,
                    "cash", DataStore.round(t[0]), "gcash", DataStore.round(t[1])));
        });
        return Json.obj("period", w.period,
                "current", Json.obj("revenue", DataStore.round(cur.revenue), "count", cur.count, "qty", cur.qty,
                        "cash", DataStore.round(curIn[0]), "gcash", DataStore.round(curIn[1])),
                "previous", Json.obj("revenue", DataStore.round(prev.revenue), "count", prev.count, "qty", prev.qty), "series", seriesOut);
    }

    /** Dealings with suppliers in one period. */
    private static final class SupAgg {
        double billed, paid, paidCash, paidCheck, paidOnline, paidOwner, paidStaff, unpaid;
        int trips, sent, refilled, rejected, bought;

        boolean isEmpty() {
            return trips + sent + refilled + rejected + bought == 0 && billed == 0 && paid == 0 && unpaid == 0;
        }

        /** Paid splits five ways that add up: the store's cash, checks and online transfers, the owner's and staff's own money. */
        void pay(DataStore.SupplierPayment sp) {
            paid += sp.amount();
            if (sp.fromOwner()) paidOwner += sp.amount();
            else if (sp.fromStaff()) paidStaff += sp.amount();
            else if (sp.byCheck()) paidCheck += sp.amount();
            else if (sp.online()) paidOnline += sp.amount();
            else paidCash += sp.amount();
        }

        Map<String, Object> json() {
            return Json.obj("billed", DataStore.round(billed), "paid", DataStore.round(paid),
                    "paidCash", DataStore.round(paidCash), "paidCheck", DataStore.round(paidCheck), "paidOnline", DataStore.round(paidOnline),
                    "paidOwner", DataStore.round(paidOwner), "paidStaff", DataStore.round(paidStaff), "unpaid", DataStore.round(unpaid),
                    "trips", trips, "sent", sent, "refilled", refilled, "rejected", rejected, "bought", bought);
        }
    }

    /**
     * What the store did with its suppliers by day, month or quarter (same periods as revenue):
     * refill trips; tanks sent, back with load and returned unfilled; new tanks bought; what that
     * cost (billed when the tanks came in); what was paid (when it was paid) by cash or check and how
     * much of it was the owner's or staff's own money; and what's still unpaid (to pay later) of the
     * bills from that period. supplierId: one supplier only, or blank for all, with the current period
     * split per supplier.
     */
    static Map<String, Object> suppliers(DataStore store, String period, String supplierId) {
        return suppliers(store, period, supplierId, LocalDateTime.now());
    }

    static Map<String, Object> suppliers(DataStore store, String period, String supplierId, LocalDateTime now) {
        String only = supplierId == null ? "" : supplierId;
        Periods w = new Periods(period, now);
        LinkedHashMap<String, SupAgg> series = new LinkedHashMap<>();
        for (String k : w.keys) series.put(k, new SupAgg());
        SupAgg cur = new SupAgg(), prev = new SupAgg();
        Map<String, SupAgg> bySupplier = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>(), byBrand = new HashMap<>(), tripSupplier = new HashMap<>();
        for (DataStore.Supplier s : store.suppliers()) {
            names.put(s.id(), s.name());
            for (String b : s.brands()) byBrand.putIfAbsent(b.toLowerCase(Locale.ROOT), s.id());
        }
        Map<String, DataStore.Product> products = store.productMap();
        // Each event counts in its period, in the current or previous total, and (current period only) under its supplier.
        for (DataStore.Refill rf : store.refills()) {
            tripSupplier.put(rf.id(), rf.supplierId());
            if (rf.start() || (!only.isEmpty() && !only.equals(rf.supplierId()))) continue;
            for (SupAgg a : targets(w, rf.time(), series, cur, prev, bySupplier, rf.supplierId())) a.trips++;
        }
        for (DataStore.Movement m : store.movements()) {
            String sid;
            if (m.type().equals("purchase")) {
                DataStore.Product p = products.get(m.productId());
                sid = p == null ? "" : byBrand.getOrDefault(p.brand().toLowerCase(Locale.ROOT), "");
            } else if (m.type().startsWith("refill-")) {
                sid = tripSupplier.getOrDefault(m.refillId(), "");
            } else {
                continue;
            }
            if (!only.isEmpty() && !only.equals(sid)) continue;
            for (SupAgg a : targets(w, m.time(), series, cur, prev, bySupplier, sid)) {
                switch (m.type()) {
                    case "refill-send" -> a.sent += m.atRefiller();
                    case "refill-receive" -> {
                        a.refilled += m.loaded();
                        a.billed += m.loaded() * m.unitCost();
                    }
                    case "refill-reject" -> a.rejected += m.empty() + m.damaged(); // older rows sent them to damaged
                    case "purchase" -> {
                        a.bought += m.loaded();
                        if (m.unitCost() > 0) a.billed += m.loaded() * m.unitCost();
                    }
                    default -> {
                    }
                }
            }
        }
        for (DataStore.SupplierPayment sp : store.supplierPayments()) {
            if (!only.isEmpty() && !only.equals(sp.supplierId())) continue;
            for (SupAgg a : targets(w, sp.time(), series, cur, prev, bySupplier, sp.supplierId())) a.pay(sp);
        }
        // Pay later: what's still owed today on the bills from each period.
        for (DataStore.Bill b : store.bills()) {
            if (b.owed() <= 0 || (!only.isEmpty() && !only.equals(b.supplierId()))) continue;
            for (SupAgg a : targets(w, b.time(), series, cur, prev, bySupplier, b.supplierId())) a.unpaid += b.owed();
        }
        List<Object> seriesOut = new ArrayList<>();
        series.forEach((k, a) -> {
            Map<String, Object> o = a.json();
            o.put("key", k);
            seriesOut.add(o);
        });
        List<Object> supplierOut = new ArrayList<>();
        bySupplier.forEach((id, a) -> {
            if (a.isEmpty()) return;
            Map<String, Object> o = a.json();
            o.put("id", id);
            o.put("name", id.isEmpty() ? "No supplier set" : names.getOrDefault(id, "Deleted supplier #" + id));
            supplierOut.add(o);
        });
        List<Object> choices = new ArrayList<>();
        for (DataStore.Supplier s : store.suppliers()) choices.add(Json.obj("id", s.id(), "name", s.name()));
        return Json.obj("period", w.period, "supplierId", only, "suppliers", choices,
                "current", cur.json(), "previous", prev.json(), "series", seriesOut, "bySupplier", only.isEmpty() ? supplierOut : List.of());
    }

    /** The totals an event at this time adds to: its period, current or previous, and its supplier if current. */
    private static List<SupAgg> targets(Periods w, LocalDateTime at, Map<String, SupAgg> series, SupAgg cur, SupAgg prev,
                                        Map<String, SupAgg> bySupplier, String supplierId) {
        List<SupAgg> out = new ArrayList<>(3);
        SupAgg a = series.get(w.key(at));
        if (a != null) out.add(a);
        if (w.isCurrent(at)) {
            out.add(cur);
            out.add(bySupplier.computeIfAbsent(supplierId == null ? "" : supplierId, k -> new SupAgg()));
        } else if (w.isPrevious(at)) {
            out.add(prev);
        }
        return out;
    }

    /**
     * The last 30 days, 12 months or 8 quarters up to now. The current period is compared with
     * the previous one up to the same point, e.g. this month so far against last month up to the
     * same day and time.
     */
    static final class Periods {
        final String period;
        final List<String> keys = new ArrayList<>();
        /** First and last day of each period, by key. */
        final Map<String, LocalDate[]> days = new LinkedHashMap<>();
        final LocalDateTime now, curStart, prevStart, prevEnd;
        private final Function<LocalDate, LocalDate> startOf;

        /** Every day from one date to another (at most 120 days, never past today), for opening a longer period day by day. */
        Periods(LocalDate from, LocalDate to, LocalDateTime now) {
            this.period = "day";
            this.now = now;
            this.startOf = Function.identity();
            LocalDate today = now.toLocalDate();
            if (to.isAfter(today)) to = today;
            if (from.isAfter(to)) from = to;
            if (ChronoUnit.DAYS.between(from, to) > 119) from = to.minusDays(119);
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                keys.add(d.toString());
                days.put(d.toString(), new LocalDate[]{d, d});
            }
            curStart = today.atStartOfDay();
            prevStart = curStart.minusDays(1);
            prevEnd = now.minusDays(1);
        }

        Periods(String period, LocalDateTime now) {
            int n;
            Function<LocalDate, LocalDate> next;
            switch (period) {
                case "week" -> {
                    n = 12;
                    startOf = d -> d.minusDays(d.getDayOfWeek().getValue() - 1); // weeks start on Monday
                    next = d -> d.plusWeeks(1);
                }
                case "month" -> {
                    n = 12;
                    startOf = d -> d.withDayOfMonth(1);
                    next = d -> d.plusMonths(1);
                }
                case "quarter" -> {
                    n = 8;
                    startOf = d -> LocalDate.of(d.getYear(), (d.getMonthValue() - 1) / 3 * 3 + 1, 1);
                    next = d -> d.plusMonths(3);
                }
                default -> {
                    period = "day";
                    n = 30;
                    startOf = Function.identity();
                    next = d -> d.plusDays(1);
                }
            }
            this.period = period;
            this.now = now;
            LocalDate current = startOf.apply(now.toLocalDate()), first = current;
            for (int i = 1; i < n; i++) first = startOf.apply(first.minusDays(1));
            for (LocalDate d = first; !d.isAfter(current); d = next.apply(d)) {
                keys.add(periodKey(d, period));
                days.put(periodKey(d, period), new LocalDate[]{d, next.apply(d).minusDays(1)});
            }
            curStart = current.atStartOfDay();
            prevStart = startOf.apply(current.minusDays(1)).atStartOfDay();
            LocalDateTime end = prevStart.plus(java.time.Duration.between(curStart, now));
            prevEnd = end.isAfter(curStart) ? curStart : end;
        }

        /** The period an event falls in; null without a time or in the future. */
        String key(LocalDateTime at) {
            return at == null || at.isAfter(now) ? null : periodKey(startOf.apply(at.toLocalDate()), period);
        }

        boolean isCurrent(LocalDateTime at) {
            return at != null && !at.isBefore(curStart) && !at.isAfter(now);
        }

        boolean isPrevious(LocalDateTime at) {
            return at != null && !at.isBefore(prevStart) && at.isBefore(prevEnd);
        }
    }

    /** 2026-10-04 for a day, the Monday it starts for a week, 2026-10 for a month, 2026-Q4 for a quarter. */
    private static String periodKey(LocalDate start, String period) {
        return switch (period) {
            case "month" -> YearMonth.from(start).toString();
            case "quarter" -> start.getYear() + "-Q" + ((start.getMonthValue() - 1) / 3 + 1);
            default -> start.toString();
        };
    }

    /**
     * Estimated cost of a receipt line: the product's current refill cost (or new-tank cost for a
     * new tank sold before 3.0). Returns -1 for products without a refill cost, which are added to uncosted.
     */
    static double lineCost(DataStore.Line l, Map<String, DataStore.Product> products, Set<String> uncosted) {
        DataStore.Product p = products.get(l.productId());
        if (p == null) return -1;
        if (p.refillCost() <= 0) {
            uncosted.add(p.label());
            return -1;
        }
        return l.qty() * p.costFor(l.newTank());
    }

    static String itemsText(DataStore.Receipt r, Map<String, DataStore.Product> products) {
        return r.lines().stream().map(l -> {
            DataStore.Product p = products.get(l.productId());
            return l.qty() + " × " + (p == null ? "#" + l.productId() : p.label());
        }).collect(Collectors.joining(", "));
    }

    /**
     * Stock per product (tanks with load, empty, damaged, at the refiller) with the average sold
     * per day over the last 14 days and how long the tanks with load last at that pace. Also the
     * refill trips still waiting to come back.
     */
    static Map<String, Object> inventory(DataStore store) {
        LocalDateTime since = LocalDate.now().minusDays(13).atStartOfDay();
        Map<String, Integer> sold14 = new HashMap<>();
        for (DataStore.Line l : store.lines()) {
            if (l.time() != null && !l.time().isBefore(since)) sold14.merge(l.productId(), l.qty(), Integer::sum);
        }
        Map<String, DataStore.Stock> stock = store.stock();
        List<DataStore.Product> products = store.products();
        List<DataStore.Supplier> suppliers = store.suppliers();
        Map<String, DataStore.Product> byId = new HashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        int alerts = 0, totalLoaded = 0, totalEmpty = 0, totalDamaged = 0, totalRefiller = 0;
        Map<Double, int[]> bySize = new TreeMap<>(); // weight -> with load, empty, at refiller
        for (DataStore.Product p : products) {
            byId.put(p.id(), p);
            DataStore.Stock st = stock.getOrDefault(p.id(), DataStore.Stock.NONE);
            String status = PosApi.stockStatus(st, p.reorderLevel());
            if (!status.equals("ok")) alerts++;
            double perDay = sold14.getOrDefault(p.id(), 0) / 14.0;
            Double daysLeft = perDay > 0 ? Math.max(0, st.loaded()) / perDay : null;
            DataStore.Supplier sup = suppliers.stream().filter(s -> s.supplies(p.brand())).findFirst().orElse(null);
            items.add(Json.obj("id", p.id(), "label", p.label(), "brand", p.brand(),
                    "loaded", st.loaded(), "empty", st.empty(), "damaged", st.damaged(), "atRefiller", st.atRefiller(),
                    "reorderLevel", p.reorderLevel(), "status", status, "perDay", perDay, "daysLeft", daysLeft,
                    "supplierId", sup == null ? null : sup.id(), "supplier", sup == null ? null : sup.name()));
            totalLoaded += Math.max(0, st.loaded());
            totalEmpty += Math.max(0, st.empty());
            int[] size = bySize.computeIfAbsent(p.weight(), k -> new int[5]);
            size[0] += Math.max(0, st.loaded());
            size[1] += Math.max(0, st.empty());
            size[2] += Math.max(0, st.atRefiller());
            totalDamaged += Math.max(0, st.damaged());
            totalRefiller += Math.max(0, st.atRefiller());
        }
        // Most urgent first: out of stock, then low, then by days left.
        items.sort(Comparator.comparingInt((Map<String, Object> o) -> switch ((String) o.get("status")) {
            case "out" -> 0;
            case "low" -> 1;
            default -> 2;
        }).thenComparingDouble(o -> o.get("daysLeft") == null ? Double.MAX_VALUE : (Double) o.get("daysLeft")));

        for (var debts : store.owedTanks().values()) {
            for (DataStore.OwedTank debt : debts) {
                DataStore.Product product = byId.get(debt.productId());
                if (product != null) bySize.computeIfAbsent(product.weight(), k -> new int[5])[3] += debt.qty();
            }
        }
        // Customers' own tanks kept at the store: not the store's stock either.
        int totalKept = 0;
        for (var kept : store.depositsHeld().values()) {
            for (var e : kept.entrySet()) {
                DataStore.Product product = byId.get(e.getKey());
                totalKept += e.getValue();
                if (product != null) bySize.computeIfAbsent(product.weight(), k -> new int[5])[4] += e.getValue();
            }
        }

        // Customer-held tanks are separate from the stock total.
        List<Object> sizes = new ArrayList<>();
        for (var e : bySize.entrySet()) {
            int[] v = e.getValue();
            sizes.add(Json.obj("weight", e.getKey(), "loaded", v[0], "empty", v[1], "atRefiller", v[2], "withCustomers", v[3], "kept", v[4], "total", v[0] + v[1] + v[2]));
        }

        List<Object> openRefills = new ArrayList<>();
        double owedToRefillers = 0;
        Map<String, String> supName = suppliers.stream().collect(Collectors.toMap(DataStore.Supplier::id, DataStore.Supplier::name, (a, b) -> a));
        for (DataStore.Refill rf : store.refills()) {
            List<Object> waiting = new ArrayList<>();
            int count = 0;
            for (DataStore.RefillItem it : store.refillItems(rf.id())) {
                if (it.outstanding() <= 0) continue;
                DataStore.Product p = byId.get(it.productId());
                waiting.add(Json.obj("product", p == null ? "Deleted product #" + it.productId() : p.label(), "outstanding", it.outstanding()));
                count += it.outstanding();
            }
            double cost = store.refillCost(rf.id()), paid = store.refillPaid(rf.id());
            double owed = DataStore.round(Math.max(0, cost - paid));
            owedToRefillers += owed;
            if (count == 0 && owed <= 0) continue;
            long days = rf.time() == null ? 0 : ChronoUnit.DAYS.between(rf.time().toLocalDate(), LocalDate.now());
            openRefills.add(Json.obj("id", rf.id(), "time", rf.rawTime(), "supplier", supName.getOrDefault(rf.supplierId(), "Deleted supplier"),
                    "days", days, "tanks", count, "items", waiting, "cost", cost, "paid", paid, "owed", owed));
        }

        List<DataStore.Movement> moves = store.movements();
        List<Object> recent = new ArrayList<>();
        for (int i = moves.size() - 1; i >= 0 && recent.size() < 8; i--) {
            DataStore.Movement m = moves.get(i);
            DataStore.Product p = byId.get(m.productId());
            recent.add(Json.obj("time", m.rawTime(), "product", p == null ? "Deleted product #" + m.productId() : p.label(),
                    "type", m.type(), "loaded", m.loaded(), "empty", m.empty(), "damaged", m.damaged(), "atRefiller", m.atRefiller(),
                    "staff", m.staff(), "note", m.note()));
        }
        // What's owed to each supplier: refill trips and new tanks bought, not yet fully paid.
        Map<String, Double> owedBySupplier = new HashMap<>();
        for (DataStore.Bill b : store.bills()) if (b.owed() > 0) owedBySupplier.merge(b.supplierId(), b.owed(), Double::sum);
        List<Object> suppliersOut = new ArrayList<>();
        for (DataStore.Supplier s : suppliers) {
            suppliersOut.add(Json.obj("id", s.id(), "name", s.name(), "contact", s.contact(), "brands", s.brands(),
                    "owed", DataStore.round(owedBySupplier.getOrDefault(s.id(), 0.0))));
        }
        double owedToSuppliers = owedBySupplier.values().stream().mapToDouble(Double::doubleValue).sum();
        return Json.obj("items", items, "alerts", alerts, "totalLoaded", totalLoaded, "totalEmpty", totalEmpty,
                "totalDamaged", totalDamaged, "totalRefiller", totalRefiller, "refills", openRefills, "movements", recent,
                "owedToRefillers", DataStore.round(owedToRefillers), "owedToSuppliers", DataStore.round(owedToSuppliers),
                "bySize", sizes, "totalTanks", totalLoaded + totalEmpty + totalRefiller, "totalKept", totalKept, "suppliers", suppliersOut);
    }

    /** Who owes money right now, largest balance first. */
    static Map<String, Object> receivables(DataStore store) {
        List<DataStore.Receipt> receipts = store.receipts();
        Map<String, Double> paid = store.paidByReceipt(receipts);
        Map<String, DataStore.Customer> customers = store.customers().stream()
                .collect(Collectors.toMap(DataStore.Customer::id, Function.identity(), (a, b) -> a));
        Map<String, double[]> owed = new LinkedHashMap<>(); // balance, unpaid receipts
        Map<String, LocalDateTime> oldest = new HashMap<>();
        double total = 0;
        for (DataStore.Receipt r : receipts) {
            double bal = DataStore.balance(r, paid);
            if (bal <= 0) continue;
            total += bal;
            double[] v = owed.computeIfAbsent(r.customerId(), k -> new double[2]);
            v[0] += bal;
            v[1]++;
            if (r.time() != null) oldest.merge(r.customerId(), r.time(), (a, b) -> a.isBefore(b) ? a : b);
        }
        DataStore.Opening start = store.opening();
        for (var e : store.openingOwed().entrySet()) {
            total += e.getValue();
            double[] v = owed.computeIfAbsent(e.getKey(), k -> new double[2]);
            v[0] += e.getValue();
            v[1]++;
            oldest.merge(e.getKey(), start.time(), (a, b) -> a.isBefore(b) ? a : b);
        }
        List<Map.Entry<String, double[]>> sorted = new ArrayList<>(owed.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        List<Object> out = new ArrayList<>();
        for (var e : sorted.subList(0, Math.min(8, sorted.size()))) {
            DataStore.Customer c = customers.get(e.getKey());
            LocalDateTime since = oldest.get(e.getKey());
            out.add(Json.obj("customer", c == null ? "Deleted customer #" + e.getKey() : c.name(),
                    "balance", DataStore.round(e.getValue()[0]), "receipts", (int) e.getValue()[1],
                    "since", since == null ? null : since.format(DataStore.TIME),
                    "days", since == null ? null : ChronoUnit.DAYS.between(since.toLocalDate(), LocalDate.now())));
        }
        return Json.obj("total", DataStore.round(total), "customers", owed.size(), "top", out);
    }

    /** Money staff put in for suppliers from their own pocket: what's still owed to each, and recent entries. */
    static Map<String, Object> staffMoney(DataStore store) {
        Map<String, String> supName = store.suppliers().stream()
                .collect(Collectors.toMap(DataStore.Supplier::id, DataStore.Supplier::name, (a, b) -> a));
        List<Object> owed = new ArrayList<>();
        double total = 0;
        for (var e : store.owedToStaff().entrySet()) {
            if (e.getValue() <= 0.005) continue;
            total += e.getValue();
            owed.add(Json.obj("staff", e.getKey(), "owed", e.getValue()));
        }
        List<Map<String, Object>> recent = new ArrayList<>();
        for (DataStore.SupplierPayment sp : store.supplierPayments()) {
            if (!sp.fromStaff()) continue;
            recent.add(Json.obj("id", sp.id(), "time", sp.rawTime(), "kind", "advance", "staff", sp.paidBy(), "amount", sp.amount(),
                    "supplier", supName.getOrDefault(sp.supplierId(), "supplier"),
                    "bill", sp.refillId().startsWith("P") ? "new tanks" : sp.refillId().startsWith("O") ? "owed on the start date" : "trip #" + sp.refillId(), "by", sp.staff(), "note", sp.note()));
        }
        for (DataStore.Payback p : store.paybacks()) {
            recent.add(Json.obj("id", p.id(), "time", p.rawTime(), "kind", "payback", "staff", p.staff(), "amount", p.amount(), "by", p.recordedBy(), "note", p.note()));
        }
        recent.sort((a, b) -> ((String) b.get("time")).compareTo((String) a.get("time")));
        return Json.obj("total", DataStore.round(total), "owed", owed, "recent", new ArrayList<>(recent.subList(0, Math.min(20, recent.size()))));
    }

    /**
     * Cash on hand per day: cash in (not GCash) − cash paid to suppliers from sales − paybacks − expenses paid in
     * cash. Days without any cash movement are missing.
     */
    static TreeMap<LocalDate, Double> cashByDay(DataStore store) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        java.util.function.BiConsumer<LocalDateTime, Double> add = (t, v) -> { if (t != null && v != 0) out.merge(t.toLocalDate(), v, Double::sum); };
        for (DataStore.Receipt r : store.receipts()) if (!r.gcash()) add.accept(r.time(), r.paidAtSale());
        for (DataStore.Payment p : store.payments()) if (!p.gcash()) add.accept(p.time(), p.amount());
        for (DataStore.SupplierPayment sp : store.supplierPayments()) if (sp.cashFromDrawer()) add.accept(sp.time(), -sp.amount());
        for (DataStore.Payback pb : store.paybacks()) add.accept(pb.time(), -pb.amount());
        for (DataStore.Expense e : store.expenses()) if (e.cash()) add.accept(e.time(), -e.amount());
        return out;
    }

    /**
     * Cash in the drawer at the end of a day: the starting cash plus each day's cash on hand since the start
     * date. Null without starting balances or before the start date.
     */
    static Double drawer(DataStore.Opening o, TreeMap<LocalDate, Double> cash, LocalDate day) {
        if (o == null || day.isBefore(o.start())) return null;
        double sum = o.cash();
        for (double v : cash.subMap(o.start(), true, day, true).values()) sum += v;
        return DataStore.round(sum);
    }

    /** Everything that happened on one day, for the daily summary. */
    private static final class Day {
        int receipts, tanks, tanksLent, tanksReturned, sent, received, bought;
        double revenue, discounts, swapFees, atSale, later, gcash, credit, refillBill, purchaseBill, paidSales, paidFromDrawer, paidOwner, paidStaff, paidCheck,
                paidOnline, paidBack, expenses, expensesCash, cost, costedRevenue;
        boolean anyCosted;
        final Map<String, double[]> byProduct = new LinkedHashMap<>(); // label -> tanks, revenue
        final Map<String, Double> byStaff = new TreeMap<>(); // whose own money paid suppliers

        double collected() { return atSale + later; }

        /** Cash on hand: cash in minus cash out. Checks and online transfers come from the bank, so only cash leaves the drawer. */
        double cashLeft() { return collected() - gcash - paidFromDrawer - paidBack - expensesCash; }

        double paidSuppliers() { return paidSales + paidOwner + paidStaff; }

        Double profit() { return anyCosted ? DataStore.round(costedRevenue - cost) : null; }
    }

    /**
     * The summary of one day (date "YYYY-MM-DD", today when blank): sales, money in and out (with
     * where supplier money came from), tanks moved, tanks lent and returned, and that day's receipts.
     * Also one row for each of the `days` days up to it, newest first, to compare and open earlier days.
     */
    static Map<String, Object> daily(DataStore store, String dateText, int days) {
        LocalDate today = LocalDate.now();
        LocalDate date;
        try {
            date = dateText == null || dateText.isBlank() ? today : LocalDate.parse(dateText.trim());
        } catch (DateTimeParseException e) {
            throw new Http.Error(400, "The date must look like " + today);
        }
        if (date.isAfter(today)) date = today;
        LocalDate from = date.minusDays(days - 1);
        final LocalDate day = date;
        TreeMap<LocalDate, Day> byDay = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(date); d = d.plusDays(1)) byDay.put(d, new Day());
        Function<LocalDateTime, Day> at = t -> t == null ? null : byDay.get(t.toLocalDate());

        Map<String, DataStore.Product> products = store.productMap();
        Map<String, DataStore.Customer> customers = store.customers().stream()
                .collect(Collectors.toMap(DataStore.Customer::id, Function.identity(), (a, b) -> a));
        List<DataStore.Receipt> receipts = store.receipts();
        Map<String, Double> paid = store.paidByReceipt(receipts);
        Set<String> uncosted = new TreeSet<>();
        List<Object> dayReceipts = new ArrayList<>();
        for (DataStore.Receipt r : receipts) {
            Day d = at.apply(r.time());
            if (d == null) continue;
            d.receipts++;
            d.tanks += r.qty();
            d.revenue += r.total();
            d.discounts += r.discount();
            d.atSale += r.paidAtSale();
            if (r.gcash()) d.gcash += r.paidAtSale();
            d.credit += Math.max(0, r.total() - r.paidAtSale());
            double sub = r.subtotal(), share = sub > 0 ? r.total() / sub : 0;
            for (DataStore.Line l : r.lines()) {
                d.swapFees += l.qty() * l.swapFee();
                if (l.owesTank()) d.tanksLent += l.qty();
                DataStore.Product p = products.get(l.productId());
                double[] v = d.byProduct.computeIfAbsent(p == null ? "Deleted product #" + l.productId() : p.label(), k -> new double[2]);
                v[0] += l.qty();
                v[1] += l.amount() * share;
                double c = lineCost(l, products, r.time().toLocalDate().equals(day) ? uncosted : new HashSet<>());
                if (c >= 0) {
                    d.anyCosted = true;
                    d.cost += c;
                    d.costedRevenue += l.amount() * share;
                }
            }
            if (r.time().toLocalDate().equals(day)) {
                double bal = DataStore.balance(r, paid);
                double got = paid.getOrDefault(r.id(), 0.0);
                DataStore.Customer c = customers.get(r.customerId());
                dayReceipts.add(0, Json.obj("id", r.id(), "time", r.rawTime(), "customer", c == null ? "Unknown customer #" + r.customerId() : c.name(),
                        "items", itemsText(r, products), "total", r.total(), "balance", bal,
                        "status", bal <= 0 ? "paid" : got <= 0.005 ? "unpaid" : "partial", "staff", r.staff().isEmpty() ? "Desktop app" : r.staff()));
            }
        }
        for (DataStore.Payment p : store.payments()) {
            Day d = at.apply(p.time());
            if (d != null) {
                d.later += p.amount();
                if (p.gcash()) d.gcash += p.amount();
            }
        }
        for (DataStore.TankReturn t : store.tankReturns()) {
            Day d = at.apply(t.time());
            if (d != null) d.tanksReturned += t.qty();
        }
        for (DataStore.Movement m : store.movements()) {
            Day d = at.apply(m.time());
            if (d == null) continue;
            switch (m.type()) {
                case "refill-send" -> d.sent += m.atRefiller();
                case "refill-receive" -> {
                    d.received += m.loaded();
                    d.refillBill += m.loaded() * m.unitCost();
                }
                case "purchase" -> {
                    d.bought += m.loaded();
                    d.purchaseBill += m.loaded() * m.unitCost();
                }
                default -> {
                }
            }
        }
        for (DataStore.SupplierPayment sp : store.supplierPayments()) {
            Day d = at.apply(sp.time());
            if (d == null) continue;
            if (sp.byCheck()) d.paidCheck += sp.amount();
            if (sp.online()) d.paidOnline += sp.amount();
            if (sp.cashFromDrawer()) d.paidFromDrawer += sp.amount();
            if (sp.fromStaff()) {
                d.paidStaff += sp.amount();
                d.byStaff.merge(sp.paidBy(), sp.amount(), Double::sum);
            } else if (sp.fromOwner()) {
                d.paidOwner += sp.amount();
            } else {
                d.paidSales += sp.amount();
            }
        }
        for (DataStore.Expense e : store.expenses()) {
            Day d = at.apply(e.time());
            if (d == null) continue;
            d.expenses += e.amount();
            if (e.cash()) d.expensesCash += e.amount();
        }
        for (DataStore.Payback p : store.paybacks()) {
            Day d = at.apply(p.time());
            if (d != null) d.paidBack += p.amount();
        }

        Day d = byDay.get(date);
        List<Map.Entry<String, double[]>> prods = new ArrayList<>(d.byProduct.entrySet());
        prods.sort((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]));
        List<Object> productOut = new ArrayList<>();
        for (var e : prods) productOut.add(Json.obj("label", e.getKey(), "qty", (int) e.getValue()[0], "revenue", DataStore.round(e.getValue()[1])));
        List<Object> staffOut = new ArrayList<>();
        d.byStaff.forEach((k, v) -> staffOut.add(Json.obj("staff", k, "amount", DataStore.round(v))));
        DataStore.Opening start = store.opening();
        TreeMap<LocalDate, Double> cash = cashByDay(store);
        List<Object> rows = new ArrayList<>();
        for (var e : byDay.descendingMap().entrySet()) {
            Day x = e.getValue();
            rows.add(Json.obj("date", e.getKey().toString(), "receipts", x.receipts, "tanks", x.tanks, "revenue", DataStore.round(x.revenue),
                    "collected", DataStore.round(x.collected()), "paidSuppliers", DataStore.round(x.paidSuppliers()),
                    "expenses", DataStore.round(x.expenses), "cashLeft", DataStore.round(x.cashLeft()), "profit", x.profit(),
                    "drawer", drawer(start, cash, e.getKey())));
        }
        Map<String, Object> totals = Json.obj(
                "receipts", d.receipts, "tanks", d.tanks, "revenue", DataStore.round(d.revenue), "discounts", DataStore.round(d.discounts),
                "swapFees", DataStore.round(d.swapFees), "collectedAtSale", DataStore.round(d.atSale), "collectedLater", DataStore.round(d.later),
                "collected", DataStore.round(d.collected()), "collectedGcash", DataStore.round(d.gcash), "credit", DataStore.round(d.credit),
                "tanksLent", d.tanksLent, "tanksReturned", d.tanksReturned, "refillSent", d.sent, "refillReceived", d.received,
                "refillBill", DataStore.round(d.refillBill), "bought", d.bought, "purchaseBill", DataStore.round(d.purchaseBill),
                "paidSales", DataStore.round(d.paidSales), "paidOwner", DataStore.round(d.paidOwner), "paidStaff", DataStore.round(d.paidStaff),
                "paidCheck", DataStore.round(d.paidCheck), "paidOnline", DataStore.round(d.paidOnline), "paidSuppliers", DataStore.round(d.paidSuppliers()),
                "paidBack", DataStore.round(d.paidBack), "expenses", DataStore.round(d.expenses), "expensesCash", DataStore.round(d.expensesCash),
                "cashLeft", DataStore.round(d.cashLeft()), "profit", d.profit(), "drawer", drawer(start, cash, date),
                "drawerBefore", drawer(start, cash, date.minusDays(1)), "startCash", start != null && date.equals(start.start()) ? start.cash() : null);
        return Json.obj("date", date.toString(), "startDate", start == null ? null : start.start().toString(), "today", date.equals(today), "previous", date.minusDays(1).toString(),
                "next", date.equals(today) ? null : date.plusDays(1).toString(),
                "totals", totals, "uncosted", new ArrayList<>(uncosted), "byProduct", productOut, "paidByStaff", staffOut,
                "receipts", dayReceipts, "days", rows);
    }

    /** Customers who took tanks without bringing an empty, most tanks first. */
    static Map<String, Object> tanksOut(DataStore store) {
        Map<String, DataStore.Customer> customers = store.customers().stream()
                .collect(Collectors.toMap(DataStore.Customer::id, Function.identity(), (a, b) -> a));
        Map<String, DataStore.Product> products = store.productMap();
        List<Map<String, Object>> rows = new ArrayList<>();
        int total = 0;
        for (var e : store.owedTanks().entrySet()) {
            int n = e.getValue().stream().mapToInt(DataStore.OwedTank::qty).sum();
            total += n;
            Map<String, Integer> byProduct = new LinkedHashMap<>();
            for (DataStore.OwedTank o : e.getValue()) {
                DataStore.Product p = products.get(o.productId());
                byProduct.merge(p == null ? "#" + o.productId() : p.label(), o.qty(), Integer::sum);
            }
            LocalDateTime since = e.getValue().get(0).time();
            DataStore.Customer c = customers.get(e.getKey());
            rows.add(Json.obj("customerId", e.getKey(), "customer", c == null ? "Deleted customer #" + e.getKey() : c.name(),
                    "tanks", n, "items", byProduct.entrySet().stream().map(x -> x.getValue() + " × " + x.getKey()).collect(Collectors.joining(", ")),
                    "since", since == null ? null : since.format(DataStore.TIME),
                    "days", since == null ? null : ChronoUnit.DAYS.between(since.toLocalDate(), LocalDate.now())));
        }
        rows.sort((a, b) -> Integer.compare((int) b.get("tanks"), (int) a.get("tanks")));
        return Json.obj("total", total, "customers", rows.size(), "top", rows.subList(0, Math.min(10, rows.size())));
    }

    /** Top N by revenue; with foldOther the remainder becomes a single "Other" row. */
    private static List<Object> ranked(Map<String, Agg> groups, int limit, boolean foldOther, Function<String, String> label) {
        List<Map.Entry<String, Agg>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue().revenue, a.getValue().revenue));
        List<Object> out = new ArrayList<>();
        Agg other = new Agg();
        int otherGroups = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Agg> e = sorted.get(i);
            Agg a = e.getValue();
            if (i < limit) {
                out.add(Json.obj("id", e.getKey(), "label", label.apply(e.getKey()), "revenue", DataStore.round(a.revenue), "count", a.count, "qty", a.qty));
            } else {
                other.revenue += a.revenue;
                other.count += a.count;
                other.qty += a.qty;
                otherGroups++;
            }
        }
        if (foldOther && otherGroups > 0) {
            out.add(Json.obj("id", "", "label", "Other (" + otherGroups + ")", "revenue", DataStore.round(other.revenue), "count", other.count, "qty", other.qty));
        }
        return out;
    }
}

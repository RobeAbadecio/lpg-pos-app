import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
        double collected = 0, prevCollected = 0, discounts = 0, prevDiscounts = 0;
        // Profit only counts lines whose product has a refill cost; the rest are listed in uncosted.
        double cost = 0, prevCost = 0, costedRevenue = 0, prevCostedRevenue = 0, supplierPaid = 0, prevSupplierPaid = 0;
        boolean anyCosted = false, prevAnyCosted = false;
        Set<String> uncosted = new TreeSet<>(); // products sold in range with no refill cost set
        Map<String, Agg> byStaff = new HashMap<>(), byCustomer = new HashMap<>();
        Map<String, double[]> byProduct = new HashMap<>(); // revenue, qty, receipts, cost
        for (DataStore.Receipt r : receipts) {
            LocalDateTime at = r.time();
            if (inRange.test(at)) {
                total.add(r);
                collected += r.paidAtSale();
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
        for (DataStore.SupplierPayment sp : store.supplierPayments()) {
            if (inRange.test(sp.time())) supplierPaid += sp.amount();
            else if (inPrev.test(sp.time())) prevSupplierPaid += sp.amount();
        }
        for (DataStore.Payment p : payments) {
            if (inRange.test(p.time())) collected += p.amount();
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
        totals.put("supplierPaid", DataStore.round(supplierPaid));
        Map<String, Object> previous = null;
        if (prevStart != null) {
            previous = prev.json(prevCollected, prevDiscounts);
            previous.put("cost", DataStore.round(prevCost));
            previous.put("profit", prevAnyCosted ? DataStore.round(prevCostedRevenue - prevCost) : null);
            previous.put("supplierPaid", DataStore.round(prevSupplierPaid));
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
     * Estimated cost of a receipt line: the product's current refill cost (or new-tank cost for a
     * new tank sold before 3.0). Returns -1 for products without a refill cost, which are added to uncosted.
     */
    private static double lineCost(DataStore.Line l, Map<String, DataStore.Product> products, Set<String> uncosted) {
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
            int[] size = bySize.computeIfAbsent(p.weight(), k -> new int[3]);
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

        List<Object> sizes = new ArrayList<>();
        double kgLoaded = 0;
        for (var e : bySize.entrySet()) {
            kgLoaded += e.getKey() * e.getValue()[0];
            sizes.add(Json.obj("weight", e.getKey(), "loaded", e.getValue()[0], "empty", e.getValue()[1], "atRefiller", e.getValue()[2],
                    "kgLoaded", DataStore.round(e.getKey() * e.getValue()[0])));
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
                "bySize", sizes, "kgLoaded", DataStore.round(kgLoaded), "suppliers", suppliersOut);
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

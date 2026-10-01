import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Sales figures for the admin dashboard, for one date range plus the period before it. */
final class Stats {
    private Stats() {}

    private static final class Agg {
        double revenue;
        int count, qty;
        final Set<String> customers = new HashSet<>();

        void add(DataStore.Txn t, double amount) {
            revenue += amount;
            count++;
            qty += t.qty();
            customers.add(t.customerId());
        }

        Map<String, Object> json() {
            return Json.obj("revenue", revenue, "count", count, "qty", qty,
                    "avg", count == 0 ? 0.0 : revenue / count, "customers", customers.size());
        }
    }

    static Map<String, Object> compute(DataStore store, String range) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        List<DataStore.Txn> txns = store.transactions();
        Map<String, DataStore.Product> products = store.products().stream()
                .collect(Collectors.toMap(DataStore.Product::id, Function.identity(), (a, b) -> a));
        Map<String, DataStore.Customer> customers = store.customers().stream()
                .collect(Collectors.toMap(DataStore.Customer::id, Function.identity(), (a, b) -> a));

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
                LocalDate first = txns.stream().map(DataStore.Txn::time).filter(Objects::nonNull)
                        .map(LocalDateTime::toLocalDate).min(Comparator.naturalOrder()).orElse(today);
                if (first.isAfter(today)) first = today;
                start = first.atStartOfDay();
                bucket = ChronoUnit.DAYS.between(first, today) > 120 ? "month" : "day";
            }
        }

        // Buckets in order, keyed so each sale can find its slot.
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

        Agg total = new Agg(), prev = new Agg();
        Map<String, Agg> byProduct = new HashMap<>(), byStaff = new HashMap<>(), byCustomer = new HashMap<>();
        for (DataStore.Txn t : txns) {
            double amount = DataStore.amount(t, products);
            LocalDateTime at = t.time();
            boolean inRange = range.equals("all")
                    ? at == null || !at.isBefore(start)
                    : at != null && !at.isBefore(start) && at.isBefore(end);
            if (inRange) {
                total.add(t, amount);
                byProduct.computeIfAbsent(t.productId(), k -> new Agg()).add(t, amount);
                byStaff.computeIfAbsent(t.staff().isEmpty() ? "Desktop app" : t.staff(), k -> new Agg()).add(t, amount);
                byCustomer.computeIfAbsent(t.customerId(), k -> new Agg()).add(t, amount);
                if (at != null) {
                    String key = switch (bucket) {
                        case "hour" -> String.format("%02d", at.getHour());
                        case "day" -> at.toLocalDate().toString();
                        default -> YearMonth.from(at).toString();
                    };
                    Agg a = series.get(key);
                    if (a != null) a.add(t, amount);
                }
            } else if (prevStart != null && at != null && !at.isBefore(prevStart) && at.isBefore(prevEnd)) {
                prev.add(t, amount);
            }
        }

        List<Object> seriesOut = new ArrayList<>();
        series.forEach((k, a) -> seriesOut.add(Json.obj("key", k, "revenue", a.revenue, "count", a.count, "qty", a.qty)));

        List<Object> productOut = ranked(byProduct, 8, true, id -> {
            DataStore.Product p = products.get(id);
            return p == null ? "Deleted product #" + id : p.label();
        });
        List<Object> staffOut = ranked(byStaff, 8, true, Function.identity());
        List<Object> customerOut = ranked(byCustomer, 5, false, id -> {
            DataStore.Customer c = customers.get(id);
            return c == null ? "Deleted customer #" + id : c.name();
        });

        List<DataStore.Txn> latest = new ArrayList<>(txns);
        latest.sort(Comparator.comparing((DataStore.Txn t) -> t.time() == null ? LocalDateTime.MIN : t.time())
                .thenComparing(t -> parseId(t.id())).reversed());
        List<Object> recent = new ArrayList<>();
        for (DataStore.Txn t : latest.subList(0, Math.min(12, latest.size()))) {
            DataStore.Customer c = customers.get(t.customerId());
            DataStore.Product p = products.get(t.productId());
            recent.add(Json.obj(
                    "id", t.id(), "time", t.rawTime(),
                    "customer", c == null ? "Unknown customer #" + t.customerId() : c.name(),
                    "product", p == null ? "Unknown product #" + t.productId() : p.label(),
                    "qty", t.qty(), "amount", amount(t, products),
                    "staff", t.staff().isEmpty() ? "Desktop app" : t.staff()));
        }

        return Json.obj(
                "range", range,
                "bucket", bucket,
                "start", start.toLocalDate().toString(),
                "compare", compare,
                "totals", total.json(),
                "previous", prevStart == null ? null : prev.json(),
                "series", seriesOut,
                "byProduct", productOut,
                "byStaff", staffOut,
                "topCustomers", customerOut,
                "recent", recent,
                "counts", Json.obj("customers", customers.size(), "products", products.size(), "transactions", txns.size()));
    }

    /**
     * Stock on hand per product, with the average sold per day over the last 14 days and how
     * many days the current full stock lasts at that pace.
     */
    static Map<String, Object> inventory(DataStore store) {
        LocalDateTime since = LocalDate.now().minusDays(13).atStartOfDay();
        Map<String, Integer> sold14 = new HashMap<>();
        for (DataStore.Txn t : store.transactions()) {
            if (t.time() != null && !t.time().isBefore(since)) sold14.merge(t.productId(), t.qty(), Integer::sum);
        }
        Map<String, String> lastDelivery = new HashMap<>();
        List<DataStore.Movement> moves = store.movements();
        for (DataStore.Movement m : moves) if (m.type().equals("delivery")) lastDelivery.put(m.productId(), m.rawTime());

        Map<String, DataStore.Stock> stock = store.stock();
        List<DataStore.Product> products = store.products();
        Map<String, DataStore.Product> byId = new HashMap<>();
        List<Object> items = new ArrayList<>();
        int alerts = 0;
        for (DataStore.Product p : products) {
            byId.put(p.id(), p);
            DataStore.Stock st = stock.getOrDefault(p.id(), new DataStore.Stock(0, 0));
            String status = PosApi.stockStatus(st, p.reorderLevel());
            if (!status.equals("ok")) alerts++;
            double perDay = sold14.getOrDefault(p.id(), 0) / 14.0;
            Double daysLeft = perDay > 0 ? Math.max(0, st.full()) / perDay : null;
            items.add(Json.obj("id", p.id(), "label", p.label(), "full", st.full(), "empty", st.empty(),
                    "reorderLevel", p.reorderLevel(), "status", status, "perDay", perDay, "daysLeft", daysLeft,
                    "lastDelivery", lastDelivery.get(p.id())));
        }
        // Most urgent first: out of stock, then low, then by days left.
        items.sort(Comparator.comparingInt((Object o) -> switch ((String) ((Map<?, ?>) o).get("status")) {
            case "out" -> 0;
            case "low" -> 1;
            default -> 2;
        }).thenComparingDouble(o -> {
            Object d = ((Map<?, ?>) o).get("daysLeft");
            return d == null ? Double.MAX_VALUE : (Double) d;
        }));

        List<Object> recent = new ArrayList<>();
        for (int i = moves.size() - 1; i >= 0 && recent.size() < 8; i--) {
            DataStore.Movement m = moves.get(i);
            DataStore.Product p = byId.get(m.productId());
            recent.add(Json.obj("time", m.rawTime(), "product", p == null ? "Deleted product #" + m.productId() : p.label(),
                    "type", m.type(), "full", m.fullDelta(), "empty", m.emptyDelta(), "staff", m.staff(), "note", m.note()));
        }
        int totalFull = 0, totalEmpty = 0;
        for (DataStore.Product p : products) {
            DataStore.Stock st = stock.getOrDefault(p.id(), new DataStore.Stock(0, 0));
            totalFull += Math.max(0, st.full());
            totalEmpty += Math.max(0, st.empty());
        }
        return Json.obj("items", items, "alerts", alerts, "totalFull", totalFull, "totalEmpty", totalEmpty, "movements", recent);
    }

    private static double amount(DataStore.Txn t, Map<String, DataStore.Product> products) {
        return DataStore.amount(t, products);
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
                out.add(Json.obj("id", e.getKey(), "label", label.apply(e.getKey()), "revenue", a.revenue, "count", a.count, "qty", a.qty));
            } else {
                other.revenue += a.revenue;
                other.count += a.count;
                other.qty += a.qty;
                otherGroups++;
            }
        }
        if (foldOther && otherGroups > 0) {
            out.add(Json.obj("id", "", "label", "Other (" + otherGroups + ")", "revenue", other.revenue, "count", other.count, "qty", other.qty));
        }
        return out;
    }

    private static int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

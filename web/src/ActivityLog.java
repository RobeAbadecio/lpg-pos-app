import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Append-only audit trail (WebActivity.log) with the latest entries kept in memory for the dashboard. */
final class ActivityLog {
    private static final int KEEP = 300;

    record Entry(String time, String actor, String ip, String message) {}

    private final Path file;
    private final Deque<Entry> recent = new ArrayDeque<>();

    ActivityLog(Path dir) {
        this.file = dir.resolve("WebActivity.log");
        try {
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String line : lines.subList(Math.max(0, lines.size() - KEEP), lines.size())) {
                    String[] p = line.split("\t", 4);
                    if (p.length == 4) recent.addFirst(new Entry(p[0], p[1], p[2], p[3]));
                }
            }
        } catch (IOException e) {
            System.err.println("Could not read activity log: " + e.getMessage());
        }
    }

    synchronized void add(String actor, String ip, String message) {
        Entry e = new Entry(LocalDateTime.now().withNano(0).format(DataStore.TIME), clean(actor), clean(ip), clean(message));
        recent.addFirst(e);
        while (recent.size() > KEEP) recent.removeLast();
        String line = String.join("\t", e.time(), e.actor(), e.ip(), e.message()) + "\n";
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.err.println("Could not write activity log: " + ex.getMessage());
        }
    }

    synchronized List<Entry> latest(int n) {
        return recent.stream().limit(n).toList();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ");
    }
}

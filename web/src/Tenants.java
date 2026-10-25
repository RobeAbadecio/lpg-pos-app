import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * One data folder per business: dataDir/tenants/<business id>/ holds that business's CSV files, read and written by
 * its own DataStore (own lock) and ActivityLog. Which business a request belongs to always comes from the signed-in
 * session on the server, never from the browser.
 */
final class Tenants {
    record Tenant(long id, DataStore store, ActivityLog log) {}

    private final Path root;
    private final Platform platform;
    private final Map<Long, Tenant> open = new ConcurrentHashMap<>();

    Tenants(Path dataDir, Platform platform) throws IOException {
        this.root = dataDir.resolve("tenants");
        this.platform = platform;
        Files.createDirectories(root);
    }

    Path root() { return root; }

    Tenant get(long businessId) {
        return open.computeIfAbsent(businessId, id -> {
            try {
                Path dir = root.resolve(Long.toString(id));
                return new Tenant(id, new DataStore(dir), new ActivityLog(dir));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Makes an active account usable for sign-in: copies a password hash still held by the platform (pending sign-up)
     * into the business's WebUsers.csv, then clears it there. Safe to repeat after a crash between the two steps.
     */
    void activate(Platform.Account a) {
        if (!a.active() || a.pendingHash().isEmpty()) return;
        DataStore store = get(a.businessId()).store();
        if (!store.addUser(a.username(), a.pendingSalt(), a.pendingHash())) store.setPassword(a.username(), a.pendingSalt(), a.pendingHash());
        if (a.owner()) store.setDashboard(a.username(), true);
        platform.clearPending(a.username());
    }

    /**
     * First start after the upgrade: the single store's CSV files in dataDir become business 1. They are copied (the
     * originals stay where they are, for rolling back), and its WebUsers become that business's staff accounts.
     * Everything happens inside one platform transaction; the folder is renamed into place just before it commits.
     */
    long migrateLegacy(Path dataDir, String storeName) throws IOException {
        if (!platform.businesses().isEmpty()) return 0;
        List<Path> legacy;
        try (Stream<Path> s = Files.list(dataDir)) {
            legacy = s.filter(p -> !isPlatformFile(p.getFileName().toString())).toList();
        }
        if (legacy.stream().noneMatch(p -> p.getFileName().toString().endsWith(".csv"))) return 0;

        return platform.inTransaction(() -> {
            long id = platform.createActive(storeName, "migration");
            Path target = root.resolve(Long.toString(id));
            if (Files.exists(target)) Files.move(target, root.resolve(id + ".failed-" + System.currentTimeMillis()));
            Path tmp = root.resolve("." + id + ".tmp");
            if (Files.exists(tmp)) deleteTree(tmp);
            for (Path p : legacy) copyTree(p, tmp.resolve(p.getFileName().toString()));
            DataStore copy = new DataStore(tmp);
            for (DataStore.User u : copy.users()) {
                String name = u.username().toLowerCase(Locale.ROOT);
                if (!name.equals(u.username())) throw new IllegalStateException("Username " + u.username() + " has capital letters");
                platform.importAccount(id, name, "staff", "migration");
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            platform.audit("migration", id, "Copied single-store data into business " + id, dataDir.toString());
            return id;
        });
    }

    static boolean isPlatformFile(String name) {
        return name.equals("tenants") || name.startsWith("platform") || name.startsWith(".");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (Files.isDirectory(from)) {
            Files.createDirectories(to);
            try (Stream<Path> s = Files.list(from)) {
                for (Path p : s.toList()) copyTree(p, to.resolve(p.getFileName().toString()));
            }
        } else {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> s = Files.list(p)) {
                for (Path c : s.toList()) deleteTree(c);
            }
        }
        Files.deleteIfExists(p);
    }
}

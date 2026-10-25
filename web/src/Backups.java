import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Backups of everything the server keeps: a consistent copy of platform.db, every business's CSV folder and the
 * payment pictures (platform-files), one folder per run in dataDir/platform-backups/ (the 14 newest are kept). Runs at start-up, then daily, and from the
 * admin page. Each CSV file is replaced atomically when saved, so every copied file is whole.
 *
 * Restore (with the server stopped):  java -cp build:lib/* Backups restore <backup folder> <data folder>
 * The current platform.db, tenants/ and platform-files/ are moved aside first, never deleted.
 */
final class Backups {
    static final int KEEP = 14;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Backups() {}

    static Path dir(Platform platform) {
        return platform.file().toAbsolutePath().getParent().resolve("platform-backups");
    }

    static synchronized Path run(Platform platform, Tenants tenants) {
        Path base = dir(platform);
        Path target = base.resolve(LocalDateTime.now().format(STAMP));
        Path tmp = base.resolve("." + target.getFileName() + ".tmp");
        try {
            Files.createDirectories(base);
            if (Files.exists(tmp)) Tenants.deleteTree(tmp);
            platform.backup(tmp.resolve("platform.db"));
            copy(tenants.root(), tmp.resolve("tenants"));
            Path files = base.getParent().resolve("platform-files");
            if (Files.isDirectory(files)) copy(files, tmp.resolve("platform-files"));
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            platform.setSetting("last_backup", target.getFileName().toString());
            List<Path> all = list(base);
            for (int i = 0; i < all.size() - KEEP; i++) Tenants.deleteTree(all.get(i));
            return target;
        } catch (IOException e) {
            platform.audit("system", null, "Backup failed", e.getMessage());
            throw new IllegalStateException("Backup failed: " + e.getMessage(), e);
        }
    }

    /** Finished backups, oldest first. */
    static List<Path> list(Path base) throws IOException {
        if (!Files.isDirectory(base)) return List.of();
        try (Stream<Path> s = Files.list(base)) {
            return s.filter(p -> Files.isDirectory(p) && !p.getFileName().toString().startsWith(".")).sorted().toList();
        }
    }

    /** Puts a backup back in place. The server must be stopped. Returns where the replaced data was moved. */
    static Path restore(Path backup, Path dataDir) throws Exception {
        if (!Files.isRegularFile(backup.resolve("platform.db")) || !Files.isDirectory(backup.resolve("tenants"))) {
            throw new IllegalArgumentException("Not a backup folder: " + backup);
        }
        try (Platform check = new Platform(backup.resolve("platform.db"))) {
            if (!check.integrity().equals("ok")) throw new IllegalStateException("The backup's database is damaged");
        }
        Path aside = dataDir.resolve("platform-replaced-" + LocalDateTime.now().format(STAMP));
        Files.createDirectories(aside);
        for (String name : List.of("platform.db", "platform.db-wal", "platform.db-shm", "tenants", "platform-files")) {
            Path p = dataDir.resolve(name);
            if (Files.exists(p)) Files.move(p, aside.resolve(name));
        }
        Files.copy(backup.resolve("platform.db"), dataDir.resolve("platform.db"));
        copy(backup.resolve("tenants"), dataDir.resolve("tenants"));
        if (Files.isDirectory(backup.resolve("platform-files"))) copy(backup.resolve("platform-files"), dataDir.resolve("platform-files"));
        return aside;
    }

    static Map<String, Object> health(Platform platform, Tenants tenants) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("database", platform.integrity());
        try {
            out.put("databaseBytes", Files.size(platform.file()));
            FileStore fs = Files.getFileStore(platform.file());
            out.put("diskFreeBytes", fs.getUsableSpace());
            out.put("diskTotalBytes", fs.getTotalSpace());
            out.put("backups", list(dir(platform)).size());
        } catch (IOException e) {
            out.put("error", e.getMessage());
        }
        out.put("lastBackup", platform.setting("last_backup"));
        return out;
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path rel = from.relativize(p);
                boolean hidden = false;
                for (Path part : rel) hidden |= part.toString().startsWith(".");
                if (hidden) continue; // temporary files and folders
                Path dest = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].equals("restore")) {
            System.err.println("Usage: java -cp build:lib/* Backups restore <backup folder> <data folder>");
            System.exit(2);
        }
        Path aside = restore(Path.of(args[1]), Path.of(args[2]));
        System.out.println("Restored. The replaced data was moved to " + aside);
    }
}

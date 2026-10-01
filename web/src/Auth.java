import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Password hashing (PBKDF2), in-memory sessions and login throttling for staff accounts. */
final class Auth {
    static final long SESSION_IDLE_MS = 12L * 60 * 60 * 1000;
    private static final int ITERATIONS = 210_000;
    private static final int MAX_FAILURES = 8;
    private static final long FAILURE_WINDOW_MS = 15L * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY_SALT = b64(randomBytes(16));

    static final class Session {
        final String token;      // cookie value, never sent to the admin page
        final String id;         // public handle the admin page uses to revoke
        final String username;
        final String ip;
        final String via;        // Internet / Local network / This Mac
        final String userAgent;
        final long created = System.currentTimeMillis();
        volatile long lastSeen = created;

        Session(String username, String ip, String via, String userAgent) {
            this.token = b64(randomBytes(32));
            this.id = b64(randomBytes(9));
            this.username = username;
            this.ip = ip;
            this.via = via;
            this.userAgent = userAgent == null ? "" : userAgent;
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- passwords

    static String newSalt() {
        return b64(randomBytes(16));
    }

    static String hash(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getUrlDecoder().decode(salt), ITERATIONS, 256);
            byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return b64(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs a full hash even for unknown users so response time doesn't reveal valid usernames. */
    static boolean verify(String password, DataStore.User user) {
        String salt = user == null ? DUMMY_SALT : user.salt();
        byte[] actual = hash(password, salt).getBytes();
        byte[] expected = (user == null ? "" : user.hash()).getBytes();
        return MessageDigest.isEqual(actual, expected) && user != null;
    }

    static String randomPassword() {
        String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i == 4 || i == 8) sb.append('-');
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- sessions

    Session create(String username, String ip, String via, String userAgent) {
        Session s = new Session(username, ip, via, userAgent);
        sessions.put(s.token, s);
        return s;
    }

    Session get(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        if (now - s.lastSeen > SESSION_IDLE_MS) {
            sessions.remove(token);
            return null;
        }
        s.lastSeen = now;
        return s;
    }

    void end(String token) {
        if (token != null) sessions.remove(token);
    }

    Session revokeById(String id) {
        for (Session s : sessions.values()) {
            if (s.id.equals(id)) {
                sessions.remove(s.token);
                return s;
            }
        }
        return null;
    }

    /** Signs a user out everywhere, optionally keeping the session that asked for it. */
    void revokeUser(String username, String keepToken) {
        sessions.values().removeIf(s -> s.username.equals(username) && !s.token.equals(keepToken));
    }

    List<Session> active() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> now - s.lastSeen > SESSION_IDLE_MS);
        List<Session> list = new ArrayList<>(sessions.values());
        list.sort(Comparator.comparingLong((Session s) -> s.lastSeen).reversed());
        return list;
    }

    // ---------------------------------------------------------------- throttling

    boolean throttled(String ip, String username) {
        return recent("ip:" + ip) >= MAX_FAILURES || recent("user:" + username) >= MAX_FAILURES;
    }

    void recordFailure(String ip, String username) {
        long now = System.currentTimeMillis();
        for (String key : List.of("ip:" + ip, "user:" + username)) {
            Deque<Long> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (q) {
                q.addLast(now);
            }
        }
    }

    void clearFailures(String ip, String username) {
        failures.remove("ip:" + ip);
        failures.remove("user:" + username);
    }

    private int recent(String key) {
        Deque<Long> q = failures.get(key);
        if (q == null) return 0;
        synchronized (q) {
            long cutoff = System.currentTimeMillis() - FAILURE_WINDOW_MS;
            while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
            return q.size();
        }
    }

    // ---------------------------------------------------------------- util

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}

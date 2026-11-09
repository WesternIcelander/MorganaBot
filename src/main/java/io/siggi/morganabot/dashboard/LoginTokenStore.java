package io.siggi.morganabot.dashboard;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Single-use dashboard login tokens, kept in memory only.
 * <p>
 * Each token belongs to one user, expires after its time to live, and remembers a {@link Listener} (for
 * {@code /dashboard}, the interaction hook that edits the reply). A token is used up atomically with
 * {@link ConcurrentHashMap#remove}, so two requests (two tabs, a double click) can never both redeem it, and the
 * redeem/expire callbacks fire exactly once per token.
 */
public class LoginTokenStore {
    /** How long a {@code /dashboard} link works. */
    public static final long COMMAND_TTL = 30_000L;
    /** How long a console recovery link works. */
    public static final long RECOVERY_TTL = 15L * 60L * 1000L;

    /** Told what happened to a token. Called at most once, on whichever thread redeemed or expired it. */
    public interface Listener {
        void redeemed();

        void expired();

        Listener NONE = new Listener() {
            @Override
            public void redeemed() {
            }

            @Override
            public void expired() {
            }
        };
    }

    /**
     * What a redeemed token logs in as.
     *
     * @param guildId the server {@code /dashboard} was run in, or 0 (DM, recovery link)
     */
    public record Login(long userId, boolean superAdmin, long guildId) {
    }

    private record Entry(Login login, long expiresAt, Listener listener) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private ScheduledExecutorService sweeper;

    public LoginTokenStore() {
        this(System::currentTimeMillis);
    }

    public LoginTokenStore(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Starts a background sweep (once a second) that expires unused tokens, so their replies are edited on time.
     */
    public synchronized void start() {
        if (sweeper != null) return;
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Login token expiry");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(() -> {
            try {
                sweep();
            } catch (Throwable ignored) {
                // A listener failing must not stop the sweep.
            }
        }, 1L, 1L, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (sweeper != null) sweeper.shutdownNow();
        sweeper = null;
    }

    /**
     * Creates a new token.
     *
     * @param ttl how long it works, in milliseconds
     * @return the token, safe to put in a URL
     */
    public String issue(Login login, long ttl, Listener listener) {
        if (login == null) throw new IllegalArgumentException("login is required");
        Entry entry = new Entry(login, clock.getAsLong() + ttl, listener == null ? Listener.NONE : listener);
        while (true) {
            String token = newToken();
            if (tokens.putIfAbsent(token, entry) == null) return token;
        }
    }

    /**
     * Uses up a token.
     *
     * @return what it logs in as, or null if it's unknown, already used or expired
     */
    public Login redeem(String token) {
        if (token == null || token.isEmpty()) return null;
        Entry entry = tokens.remove(token);
        if (entry == null) return null;
        if (clock.getAsLong() >= entry.expiresAt) {
            notifyExpired(entry);
            return null;
        }
        try {
            entry.listener.redeemed();
        } catch (RuntimeException ignored) {
            // Editing the Discord reply is best-effort; the login itself still works.
        }
        return entry.login;
    }

    /**
     * Removes every expired token and tells its listener.
     */
    public void sweep() {
        long now = clock.getAsLong();
        for (Map.Entry<String, Entry> e : tokens.entrySet()) {
            Entry entry = e.getValue();
            if (now >= entry.expiresAt && tokens.remove(e.getKey(), entry)) {
                notifyExpired(entry);
            }
        }
    }

    /** Number of tokens not yet used or swept. */
    public int size() {
        return tokens.size();
    }

    private static void notifyExpired(Entry entry) {
        try {
            entry.listener.expired();
        } catch (RuntimeException ignored) {
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

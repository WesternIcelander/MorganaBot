package io.siggi.morganabot.twitch;

import io.siggi.morganabot.config.AuthCache;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Owns the Twitch app access token in {@link AuthCache}. All access to the cache goes through here, under one lock.
 * <ul>
 *     <li>Any thread may refresh; others wait on the lock instead of failing.</li>
 *     <li>The old token stays in place until a new one has been fetched.</li>
 *     <li>A token is refreshed early once it's within {@link #REFRESH_MARGIN} of expiry. If that refresh fails, the
 *     old token keeps being used until it actually expires, retrying at most every {@link #RETRY_INTERVAL}.</li>
 *     <li>{@link #invalidate} marks a token Twitch rejected (HTTP 401), so the next call fetches a new one.</li>
 * </ul>
 */
public class TwitchTokenManager {
    public static final long REFRESH_MARGIN = 24L * 60L * 60L * 1000L;
    public static final long RETRY_INTERVAL = 5L * 60L * 1000L;

    public record Token(String value, long expiresInMillis) {
    }

    public interface Fetcher {
        Token fetch() throws IOException;
    }

    public interface Listener {
        void refreshed(long expiry);

        /**
         * @param keptOld whether the old token is still being used
         */
        void refreshFailed(IOException error, boolean keptOld, long oldExpiry);
    }

    private final AuthCache cache;
    private final Fetcher fetcher;
    private final Listener listener;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock();
    private String rejected;
    private long nextAttempt;

    public TwitchTokenManager(AuthCache cache, Fetcher fetcher, Listener listener, LongSupplier clock) {
        this.cache = cache;
        this.fetcher = fetcher;
        this.listener = listener;
        this.clock = clock;
    }

    public String getToken() throws IOException {
        lock.lock();
        try {
            long now = clock.getAsLong();
            String token = cache.twitchAppAccessToken;
            long expiry = cache.twitchAppAccessTokenExpiry;
            boolean usable = token != null && !token.equals(rejected) && (expiry == 0L || expiry > now);
            boolean fresh = usable && (expiry == 0L || expiry - now > REFRESH_MARGIN);
            if (fresh) return token;
            if (usable && now < nextAttempt) return token;
            Token fetched;
            try {
                fetched = fetcher.fetch();
            } catch (IOException e) {
                nextAttempt = now + RETRY_INTERVAL;
                if (listener != null) listener.refreshFailed(e, usable, expiry);
                if (usable) return token;
                throw e;
            }
            cache.twitchAppAccessToken = fetched.value();
            cache.twitchAppAccessTokenExpiry = now + fetched.expiresInMillis();
            rejected = null;
            nextAttempt = 0L;
            try {
                if (cache.file != null) cache.save();
            } catch (RuntimeException e) {
                // Already reported as a storage failure; the new token still works from memory.
            }
            if (listener != null) listener.refreshed(cache.twitchAppAccessTokenExpiry);
            return fetched.value();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks {@code token} as rejected by Twitch, if it's still the current one, so the next {@link #getToken()}
     * fetches a new one straight away.
     */
    public void invalidate(String token) {
        lock.lock();
        try {
            if (token != null && token.equals(cache.twitchAppAccessToken)) {
                rejected = token;
                nextAttempt = 0L;
            }
        } finally {
            lock.unlock();
        }
    }

    public long getExpiry() {
        lock.lock();
        try {
            return cache.twitchAppAccessTokenExpiry;
        } finally {
            lock.unlock();
        }
    }
}

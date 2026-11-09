package io.siggi.morganabot.twitch;

import com.google.gson.Gson;
import io.siggi.morganabot.config.AuthCache;
import io.siggi.morganabot.config.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TwitchTokenManagerTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    @TempDir
    Path tempDir;

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final AtomicInteger fetches = new AtomicInteger();
    private final List<String> events = new ArrayList<>();
    private volatile IOException failWith;

    private AuthCache cache() {
        return Data.read(tempDir.resolve("auth-cache.json").toFile(), AuthCache.class, new Gson());
    }

    private TwitchTokenManager manager(AuthCache cache) {
        return new TwitchTokenManager(cache, () -> {
            int n = fetches.incrementAndGet();
            if (failWith != null) throw failWith;
            return new TwitchTokenManager.Token("token" + n, 60L * DAY);
        }, new TwitchTokenManager.Listener() {
            @Override
            public void refreshed(long expiry) {
                events.add("refreshed");
            }

            @Override
            public void refreshFailed(IOException error, boolean keptOld, long oldExpiry) {
                events.add("failed keptOld=" + keptOld);
            }
        }, now::get);
    }

    @Test
    void fetchesWhenMissingAndSaves() throws IOException {
        AuthCache cache = cache();
        TwitchTokenManager tokens = manager(cache);
        assertEquals("token1", tokens.getToken());
        assertEquals("token1", tokens.getToken());
        assertEquals(1, fetches.get());
        assertEquals(List.of("refreshed"), events);
        AuthCache saved = cache();
        assertEquals("token1", saved.twitchAppAccessToken);
        assertEquals(now.get() + 60L * DAY, saved.twitchAppAccessTokenExpiry);
        assertEquals(saved.twitchAppAccessTokenExpiry, tokens.getExpiry());
    }

    @Test
    void usesCachedTokenUntilNearExpiry() throws IOException {
        AuthCache cache = cache();
        cache.twitchAppAccessToken = "old";
        cache.twitchAppAccessTokenExpiry = now.get() + 3 * DAY;
        TwitchTokenManager tokens = manager(cache);
        assertEquals("old", tokens.getToken());
        assertEquals(0, fetches.get());

        now.addAndGet(2 * DAY + 1);
        assertEquals("token1", tokens.getToken(), "refreshed early, within a day of expiry");
    }

    @Test
    void failedEarlyRefreshKeepsOldTokenAndThrottlesRetries() throws IOException {
        AuthCache cache = cache();
        cache.twitchAppAccessToken = "old";
        cache.twitchAppAccessTokenExpiry = now.get() + DAY / 2;
        TwitchTokenManager tokens = manager(cache);
        failWith = new IOException("network down");
        assertEquals("old", tokens.getToken());
        assertEquals("old", tokens.getToken());
        assertEquals(1, fetches.get(), "no retry within the retry interval");
        assertEquals(List.of("failed keptOld=true"), events);

        now.addAndGet(TwitchTokenManager.RETRY_INTERVAL);
        failWith = null;
        assertEquals("token2", tokens.getToken());
    }

    @Test
    void expiredTokenIsNotUsed() {
        AuthCache cache = cache();
        cache.twitchAppAccessToken = "old";
        cache.twitchAppAccessTokenExpiry = now.get() - 1;
        TwitchTokenManager tokens = manager(cache);
        failWith = new IOException("network down");
        assertThrows(IOException.class, tokens::getToken);
        assertEquals(List.of("failed keptOld=false"), events);
        // Without a usable token there's no throttle: the next call tries again.
        assertThrows(IOException.class, tokens::getToken);
        assertEquals(2, fetches.get());
    }

    @Test
    void invalidateForcesRefresh() throws IOException {
        AuthCache cache = cache();
        TwitchTokenManager tokens = manager(cache);
        String first = tokens.getToken();
        tokens.invalidate("someone-elses-old-token");
        assertEquals(first, tokens.getToken());
        tokens.invalidate(first);
        assertEquals("token2", tokens.getToken());
        // A late invalidate of the replaced token does nothing.
        tokens.invalidate(first);
        assertEquals("token2", tokens.getToken());
        assertEquals(2, fetches.get());
    }

    @Test
    void rejectedTokenIsKeptUntilReplacementArrives() {
        AuthCache cache = cache();
        cache.twitchAppAccessToken = "old";
        cache.twitchAppAccessTokenExpiry = now.get() + 30 * DAY;
        TwitchTokenManager tokens = manager(cache);
        tokens.invalidate("old");
        failWith = new IOException("down");
        assertThrows(IOException.class, tokens::getToken, "a rejected token isn't handed out");
        assertEquals("old", cache.twitchAppAccessToken, "but it isn't cleared either");
    }

    @Test
    void concurrentCallersShareOneRefresh() throws Exception {
        AuthCache cache = cache();
        CountDownLatch inFetch = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TwitchTokenManager tokens = new TwitchTokenManager(cache, () -> {
            fetches.incrementAndGet();
            inFetch.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            return new TwitchTokenManager.Token("shared", 60L * DAY);
        }, null, now::get);
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            threads.add(new Thread(() -> {
                try {
                    results.add(tokens.getToken());
                } catch (IOException e) {
                    results.add("error");
                }
            }));
        }
        threads.forEach(Thread::start);
        inFetch.await();
        Thread.sleep(50);
        release.countDown();
        for (Thread t : threads) t.join();
        assertEquals(1, fetches.get());
        assertEquals(List.of("shared", "shared", "shared", "shared", "shared"), results);
    }
}

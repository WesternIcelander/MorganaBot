package io.siggi.morganabot.dashboard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LoginTokenStoreTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final LoginTokenStore store = new LoginTokenStore(now::get);

    private static final class Recorder implements LoginTokenStore.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public synchronized void redeemed() {
            events.add("redeemed");
        }

        @Override
        public synchronized void expired() {
            events.add("expired");
        }
    }

    @Test
    void redeemOnce() {
        Recorder recorder = new Recorder();
        String token = store.issue(new LoginTokenStore.Login(42L, false, 7L), LoginTokenStore.COMMAND_TTL, recorder);
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
        LoginTokenStore.Login login = store.redeem(token);
        assertEquals(new LoginTokenStore.Login(42L, false, 7L), login);
        assertNull(store.redeem(token), "single use");
        assertEquals(List.of("redeemed"), recorder.events);
        store.sweep();
        assertEquals(List.of("redeemed"), recorder.events);
        assertEquals(0, store.size());
    }

    @Test
    void tokensAreUnique() {
        String a = store.issue(new LoginTokenStore.Login(1L, false, 0L), 1000L, null);
        String b = store.issue(new LoginTokenStore.Login(1L, false, 0L), 1000L, null);
        assertNotEquals(a, b);
    }

    @Test
    void unknownAndEmptyTokens() {
        assertNull(store.redeem(null));
        assertNull(store.redeem(""));
        assertNull(store.redeem("nope"));
    }

    @Test
    void validForThirtySeconds() {
        Recorder recorder = new Recorder();
        String token = store.issue(new LoginTokenStore.Login(1L, false, 0L), LoginTokenStore.COMMAND_TTL, recorder);
        now.addAndGet(29_999L);
        store.sweep();
        assertTrue(recorder.events.isEmpty());
        assertNotNull(store.redeem(token));
    }

    @Test
    void expiresAfterThirtySeconds() {
        Recorder recorder = new Recorder();
        String token = store.issue(new LoginTokenStore.Login(1L, false, 0L), LoginTokenStore.COMMAND_TTL, recorder);
        now.addAndGet(30_000L);
        store.sweep();
        assertEquals(List.of("expired"), recorder.events);
        assertNull(store.redeem(token));
        store.sweep();
        assertEquals(List.of("expired"), recorder.events, "expired is reported once");
    }

    @Test
    void redeemingAfterExpiryBeforeSweepFails() {
        Recorder recorder = new Recorder();
        String token = store.issue(new LoginTokenStore.Login(1L, false, 0L), LoginTokenStore.COMMAND_TTL, recorder);
        now.addAndGet(31_000L);
        assertNull(store.redeem(token));
        assertEquals(List.of("expired"), recorder.events);
        store.sweep();
        assertEquals(List.of("expired"), recorder.events);
    }

    @Test
    void recoveryTokensLastFifteenMinutes() {
        String token = store.issue(new LoginTokenStore.Login(1L, true, 0L), LoginTokenStore.RECOVERY_TTL, null);
        now.addAndGet(14L * 60_000L);
        store.sweep();
        assertEquals(new LoginTokenStore.Login(1L, true, 0L), store.redeem(token));
    }

    @Test
    void listenerFailureDoesNotBreakLogin() {
        String token = store.issue(new LoginTokenStore.Login(1L, false, 0L), 1000L, new LoginTokenStore.Listener() {
            @Override
            public void redeemed() {
                throw new IllegalStateException("boom");
            }

            @Override
            public void expired() {
                throw new IllegalStateException("boom");
            }
        });
        assertNotNull(store.redeem(token));
    }

    @Test
    void concurrentRedeemOnlyOneWins() throws Exception {
        for (int round = 0; round < 50; round++) {
            AtomicInteger redeemedCallbacks = new AtomicInteger();
            String token = store.issue(new LoginTokenStore.Login(1L, false, 0L), 30_000L, new LoginTokenStore.Listener() {
                @Override
                public void redeemed() {
                    redeemedCallbacks.incrementAndGet();
                }

                @Override
                public void expired() {
                    fail("not expired");
                }
            });
            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            List<Thread> list = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (store.redeem(token) != null) wins.incrementAndGet();
                });
                t.start();
                list.add(t);
            }
            start.countDown();
            for (Thread t : list) t.join();
            assertEquals(1, wins.get());
            assertEquals(1, redeemedCallbacks.get());
        }
    }
}

package io.siggi.morganabot.twitch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StreamerQueueTest {

    @Test
    void sameStreamerRunsOneAtATime() throws Exception {
        StreamerQueue queue = new StreamerQueue();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            threads.add(new Thread(() -> {
                try {
                    queue.run("s1", 1000L, () -> {
                        int n = running.incrementAndGet();
                        maxRunning.accumulateAndGet(n, Math::max);
                        Thread.sleep(5);
                        running.decrementAndGet();
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();
        assertEquals(1, maxRunning.get());
    }

    @Test
    void differentStreamersDontBlockEachOther() throws Exception {
        StreamerQueue queue = new StreamerQueue();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                queue.run("s1", 1000L, () -> {
                    inside.countDown();
                    release.await();
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        holder.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        CompletableFuture<Boolean> other = CompletableFuture.supplyAsync(() -> {
            try {
                return queue.run("s2", 1000L, () -> {
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(other.get(5, TimeUnit.SECONDS));
        release.countDown();
        holder.join();
    }

    @Test
    void laterNotificationWaitsForInFlightAction() throws Exception {
        StreamerQueue queue = new StreamerQueue();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        // "Online": starts a send that finishes later, on another thread (like a JDA callback).
        CompletableFuture<Void>[] send = new CompletableFuture[1];
        queue.run("s1", 1000L, () -> {
            send[0] = queue.track("s1");
            order.add("online handled");
        });
        assertEquals(1, queue.inFlightCount("s1"));
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            order.add("send saved");
            send[0].complete(null);
        }).start();
        // "Offline": must see the saved send.
        boolean waited = queue.run("s1", 5000L, () -> order.add("offline handled"));
        assertTrue(waited);
        assertEquals(List.of("online handled", "send saved", "offline handled"), order);
        assertEquals(0, queue.inFlightCount("s1"));
    }

    @Test
    void failedActionAlsoReleasesWaiters() throws Exception {
        StreamerQueue queue = new StreamerQueue();
        CompletableFuture<Void> action = queue.track("s1");
        action.completeExceptionally(new RuntimeException("send failed"));
        assertTrue(queue.run("s1", 1000L, () -> {
        }));
    }

    @Test
    void waitTimesOutButStillRuns() throws Exception {
        StreamerQueue queue = new StreamerQueue();
        queue.track("s1"); // never completes
        AtomicInteger ran = new AtomicInteger();
        assertFalse(queue.run("s1", 50L, ran::incrementAndGet));
        assertEquals(1, ran.get());
    }

    @Test
    void lockIsReleasedWhenTaskThrows() throws Exception {
        StreamerQueue queue = new StreamerQueue();
        assertThrows(IllegalStateException.class, () -> queue.run("s1", 100L, () -> {
            throw new IllegalStateException("x");
        }));
        CompletableFuture<Boolean> next = CompletableFuture.supplyAsync(() -> {
            try {
                return queue.run("s1", 100L, () -> {
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(next.get(5, TimeUnit.SECONDS));
    }
}

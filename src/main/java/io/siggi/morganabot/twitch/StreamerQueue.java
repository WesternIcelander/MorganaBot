package io.siggi.morganabot.twitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs each streamer's notifications one at a time, and lets a later notification wait for the Discord actions
 * (sends, deletes) an earlier one started.
 * <p>
 * Without this, an offline arriving right after an online would find no stored message ID (the send hadn't been
 * confirmed yet) and leave the live post up.
 */
public class StreamerQueue {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<CompletableFuture<?>>> inFlight = new ConcurrentHashMap<>();

    public interface Task {
        void run() throws Exception;
    }

    /**
     * Runs {@code task} under the streamer's lock, after waiting (up to {@code waitMillis}) for that streamer's
     * in-flight actions to finish.
     *
     * @return false if the wait timed out (the task still ran)
     */
    public boolean run(String streamerId, long waitMillis, Task task) throws Exception {
        ReentrantLock lock = locks.computeIfAbsent(streamerId, id -> new ReentrantLock());
        lock.lock();
        try {
            boolean finished = awaitInFlight(streamerId, waitMillis);
            task.run();
            return finished;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Registers a pending action for the streamer. Complete the returned future when the action (including any
     * save that follows it) is done, whether it worked or not.
     */
    public CompletableFuture<Void> track(String streamerId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Set<CompletableFuture<?>> set = inFlight.computeIfAbsent(streamerId, id -> ConcurrentHashMap.newKeySet());
        set.add(future);
        future.whenComplete((r, e) -> set.remove(future));
        return future;
    }

    public int inFlightCount(String streamerId) {
        Set<CompletableFuture<?>> set = inFlight.get(streamerId);
        return set == null ? 0 : set.size();
    }

    private boolean awaitInFlight(String streamerId, long waitMillis) throws InterruptedException {
        Set<CompletableFuture<?>> set = inFlight.get(streamerId);
        if (set == null || set.isEmpty()) return true;
        List<CompletableFuture<?>> pending = new ArrayList<>(set);
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).get(waitMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException e) {
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }
}

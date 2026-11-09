package io.siggi.morganabot.problems;

import com.google.gson.Gson;
import io.siggi.morganabot.config.ServerInfoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ServerProblemsTest {

    private static final long GUILD = 100L;

    @TempDir
    Path tempDir;

    private ServerInfoStore store;

    @BeforeEach
    void setUp() {
        store = new ServerInfoStore(tempDir.toFile(), new Gson());
    }

    private ServerProblems problems(int max) {
        return new ServerProblems(tempDir.toFile(), new Gson(), store, max);
    }

    private static Problem sendFailure(long channelId, String reason) {
        return new Problem(Problem.SEND_LIVE_MESSAGE, Problem.Target.channel(channelId, "live"),
                Problem.Target.streamer("tw1", "streamer1"), reason);
    }

    @Test
    void missingFileMeansNoProblems() {
        ServerProblems problems = problems(ServerProblems.MAX_ENTRIES);
        assertTrue(problems.list(GUILD).isEmpty());
        assertEquals(new File(tempDir.toFile(), GUILD + "-problems.json"), problems.getFile(GUILD));
        assertFalse(problems.getFile(GUILD).exists());
        assertEquals(0, problems.dismissAll(GUILD));
        assertFalse(problems.dismiss(GUILD, "nope"));
    }

    @Test
    void recordAndReadBack() {
        ServerProblems problems = problems(ServerProblems.MAX_ENTRIES);
        Problem stored = problems.record(GUILD, sendFailure(5L, "Missing permission Send Messages in #live"), 1000L);
        assertNotNull(stored);
        assertNotNull(stored.id);
        assertTrue(problems.getFile(GUILD).exists());

        // A fresh instance reads it from disk.
        List<Problem> list = problems(ServerProblems.MAX_ENTRIES).list(GUILD);
        assertEquals(1, list.size());
        Problem p = list.get(0);
        assertEquals(stored.id, p.id);
        assertEquals(1000L, p.time);
        assertEquals(1000L, p.lastSeen);
        assertEquals(1, p.count);
        assertEquals(Problem.SEND_LIVE_MESSAGE, p.action);
        assertEquals(Problem.Target.CHANNEL, p.target.kind);
        assertEquals("5", p.target.id);
        assertEquals("live", p.target.name);
        assertEquals(Problem.Target.STREAMER, p.streamer.kind);
        assertEquals("tw1", p.streamer.id);
        assertEquals("Missing permission Send Messages in #live", p.reason);
    }

    @Test
    void identicalFailuresAreCombined() {
        ServerProblems problems = problems(ServerProblems.MAX_ENTRIES);
        Problem first = problems.record(GUILD, sendFailure(5L, "Unknown channel (deleted?)"), 1000L);
        for (int i = 1; i < 20; i++) {
            Problem again = sendFailure(5L, "Unknown channel (deleted?)");
            again.target.name = "renamed";
            problems.record(GUILD, again, 1000L + i);
        }
        List<Problem> list = problems.list(GUILD);
        assertEquals(1, list.size());
        Problem p = list.get(0);
        assertEquals(first.id, p.id);
        assertEquals(20, p.count);
        assertEquals(1000L, p.time);
        assertEquals(1019L, p.lastSeen);
        assertEquals("renamed", p.target.name, "names are refreshed, matching is by ID");
    }

    @Test
    void differentFailuresAreSeparate() {
        ServerProblems problems = problems(ServerProblems.MAX_ENTRIES);
        problems.record(GUILD, sendFailure(5L, "A"), 1L);
        problems.record(GUILD, sendFailure(5L, "B"), 2L);
        problems.record(GUILD, sendFailure(6L, "A"), 3L);
        problems.record(GUILD, new Problem(Problem.DELETE_LIVE_MESSAGE, Problem.Target.channel(5L, "live"),
                Problem.Target.streamer("tw1", "streamer1"), "A"), 4L);
        problems.record(GUILD, new Problem(Problem.SEND_LIVE_MESSAGE, Problem.Target.channel(5L, "live"),
                Problem.Target.streamer("tw2", "streamer2"), "A"), 5L);
        problems.record(GUILD, new Problem(Problem.SEND_LIVE_MESSAGE, null,
                Problem.Target.streamer("tw1", "streamer1"), "Not posted: No live channel is set"), 6L);
        problems.record(GUILD, new Problem(Problem.SEND_LIVE_MESSAGE, null,
                Problem.Target.streamer("tw1", "streamer1"), "Not posted: No live channel is set"), 7L);
        List<Problem> list = problems.list(GUILD);
        assertEquals(6, list.size());
        assertEquals(2, list.get(0).count);
        assertNull(list.get(0).target);
    }

    @Test
    void listIsMostRecentlySeenFirst() {
        ServerProblems problems = problems(ServerProblems.MAX_ENTRIES);
        problems.record(GUILD, sendFailure(1L, "x"), 10L);
        problems.record(GUILD, sendFailure(2L, "x"), 20L);
        problems.record(GUILD, sendFailure(1L, "x"), 30L); // seen again, now the newest
        List<Problem> list = problems.list(GUILD);
        assertEquals("1", list.get(0).target.id);
        assertEquals("2", list.get(1).target.id);
    }

    @Test
    void keepsOnlyNewestEntries() {
        ServerProblems problems = problems(5);
        for (int i = 0; i < 12; i++) {
            problems.record(GUILD, sendFailure(i, "x"), 100L + i);
        }
        List<Problem> list = problems.list(GUILD);
        assertEquals(5, list.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(Integer.toString(11 - i), list.get(i).target.id);
        }
        assertEquals(100, ServerProblems.MAX_ENTRIES);
    }

    @Test
    void dismissOne() {
        ServerProblems problems = problems(ServerProblems.MAX_ENTRIES);
        Problem a = problems.record(GUILD, sendFailure(1L, "x"), 1L);
        Problem b = problems.record(GUILD, sendFailure(2L, "x"), 2L);
        assertTrue(problems.dismiss(GUILD, a.id));
        assertFalse(problems.dismiss(GUILD, a.id));
        List<Problem> list = problems.list(GUILD);
        assertEquals(1, list.size());
        assertEquals(b.id, list.get(0).id);

        // The same failure after dismissal starts a new entry.
        Problem again = problems.record(GUILD, sendFailure(1L, "x"), 3L);
        assertNotEquals(a.id, again.id);
        assertEquals(1, again.count);
    }

    @Test
    void dismissAll() {
        ServerProblems problems = problems(ServerProblems.MAX_ENTRIES);
        problems.record(GUILD, sendFailure(1L, "x"), 1L);
        problems.record(GUILD, sendFailure(2L, "x"), 2L);
        assertEquals(2, problems.dismissAll(GUILD));
        assertTrue(problems.list(GUILD).isEmpty());
        assertFalse(problems.getFile(GUILD).exists());
    }

    @Test
    void guildsAreSeparate() {
        ServerProblems problems = problems(ServerProblems.MAX_ENTRIES);
        problems.record(GUILD, sendFailure(1L, "x"), 1L);
        problems.record(GUILD + 1, sendFailure(1L, "x"), 1L);
        problems.dismissAll(GUILD);
        assertTrue(problems.list(GUILD).isEmpty());
        assertEquals(1, problems.list(GUILD + 1).size());
    }

    @Test
    void unreadableFileIsReplacedNotFatal() throws Exception {
        ServerProblems problems = problems(ServerProblems.MAX_ENTRIES);
        Files.writeString(problems.getFile(GUILD).toPath(), "{ not json", StandardCharsets.UTF_8);
        assertTrue(problems.list(GUILD).isEmpty());
        assertNotNull(problems.record(GUILD, sendFailure(1L, "x"), 1L));
        assertEquals(1, problems.list(GUILD).size());
    }

    @Test
    void writesWaitForTheServerLock() throws Exception {
        ServerProblems problems = problems(ServerProblems.MAX_ENTRIES);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean written = new AtomicBoolean();
        Thread holder = new Thread(() -> store.withLock(GUILD, () -> {
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
            return null;
        }));
        holder.start();
        locked.await();
        Thread writer = new Thread(() -> {
            problems.record(GUILD, sendFailure(1L, "x"), 1L);
            written.set(true);
        });
        writer.start();
        writer.join(200L);
        assertFalse(written.get(), "record must wait for the guild lock");
        release.countDown();
        writer.join(5000L);
        holder.join(5000L);
        assertTrue(written.get());
    }

    @Test
    void concurrentRecordsAreAllCounted() throws Exception {
        ServerProblems problems = problems(ServerProblems.MAX_ENTRIES);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 25; i++) problems.record(GUILD, sendFailure(1L, "x"));
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();
        List<Problem> list = problems.list(GUILD);
        assertEquals(1, list.size());
        assertEquals(200, list.get(0).count);
    }
}

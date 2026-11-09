package io.siggi.morganabot.health;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SystemLogTest {

    @TempDir
    Path tempDir;

    private File file() {
        return tempDir.resolve("system-log.jsonl").toFile();
    }

    private SystemLog log(int max) {
        return new SystemLog(file(), new Gson(), max);
    }

    private static int lines(File file) throws IOException {
        return (int) Files.readAllLines(file.toPath()).stream().filter(l -> !l.isBlank()).count();
    }

    @Test
    void missingFileMeansEmptyLog() {
        SystemLog log = log(SystemLog.MAX_ENTRIES);
        assertTrue(log.list().isEmpty());
        assertFalse(file().exists());
        assertFalse(log.dismiss("nope"));
    }

    @Test
    void repeatsAreCombined() {
        SystemLog log = log(SystemLog.MAX_ENTRIES);
        SystemLogEntry first = log.record(Component.TWITCH_API, Severity.WARNING, "Twitch down", "trace 1", 1000L);
        SystemLogEntry second = log.record(Component.TWITCH_API, Severity.WARNING, "Twitch down", "trace 2", 2000L);
        assertEquals(first.id, second.id);
        assertEquals(2, second.count);
        assertEquals(1000L, second.time);
        assertEquals(2000L, second.lastSeen);
        assertEquals("trace 2", second.detail);

        // Different component, severity or message: separate entries.
        log.record(Component.EVENTSUB, Severity.WARNING, "Twitch down", null, 3000L);
        log.record(Component.TWITCH_API, Severity.ERROR, "Twitch down", null, 4000L);
        log.record(Component.TWITCH_API, Severity.WARNING, "Twitch up", null, 5000L);
        assertEquals(4, log.list().size());
    }

    @Test
    void listIsNewestFirstAndCopies() {
        SystemLog log = log(SystemLog.MAX_ENTRIES);
        log.record(Component.BOT, Severity.ERROR, "a", null, 1000L);
        log.record(Component.BOT, Severity.ERROR, "b", null, 2000L);
        log.record(Component.BOT, Severity.ERROR, "a", null, 3000L);
        List<SystemLogEntry> list = log.list();
        assertEquals("a", list.get(0).message);
        assertEquals("b", list.get(1).message);
        list.get(0).message = "changed";
        assertEquals("a", log.list().get(0).message);
    }

    @Test
    void survivesRestart() {
        SystemLog log = log(SystemLog.MAX_ENTRIES);
        log.record(Component.STORAGE, Severity.ERROR, "disk full", "trace", 1000L);
        log.record(Component.STORAGE, Severity.ERROR, "disk full", null, 2000L);
        SystemLogEntry other = log.record(Component.DISCORD, Severity.WARNING, "reconnecting", null, 1500L);

        SystemLog reloaded = log(SystemLog.MAX_ENTRIES);
        List<SystemLogEntry> list = reloaded.list();
        assertEquals(2, list.size());
        SystemLogEntry disk = list.get(0);
        assertEquals("disk full", disk.message);
        assertEquals(2, disk.count);
        assertEquals("trace", disk.detail);
        assertEquals(other.id, list.get(1).id);

        // Combining continues with the reloaded entry.
        SystemLogEntry again = reloaded.record(Component.STORAGE, Severity.ERROR, "disk full", null, 3000L);
        assertEquals(disk.id, again.id);
        assertEquals(3, again.count);
    }

    @Test
    void dismissSurvivesRestart() {
        SystemLog log = log(SystemLog.MAX_ENTRIES);
        SystemLogEntry a = log.record(Component.BOT, Severity.ERROR, "a", null, 1000L);
        log.record(Component.BOT, Severity.ERROR, "b", null, 2000L);
        assertTrue(log.dismiss(a.id));
        assertFalse(log.dismiss(a.id));
        assertEquals(1, log.list().size());

        SystemLog reloaded = log(SystemLog.MAX_ENTRIES);
        assertEquals(1, reloaded.list().size());
        assertEquals("b", reloaded.list().get(0).message);

        // The same message again starts a new entry.
        SystemLogEntry newA = reloaded.record(Component.BOT, Severity.ERROR, "a", null, 3000L);
        assertNotEquals(a.id, newA.id);
        assertEquals(1, newA.count);
    }

    @Test
    void dismissAll() {
        SystemLog log = log(SystemLog.MAX_ENTRIES);
        log.record(Component.BOT, Severity.ERROR, "a", null, 1000L);
        log.record(Component.BOT, Severity.ERROR, "b", null, 2000L);
        assertEquals(2, log.dismissAll());
        assertTrue(log.list().isEmpty());
        assertTrue(log(SystemLog.MAX_ENTRIES).list().isEmpty());
    }

    @Test
    void keepsNewestEntriesAndCompactsFile() throws IOException {
        SystemLog log = log(5);
        for (int i = 0; i < 50; i++) {
            log.record(Component.BOT, Severity.ERROR, "error " + i, null, 1000L + i);
            assertTrue(lines(file()) <= 10, "file grew past twice the limit");
        }
        List<SystemLogEntry> list = log.list();
        assertEquals(5, list.size());
        assertEquals("error 49", list.get(0).message);
        assertEquals("error 45", list.get(4).message);

        List<String> reloaded = new ArrayList<>();
        for (SystemLogEntry e : log(5).list()) reloaded.add(e.message);
        assertEquals(List.of("error 49", "error 48", "error 47", "error 46", "error 45"), reloaded);
    }

    @Test
    void repeatsDontGrowFileForever() throws IOException {
        SystemLog log = log(5);
        for (int i = 0; i < 100; i++) {
            log.record(Component.TWITCH_API, Severity.WARNING, "outage", null, 1000L + i);
        }
        assertTrue(lines(file()) <= 10);
        List<SystemLogEntry> list = log(5).list();
        assertEquals(1, list.size());
        assertEquals(100, list.get(0).count);
    }

    @Test
    void skipsBrokenLines() throws IOException {
        SystemLog log = log(SystemLog.MAX_ENTRIES);
        log.record(Component.BOT, Severity.ERROR, "a", null, 1000L);
        Files.writeString(file().toPath(), "{not json\n\n{\"id\":null}\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        // A cut-off last line (no newline).
        Files.writeString(file().toPath(), "{\"id\":\"x\",\"comp", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        SystemLog reloaded = log(SystemLog.MAX_ENTRIES);
        assertEquals(1, reloaded.list().size());
        reloaded.record(Component.BOT, Severity.ERROR, "b", null, 2000L);
        assertEquals(2, log(SystemLog.MAX_ENTRIES).list().size());
    }

    @Test
    void truncatesLongText() {
        SystemLog log = log(SystemLog.MAX_ENTRIES);
        SystemLogEntry entry = log.record(Component.BOT, Severity.ERROR, "m".repeat(5000), "d".repeat(50000), 1000L);
        assertTrue(entry.message.length() <= SystemLog.MAX_MESSAGE + 1);
        assertTrue(entry.detail.length() <= SystemLog.MAX_DETAIL + 1);
    }

    @Test
    void writeFailureIsReportedNotThrown() throws IOException {
        // The log's path is a directory, so writes fail.
        File dir = tempDir.resolve("dir.jsonl").toFile();
        assertTrue(dir.mkdir());
        SystemLog log = new SystemLog(dir, new Gson(), 5);
        AtomicReference<IOException> failure = new AtomicReference<>();
        log.setWriteFailureListener(failure::set);
        SystemLogEntry entry = log.record(Component.BOT, Severity.ERROR, "a", null, 1000L);
        assertNotNull(entry);
        assertNotNull(failure.get());
        assertEquals(1, log.list().size());
    }

    @Test
    void concurrentRecordsAreAllCounted() throws Exception {
        SystemLog log = log(SystemLog.MAX_ENTRIES);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int n = t;
            threads.add(new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    log.record(Component.BOT, Severity.ERROR, "shared", null, 1000L + i);
                    log.record(Component.BOT, Severity.ERROR, "thread " + n, null, 1000L + i);
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();
        List<SystemLogEntry> list = log(SystemLog.MAX_ENTRIES).list();
        assertEquals(9, list.size());
        SystemLogEntry shared = list.stream().filter(e -> e.message.equals("shared")).findFirst().orElseThrow();
        assertEquals(400, shared.count);
    }
}

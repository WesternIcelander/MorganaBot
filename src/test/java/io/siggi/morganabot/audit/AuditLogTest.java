package io.siggi.morganabot.audit;

import com.google.gson.Gson;
import io.siggi.morganabot.config.ServerInfoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {

    private static final long GUILD = 100L;
    private static final Actor ACTOR = Actor.command(301L, "admin_user", "setlivechannel");

    @TempDir
    Path tempDir;

    private ServerInfoStore store;

    @BeforeEach
    void setUp() {
        store = new ServerInfoStore(tempDir.toFile(), new Gson());
    }

    private AuditLog log(int max) {
        return new AuditLog(tempDir.toFile(), new Gson(), store, AuditLog.Names.NONE, max);
    }

    private static AuditEntry entry(int n) {
        return new AuditEntry(AuditEntry.LIVE_CHANNEL).change("channel", AuditEntry.Value.channel(n), AuditEntry.Value.channel(n + 1));
    }

    @Test
    void missingFileMeansNoEntries() {
        AuditLog log = log(AuditLog.MAX_ENTRIES);
        assertTrue(log.read(GUILD, true).isEmpty());
        assertEquals(new File(tempDir.toFile(), GUILD + "-audit.jsonl"), log.getFile(GUILD));
    }

    @Test
    void roundTripNewestFirst() throws Exception {
        AuditLog log = log(AuditLog.MAX_ENTRIES);
        AuditEntry first = entry(1);
        first.note = "first";
        log.record(GUILD, ACTOR, first);
        log.record(GUILD, Actor.system(), entry(2));

        List<String> lines = Files.readAllLines(log.getFile(GUILD).toPath(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "one JSON object per line");
        assertTrue(lines.get(0).contains("\"old\""));
        assertTrue(lines.get(0).contains("\"new\""));
        assertTrue(lines.get(0).contains("\"source\":\"command\""));
        assertTrue(lines.get(0).contains("\"command\":\"/setlivechannel\""));
        assertTrue(lines.get(0).contains("\"userId\":\"301\""), "snowflakes stored as strings");

        List<AuditEntry> entries = log.read(GUILD, false);
        assertEquals(2, entries.size());
        assertEquals(Actor.system(), entries.get(0).actor);
        assertEquals(ACTOR, entries.get(1).actor);
        assertEquals("first", entries.get(1).note);
        assertEquals("1", entries.get(1).changes.get(0).oldValue.id);
        assertEquals("2", entries.get(1).changes.get(0).newValue.id);
        assertEquals(AuditEntry.Value.CHANNEL, entries.get(1).changes.get(0).newValue.kind);
        assertTrue(entries.get(1).time > 0L);
    }

    @Test
    void trimsToNewestEntries() {
        AuditLog log = log(5);
        for (int i = 0; i < 12; i++) {
            log.record(GUILD, ACTOR, entry(i * 10));
        }
        List<AuditEntry> entries = log.read(GUILD, true);
        assertEquals(5, entries.size());
        assertEquals("110", entries.get(0).changes.get(0).oldValue.id);
        assertEquals("70", entries.get(4).changes.get(0).oldValue.id);
        File[] leftovers = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".sav"));
        assertEquals(0, leftovers.length);
    }

    @Test
    void defaultLimitIs1000() {
        assertEquals(1000, AuditLog.MAX_ENTRIES);
    }

    @Test
    void superAdminOnlyEntriesAreFiltered() {
        AuditLog log = log(AuditLog.MAX_ENTRIES);
        log.record(GUILD, ACTOR, entry(1));
        AuditEntry hidden = entry(2);
        hidden.visibility = AuditEntry.VISIBILITY_SUPER_ADMIN;
        log.record(GUILD, Actor.superAdmin(303L, "owner"), hidden);
        assertEquals(1, log.read(GUILD, false).size());
        assertEquals(2, log.read(GUILD, true).size());
        assertTrue(log.read(GUILD, true).get(0).isSuperAdminOnly());
    }

    @Test
    void brokenLinesAreSkippedAndNotGluedTo() throws Exception {
        AuditLog log = log(AuditLog.MAX_ENTRIES);
        log.record(GUILD, ACTOR, entry(1));
        // A write cut off by a crash, with no trailing newline.
        Files.writeString(log.getFile(GUILD).toPath(), "{\"time\":12,\"type\":\"liv", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        log.record(GUILD, ACTOR, entry(2));
        List<AuditEntry> entries = log.read(GUILD, true);
        assertEquals(2, entries.size());
        assertEquals("2", entries.get(0).changes.get(0).oldValue.id);
        assertEquals("1", entries.get(1).changes.get(0).oldValue.id);
    }

    @Test
    void namesAreFilledInWhenMissing() {
        AuditLog.Names names = new AuditLog.Names() {
            @Override
            public String channel(long guildId, long channelId) {
                return "live";
            }

            @Override
            public String role(long guildId, long roleId) {
                throw new RuntimeException("Discord is down");
            }

            @Override
            public String user(long guildId, long userId) {
                return null;
            }

            @Override
            public String twitchLogin(String twitchId) {
                return "looked_up";
            }
        };
        AuditLog log = new AuditLog(tempDir.toFile(), new Gson(), store, names);
        AuditEntry entry = new AuditEntry(AuditEntry.STREAMER_ADD);
        entry.streamer = AuditEntry.Value.twitch("123", null);
        entry.change("channel", null, AuditEntry.Value.channel(5L));
        entry.change("role", null, AuditEntry.Value.role(6L));
        entry.change("twitch", AuditEntry.Value.twitch("9", "known"), null);
        log.record(GUILD, ACTOR, entry);

        AuditEntry read = log.read(GUILD, true).get(0);
        assertEquals("looked_up", read.streamer.name);
        assertEquals("live", read.changes.get(0).newValue.name);
        assertNull(read.changes.get(1).newValue.name, "a failing lookup leaves the name out");
        assertEquals("6", read.changes.get(1).newValue.id);
        assertEquals("known", read.changes.get(2).oldValue.name, "known names aren't looked up again");
    }

    @Test
    void valueEqualityIgnoresNames() {
        AuditEntry entry = new AuditEntry(AuditEntry.LIVE_ROLE);
        AuditEntry.Value a = AuditEntry.Value.role(5L);
        AuditEntry.Value b = AuditEntry.Value.role(5L);
        b.name = "renamed";
        entry.change("role", a, b);
        entry.change("role", null, null);
        entry.change("template", AuditEntry.Value.text("x"), AuditEntry.Value.text("x"));
        assertFalse(entry.hasChanges());
        assertNull(AuditEntry.Value.channel(0L));
        assertNull(AuditEntry.Value.text(null));
    }

    @Test
    void appendsWaitForTheServerLock() throws Exception {
        AuditLog log = log(AuditLog.MAX_ENTRIES);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean writtenWhileLocked = new AtomicBoolean();
        Thread holder = new Thread(() -> store.withLock(GUILD, () -> {
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
            writtenWhileLocked.set(log.getFile(GUILD).exists());
            return null;
        }));
        holder.start();
        locked.await();
        Thread writer = new Thread(() -> log.record(GUILD, ACTOR, entry(1)));
        writer.start();
        Thread.sleep(100);
        release.countDown();
        holder.join();
        writer.join();
        assertFalse(writtenWhileLocked.get());
        assertEquals(1, log.read(GUILD, true).size());
    }

    @Test
    void concurrentAppendsAreAllKept() throws Exception {
        AuditLog log = log(AuditLog.MAX_ENTRIES);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int base = t * 100;
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 25; i++) log.record(GUILD, ACTOR, entry(base + i + 1));
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();
        List<AuditEntry> entries = log.read(GUILD, true);
        assertEquals(200, entries.size());
        Set<String> ids = new HashSet<>();
        for (AuditEntry entry : entries) ids.add(entry.changes.get(0).oldValue.id);
        assertEquals(200, ids.size());
    }
}

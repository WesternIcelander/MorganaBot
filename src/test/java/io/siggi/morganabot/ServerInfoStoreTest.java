package io.siggi.morganabot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ServerInfoStoreTest {

    private static final long GUILD = 100L;

    @TempDir
    Path tempDir;

    private ServerInfoStore store;
    private File file;

    @BeforeEach
    void setUp() {
        store = new ServerInfoStore(tempDir.toFile(), new Gson());
        file = store.getFile(GUILD);
    }

    private void copyFixture() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("fixtures/server-v0.json")) {
            assertNotNull(in);
            Files.copy(in, file.toPath());
        }
    }

    private JsonObject readJson() throws IOException {
        return JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Rewrites the file the way a person editing it by hand would, keeping its modified time moving forward. */
    private void handEdit(java.util.function.Consumer<JsonObject> edit) throws IOException {
        JsonObject json = readJson();
        edit.accept(json);
        FileTime before = Files.getLastModifiedTime(file.toPath());
        Files.writeString(file.toPath(), json.toString(), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(before.toMillis() + 2000L));
    }

    @Test
    void missingFileGivesDefaultsAndIsNotCreatedByReading() {
        ServerInfo info = store.get(GUILD);
        assertFalse(info.enabledTwitchTracking);
        assertTrue(info.streamers.isEmpty());
        assertFalse(file.exists());
    }

    @Test
    void loadsFixture() throws IOException {
        copyFixture();
        ServerInfo info = store.get(GUILD);
        assertTrue(info.enabledTwitchTracking);
        assertEquals(3, info.streamers.size());
        assertEquals("22222222", info.streamers.get(1).twitchId);
    }

    @Test
    void modifySavesToDisk() throws IOException {
        copyFixture();
        store.modify(GUILD, info -> info.roleForLiveUsers = 42L);
        assertEquals(42L, readJson().get("roleForLiveUsers").getAsLong());
        assertEquals(42L, store.get(GUILD).roleForLiveUsers);
        // A fresh store (e.g. after a restart) sees the change too.
        assertEquals(42L, new ServerInfoStore(tempDir.toFile(), new Gson()).get(GUILD).roleForLiveUsers);
    }

    @Test
    void modifyCreatesMissingFile() {
        store.modify(GUILD, info -> info.channelToPostLiveNotifications = 7L);
        assertTrue(file.exists());
        assertEquals(7L, store.get(GUILD).channelToPostLiveNotifications);
    }

    @Test
    void modifyWithoutChangeDoesNotWrite() throws IOException {
        copyFixture();
        FileTime old = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(file.toPath(), old);
        String result = store.modify(GUILD, info -> "unchanged");
        assertEquals("unchanged", result);
        assertEquals(old, Files.getLastModifiedTime(file.toPath()));
    }

    @Test
    void getReturnsIndependentCopy() throws IOException {
        copyFixture();
        ServerInfo copy = store.get(GUILD);
        copy.roleForLiveUsers = 1L;
        copy.streamers.clear();
        ServerInfo again = store.get(GUILD);
        assertEquals(1163991005738061975L, again.roleForLiveUsers);
        assertEquals(3, again.streamers.size());
    }

    @Test
    void failedModifyLeavesCacheAndFileUnchanged() throws IOException {
        copyFixture();
        String before = Files.readString(file.toPath());
        assertThrows(RuntimeException.class, () -> store.modify(GUILD, info -> {
            info.streamers.clear();
            throw new RuntimeException("boom");
        }));
        assertEquals(3, store.get(GUILD).streamers.size());
        assertEquals(before, Files.readString(file.toPath()));
    }

    @Test
    void handEditIsPickedUpAndNotOverwritten() throws IOException {
        copyFixture();
        assertTrue(store.get(GUILD).enabledTwitchTracking); // cache it

        handEdit(json -> json.addProperty("enabledTwitchTracking", false));
        assertFalse(store.get(GUILD).enabledTwitchTracking);

        handEdit(json -> json.addProperty("enabledTwitchTracking", true));
        store.modify(GUILD, info -> info.roleForLiveUsers = 5L);
        JsonObject saved = readJson();
        assertTrue(saved.get("enabledTwitchTracking").getAsBoolean(), "hand edit was overwritten by stale cache");
        assertEquals(5L, saved.get("roleForLiveUsers").getAsLong());
    }

    @Test
    void sameSizeHandEditWithNewTimeIsPickedUp() throws IOException {
        copyFixture();
        assertEquals(900000L, store.get(GUILD).streamNotificationCooldown);
        // Same length, different value: only the modified time tells them apart.
        handEdit(json -> json.addProperty("streamNotificationCooldown", 800000L));
        assertEquals(800000L, store.get(GUILD).streamNotificationCooldown);
    }

    @Test
    void createdByHandAfterCachingMissingFile() throws IOException {
        assertFalse(store.get(GUILD).enabledTwitchTracking);
        Files.writeString(file.toPath(), "{\"enabledTwitchTracking\":true}");
        assertTrue(store.get(GUILD).enabledTwitchTracking);
    }

    @Test
    void corruptFileThrowsAndIsNotOverwritten() throws IOException {
        copyFixture();
        store.get(GUILD);
        String broken = "{\"enabledTwitchTracking\": tru";
        Files.writeString(file.toPath(), broken);
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        assertThrows(IllegalStateException.class, () -> store.get(GUILD));
        assertThrows(IllegalStateException.class, () -> store.modify(GUILD, info -> info.roleForLiveUsers = 1L));
        assertEquals(broken, Files.readString(file.toPath()));
    }

    @Test
    void emptyFileThrowsAndIsNotOverwritten() throws IOException {
        Files.writeString(file.toPath(), "");
        assertThrows(IllegalStateException.class, () -> store.modify(GUILD, info -> info.roleForLiveUsers = 1L));
        assertEquals("", Files.readString(file.toPath()));
    }

    @Test
    void nestedModifyIsRejected() {
        assertThrows(IllegalStateException.class, () -> store.modify(GUILD, outer ->
                store.modify(GUILD, inner -> inner.roleForLiveUsers = 1L)));
    }

    @Test
    void concurrentModifiesDoNotLoseUpdates() throws Exception {
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int n = i;
                        store.modify(GUILD, info -> {
                            info.streamNotificationCooldown++;
                            info.streamers.add(new ServerInfo.Streamer(0L, id + "-" + n));
                            return null;
                        });
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        ServerInfo info = new ServerInfoStore(tempDir.toFile(), new Gson()).get(GUILD);
        assertEquals(1000L * 60L * 30L + threads * perThread, info.streamNotificationCooldown);
        assertEquals(threads * perThread, info.streamers.size());
    }

    @Test
    void separateGuildsDoNotBlockEachOther() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean otherDone = new AtomicBoolean();
        Thread holder = new Thread(() -> store.modify(GUILD, info -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            info.roleForLiveUsers = 1L;
            return null;
        }));
        holder.start();
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        store.modify(GUILD + 1, info -> info.roleForLiveUsers = 2L);
        otherDone.set(true);
        release.countDown();
        holder.join(10_000L);
        assertTrue(otherDone.get());
        assertEquals(1L, store.get(GUILD).roleForLiveUsers);
        assertEquals(2L, store.get(GUILD + 1).roleForLiveUsers);
    }

    /**
     * The live-post callback pattern: a snapshot is taken, the streamer is deleted meanwhile, and the callback then
     * saves through modify() by Twitch ID. The deleted streamer must not come back.
     */
    @Test
    void lateCallbackDoesNotResurrectDeletedStreamer() throws IOException {
        copyFixture();
        ServerInfo snapshot = store.get(GUILD);
        String twitchId = snapshot.streamers.get(1).twitchId;

        store.modify(GUILD, info -> info.streamers.removeIf(s -> twitchId.equals(s.twitchId)));

        store.modify(GUILD, info -> {
            for (ServerInfo.Streamer s : info.streamers) {
                if (twitchId.equals(s.twitchId)) {
                    s.lastLiveNotificationId = 999L;
                    break;
                }
            }
            return null;
        });

        ServerInfo after = store.get(GUILD);
        assertEquals(2, after.streamers.size());
        assertTrue(after.streamers.stream().noneMatch(s -> twitchId.equals(s.twitchId)));
    }
}

package io.siggi.morganabot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParser;
import io.siggi.morganabot.config.Data;
import io.siggi.morganabot.config.ServerInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loads a hand-made copy of today's production {@code data/servers/<id>.json} format
 * ({@code fixtures/server-v0.json}), checks every field, saves it, and checks nothing was dropped.
 * Extend the fixture and the assertions whenever a step adds stored fields.
 */
class ServerInfoFixtureTest {

    private static final String FIXTURE = "fixtures/server-v0.json";

    @TempDir
    Path tempDir;

    private File copyFixture(String name) throws IOException {
        Path target = tempDir.resolve(name);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "fixture missing: " + FIXTURE);
            Files.copy(in, target);
        }
        return target.toFile();
    }

    private static void assertFixtureContents(ServerInfo info) {
        assertTrue(info.enabledTwitchTracking);
        assertEquals(1532050821162860574L, info.channelToPostLiveNotifications);
        assertTrue(info.deleteNotificationsOnOffline);
        assertEquals(1163991005738061975L, info.roleForLiveUsers);
        assertEquals("${streamer_name} has gone live on Twitch! Watch at: ${twitch_link}", info.liveNotificationTemplate);
        assertEquals(900000L, info.streamNotificationCooldown);

        assertEquals(3, info.streamers.size());

        ServerInfo.Streamer a = info.streamers.get(0);
        assertEquals(0L, a.channelToPostLiveNotifications);
        assertEquals(1300000000000000001L, a.lastLiveNotificationId);
        assertEquals(200000000000000001L, a.discordId);
        assertEquals("11111111", a.twitchId);
        assertNull(a.liveNotificationTemplate);
        assertEquals(1730000000000L, a.startedStreaming);
        assertEquals(1730003600000L, a.stoppedStreaming);

        ServerInfo.Streamer b = info.streamers.get(1);
        assertEquals(1400000000000000002L, b.channelToPostLiveNotifications);
        assertEquals(0L, b.lastLiveNotificationId);
        assertEquals(200000000000000002L, b.discordId);
        assertEquals("22222222", b.twitchId);
        assertEquals("**${streamer_name}** is live: ${title} ${twitch_link} <@&1163991005738061975>", b.liveNotificationTemplate);
        assertEquals(1731000000000L, b.startedStreaming);
        assertEquals(0L, b.stoppedStreaming);

        ServerInfo.Streamer c = info.streamers.get(2);
        assertEquals(0L, c.channelToPostLiveNotifications);
        assertEquals(0L, c.lastLiveNotificationId);
        assertEquals(0L, c.discordId);
        assertEquals("33333333", c.twitchId);
        assertNull(c.liveNotificationTemplate);
        assertEquals(0L, c.startedStreaming);
        assertEquals(0L, c.stoppedStreaming);
    }

    private static JsonElement parse(File file) throws IOException {
        return JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    /**
     * Deep comparison that ignores key order and compares numbers by their exact text, since
     * {@link JsonElement#equals} compares parsed numbers as doubles and would hide lost precision in IDs.
     */
    private static void assertSameJson(String path, JsonElement expected, JsonElement actual) {
        if (expected.isJsonObject()) {
            assertTrue(actual.isJsonObject(), path + " is not an object");
            JsonObject e = expected.getAsJsonObject();
            JsonObject a = actual.getAsJsonObject();
            assertEquals(e.keySet(), a.keySet(), path + " keys differ");
            for (String key : e.keySet()) {
                assertSameJson(path + "." + key, e.get(key), a.get(key));
            }
        } else if (expected.isJsonArray()) {
            assertTrue(actual.isJsonArray(), path + " is not an array");
            JsonArray e = expected.getAsJsonArray();
            JsonArray a = actual.getAsJsonArray();
            assertEquals(e.size(), a.size(), path + " size differs");
            for (int i = 0; i < e.size(); i++) {
                assertSameJson(path + "[" + i + "]", e.get(i), a.get(i));
            }
        } else if (expected.isJsonNull()) {
            assertTrue(actual.isJsonNull(), path + " is not null");
        } else {
            assertTrue(actual.isJsonPrimitive(), path + " is not a primitive");
            JsonPrimitive e = expected.getAsJsonPrimitive();
            JsonPrimitive a = actual.getAsJsonPrimitive();
            assertEquals(e.isBoolean(), a.isBoolean(), path + " type differs");
            assertEquals(e.isNumber(), a.isNumber(), path + " type differs");
            assertEquals(e.isString(), a.isString(), path + " type differs");
            assertEquals(e.getAsString(), a.getAsString(), path + " value differs");
        }
    }

    @Test
    void loadsEveryFieldOfV0Format() throws IOException {
        File file = copyFixture("100.json");
        ServerInfo info = Data.read(file, ServerInfo.class, new Gson());
        assertFixtureContents(info);
    }

    @Test
    void saveKeepsEveryField() throws IOException {
        File file = copyFixture("100.json");
        JsonElement original = parse(file);

        ServerInfo info = Data.read(file, ServerInfo.class, new Gson());
        info.save();

        assertTrue(file.exists(), "save() removed the file");
        assertSameJson("$", original, parse(file));

        ServerInfo reloaded = Data.read(file, ServerInfo.class, new Gson());
        assertFixtureContents(reloaded);
    }

    @Test
    void missingFileGivesDefaults() {
        ServerInfo info = Data.read(tempDir.resolve("missing.json").toFile(), ServerInfo.class, new Gson());
        assertFalse(info.enabledTwitchTracking);
        assertEquals(0L, info.channelToPostLiveNotifications);
        assertFalse(info.deleteNotificationsOnOffline);
        assertEquals(0L, info.roleForLiveUsers);
        assertNull(info.liveNotificationTemplate);
        assertEquals(1000L * 60L * 30L, info.streamNotificationCooldown);
        assertTrue(info.streamers.isEmpty());
    }
}

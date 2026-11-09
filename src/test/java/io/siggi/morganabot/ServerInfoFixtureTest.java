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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loads a hand-made copy of today's production {@code data/servers/<id>.json} format
 * ({@code fixtures/server-v0.json}), checks every field, saves it, and checks nothing was dropped.
 * Extend the fixture and the assertions whenever a step adds stored fields.
 */
class ServerInfoFixtureTest {

    private static final String FIXTURE = "fixtures/server-v0.json";

    /**
     * Optional fields added since v0 (steps 7, 8 and 16). Saving a v0 file writes them with their defaults; nothing else may
     * appear or disappear.
     */
    private static final Set<String> ADDED_SERVER_KEYS = Set.of("selfServiceRoles", "selfServiceRemoveAfterDays",
            "adminRoles", "messageCustomizationRules", "blocked", "blockedAt", "blockedBy", "blockedByName", "blockedReason",
            "blockedName", "autoPublishChannels");
    private static final Set<String> ADDED_STREAMER_KEYS = Set.of("selfService", "addedBy", "selfServiceLostAt", "channelOverridePinned",
            "templateSetBy");

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

        // Fields added by step 7 aren't in v0 files: self-service off, 7-day expiry, every streamer admin-added.
        assertTrue(info.selfServiceRoles().isEmpty());
        assertEquals(7, info.selfServiceRemoveAfterDays);
        for (ServerInfo.Streamer streamer : info.streamers) {
            assertFalse(streamer.selfService);
            assertEquals(0L, streamer.addedBy);
            assertEquals(0L, streamer.selfServiceLostAt);
            assertFalse(streamer.channelOverridePinned);
            assertFalse(streamer.isSelfServicePaused());
            assertEquals(0L, streamer.templateSetBy);
        }
        assertTrue(info.adminRoles().isEmpty());
        assertTrue(info.messageCustomizationRules().isEmpty());
        // Step 16's block fields: not blocked.
        assertFalse(info.blocked);
        assertEquals(0L, info.blockedAt);
        assertEquals(0L, info.blockedBy);
        assertNull(info.blockedByName);
        assertNull(info.blockedReason);
        assertNull(info.blockedName);
        // Step 19: no auto-publish entries, so every announcement channel publishes (what production did before).
        assertTrue(info.autoPublishChannels().isEmpty());
        assertTrue(info.isAutoPublish(info.channelToPostLiveNotifications));
        assertTrue(info.isAutoPublish(1400000000000000002L));
    }

    @Test
    void autoPublishRoundTrip() throws IOException {
        File file = tempDir.resolve("publish.json").toFile();
        Files.writeString(file.toPath(), """
                {
                  "enabledTwitchTracking": true,
                  "autoPublishChannels": {"1532050821162860574": false, "1400000000000000002": true}
                }
                """, StandardCharsets.UTF_8);
        ServerInfo info = Data.read(file, ServerInfo.class, new Gson());
        assertFalse(info.isAutoPublish(1532050821162860574L));
        assertTrue(info.isAutoPublish(1400000000000000002L));
        assertTrue(info.isAutoPublish(1500000000000000003L), "a missing channel means on");
        info.autoPublishChannels().put(1500000000000000003L, false);
        info.save();
        ServerInfo again = Data.read(file, ServerInfo.class, new Gson());
        assertFalse(again.isAutoPublish(1532050821162860574L));
        assertFalse(again.isAutoPublish(1500000000000000003L));
        assertTrue(again.isAutoPublish(1400000000000000002L));
        assertTrue(parse(file).getAsJsonObject().getAsJsonObject("autoPublishChannels").has("1500000000000000003"));
    }

    @Test
    void nullAutoPublishMeansOn() throws IOException {
        File file = tempDir.resolve("null-publish.json").toFile();
        Files.writeString(file.toPath(), "{\"autoPublishChannels\": null}", StandardCharsets.UTF_8);
        ServerInfo info = Data.read(file, ServerInfo.class, new Gson());
        assertTrue(info.isAutoPublish(5L));
    }

    @Test
    void blockFieldsRoundTrip() throws IOException {
        File file = tempDir.resolve("blocked.json").toFile();
        Files.writeString(file.toPath(), """
                {
                  "enabledTwitchTracking": true,
                  "blocked": true,
                  "blockedAt": 1762700000000,
                  "blockedBy": 260595748465278976,
                  "blockedByName": "siggi",
                  "blockedReason": "Spam",
                  "blockedName": "Bad Server",
                  "streamers": [{"discordId": 200000000000000004, "twitchId": "44444444"}]
                }
                """, StandardCharsets.UTF_8);
        ServerInfo info = Data.read(file, ServerInfo.class, new Gson());
        assertTrue(info.blocked);
        assertEquals(1762700000000L, info.blockedAt);
        assertEquals(260595748465278976L, info.blockedBy);
        assertEquals("siggi", info.blockedByName);
        assertEquals("Spam", info.blockedReason);
        assertEquals("Bad Server", info.blockedName);
        info.save();
        ServerInfo reloaded = Data.read(file, ServerInfo.class, new Gson());
        assertEquals(new Gson().toJson(info), new Gson().toJson(reloaded));
        reloaded.clearBlock();
        assertFalse(reloaded.blocked);
        assertEquals(0L, reloaded.blockedAt);
        assertNull(reloaded.blockedName);
        assertEquals(1, reloaded.streamers.size(), "unblocking keeps the settings");
    }

    @Test
    void accessFieldsRoundTrip() throws IOException {
        File file = tempDir.resolve("access.json").toFile();
        Files.writeString(file.toPath(), """
                {
                  "adminRoles": [1163991005738061976],
                  "messageCustomizationRules": [{"roleId": 1163991005738061977, "allowEveryone": true, "allowedMentionRoles": [1163991005738061978]}],
                  "streamers": [{"discordId": 200000000000000004, "twitchId": "44444444", "liveNotificationTemplate": "hi", "templateSetBy": 200000000000000005}]
                }
                """, StandardCharsets.UTF_8);
        ServerInfo info = Data.read(file, ServerInfo.class, new Gson());
        assertEquals(List.of(1163991005738061976L), info.adminRoles);
        ServerInfo.MessageCustomizationRule rule = info.messageCustomizationRules.get(0);
        assertEquals(1163991005738061977L, rule.roleId);
        assertTrue(rule.allowEveryone);
        assertEquals(List.of(1163991005738061978L), rule.allowedMentionRoles);
        assertEquals(200000000000000005L, info.streamers.get(0).templateSetBy);
        info.save();
        ServerInfo again = Data.read(file, ServerInfo.class, new Gson());
        assertEquals(1163991005738061976L, again.adminRoles.get(0));
        assertEquals(1163991005738061978L, again.messageCustomizationRules.get(0).allowedMentionRoles.get(0));
        assertEquals(200000000000000005L, again.streamers.get(0).templateSetBy);
    }

    @Test
    void nullAccessListsAreTreatedAsEmpty() throws IOException {
        File file = tempDir.resolve("null-access.json").toFile();
        Files.writeString(file.toPath(), "{\"adminRoles\": null, \"messageCustomizationRules\": [{\"roleId\": 5, \"allowedMentionRoles\": null}]}", StandardCharsets.UTF_8);
        ServerInfo info = Data.read(file, ServerInfo.class, new Gson());
        assertTrue(info.adminRoles().isEmpty());
        assertTrue(info.messageCustomizationRules().get(0).allowedMentionRoles().isEmpty());
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
            Set<String> added = new HashSet<>(a.keySet());
            added.removeAll(e.keySet());
            Set<String> allowed = path.equals("$") ? ADDED_SERVER_KEYS : path.startsWith("$.streamers[") ? ADDED_STREAMER_KEYS : Set.of();
            assertTrue(allowed.containsAll(added), path + " has unexpected new keys " + added);
            assertTrue(a.keySet().containsAll(e.keySet()), path + " lost keys");
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
    void selfServiceFieldsRoundTrip() throws IOException {
        File file = tempDir.resolve("200.json").toFile();
        Files.writeString(file.toPath(), """
                {
                  "enabledTwitchTracking": true,
                  "selfServiceRoles": [{"roleId": 1163991005738061976, "channelId": 1400000000000000003}, {"roleId": 1163991005738061977, "channelId": 0}],
                  "selfServiceRemoveAfterDays": 0,
                  "streamers": [{"discordId": 200000000000000004, "twitchId": "44444444", "selfService": true,
                    "addedBy": 200000000000000004, "selfServiceLostAt": 1732000000000, "channelOverridePinned": true}]
                }
                """, StandardCharsets.UTF_8);
        ServerInfo info = Data.read(file, ServerInfo.class, new Gson());
        assertEquals(2, info.selfServiceRoles.size());
        assertEquals(1163991005738061976L, info.selfServiceRoles.get(0).roleId);
        assertEquals(1400000000000000003L, info.selfServiceRoles.get(0).channelId);
        assertEquals(0L, info.selfServiceRoles.get(1).channelId);
        assertEquals(0, info.selfServiceRemoveAfterDays);
        ServerInfo.Streamer s = info.streamers.get(0);
        assertTrue(s.selfService);
        assertEquals(200000000000000004L, s.addedBy);
        assertEquals(1732000000000L, s.selfServiceLostAt);
        assertTrue(s.channelOverridePinned);
        assertTrue(s.isSelfServicePaused());

        info.save();
        ServerInfo reloaded = Data.read(file, ServerInfo.class, new Gson());
        assertEquals(new Gson().toJson(info), new Gson().toJson(reloaded));
    }

    @Test
    void nullSelfServiceRolesIsTreatedAsEmpty() throws IOException {
        File file = tempDir.resolve("300.json").toFile();
        Files.writeString(file.toPath(), "{\"selfServiceRoles\": null}", StandardCharsets.UTF_8);
        ServerInfo info = Data.read(file, ServerInfo.class, new Gson());
        assertTrue(info.selfServiceRoles().isEmpty());
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

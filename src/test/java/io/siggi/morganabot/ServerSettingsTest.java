package io.siggi.morganabot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.siggi.morganabot.ServerSettings.SettingsException;
import io.siggi.morganabot.ServerSettings.StreamerRef;
import io.siggi.morganabot.ServerSettings.TwitchUser;
import io.siggi.morganabot.audit.Actor;
import io.siggi.morganabot.audit.AuditEntry;
import io.siggi.morganabot.audit.AuditLog;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerSettingsTest {

    private static final long GUILD = 100L;
    private static final Actor ACTOR = Actor.command(301L, "admin_user", "/test");

    @TempDir
    Path tempDir;

    private ServerInfoStore store;
    private FakeTwitch twitch;
    private ServerSettings settings;
    private AuditLog auditLog;

    /**
     * Names as the bot would see them: channels, roles and users by ID; Twitch logins from the fake Twitch.
     */
    private final class FakeNames implements AuditLog.Names {
        @Override
        public String channel(long guildId, long channelId) {
            return "channel" + channelId;
        }

        @Override
        public String role(long guildId, long roleId) {
            return "role" + roleId;
        }

        @Override
        public String user(long guildId, long userId) {
            return "user" + userId;
        }

        @Override
        public String twitchLogin(String twitchId) {
            twitch.idLookups.add(twitchId);
            for (TwitchUser user : twitch.users.values()) {
                if (user.id().equals(twitchId)) return user.login();
            }
            return null;
        }
    }

    private static final class FakeTwitch implements ServerSettings.Twitch {
        final Map<String, TwitchUser> users = new HashMap<>();
        final List<String> lookups = new ArrayList<>();
        final List<String> idLookups = new ArrayList<>();
        int updates;

        void add(String id, String login, String displayName) {
            users.put(login, new TwitchUser(id, login, displayName));
        }

        @Override
        public TwitchUser findUserByLogin(String login) {
            lookups.add(login);
            return users.get(login.toLowerCase(Locale.ROOT));
        }

        @Override
        public void triggerUpdate() {
            updates++;
        }
    }

    @BeforeEach
    void setUp() {
        store = new ServerInfoStore(tempDir.toFile(), new Gson());
        twitch = new FakeTwitch();
        twitch.add("11111111", "alice", "Alice");
        twitch.add("22222222", "bob", "Bob");
        twitch.add("33333333", "carol", "Carol");
        twitch.add("44444444", "dave", "Dave");
        twitch.add("55555555", "jp_streamer", "日本の配信者");
        auditLog = new AuditLog(tempDir.toFile(), new Gson(), store, new FakeNames());
        settings = new ServerSettings(store, twitch, auditLog);
        store.modify(GUILD, info -> {
            info.enabledTwitchTracking = true;
            // An unlinked streamer first, so a match on discordId == 0 would hit it before anyone else.
            info.streamers.add(new ServerInfo.Streamer(0L, "33333333"));
            info.streamers.add(new ServerInfo.Streamer(201L, "11111111"));
            info.streamers.add(new ServerInfo.Streamer(202L, "22222222"));
            return null;
        });
    }

    private List<String> twitchIds() {
        List<String> ids = new ArrayList<>();
        for (ServerInfo.Streamer streamer : store.get(GUILD).streamers) {
            ids.add(streamer.twitchId);
        }
        return ids;
    }

    private ServerInfo.Streamer streamer(String twitchId) {
        for (ServerInfo.Streamer streamer : store.get(GUILD).streamers) {
            if (twitchId.equals(streamer.twitchId)) return streamer;
        }
        return null;
    }

    // --- Twitch tracking gate ---

    @Test
    void everyChangeIsRefusedWhenTwitchIsOff() {
        store.modify(GUILD, info -> info.enabledTwitchTracking = false);
        String before = new Gson().toJson(store.get(GUILD));
        assertMessage(settings.notEnabledMessage(), () -> settings.addStreamer(ACTOR, GUILD, "dave", 0L, 0L));
        assertMessage(settings.notEnabledMessage(), () -> settings.deleteStreamer(ACTOR, GUILD, new StreamerRef("bob", 0L)));
        assertMessage(settings.notEnabledMessage(), () -> settings.setChannelOverride(ACTOR, GUILD, new StreamerRef("bob", 0L), 5L));
        assertMessage(settings.notEnabledMessage(), () -> settings.setLiveChannel(ACTOR, GUILD, 5L));
        assertMessage(settings.notEnabledMessage(), () -> settings.setLiveRole(ACTOR, GUILD, 5L));
        assertMessage(settings.notEnabledMessage(), () -> settings.setDeleteOnOffline(ACTOR, GUILD, true));
        assertMessage(settings.notEnabledMessage(), () -> settings.setNotificationCooldown(ACTOR, GUILD, 5L));
        assertMessage(settings.notEnabledMessage(), () -> settings.setServerTemplate(ACTOR, GUILD, "x"));
        assertMessage(settings.notEnabledMessage(), () -> settings.setStreamerTemplate(ACTOR, GUILD, new StreamerRef(null, 201L), "x"));
        assertMessage(settings.notEnabledMessage(), () -> settings.requireTwitchEnabled(GUILD));
        assertEquals(before, new Gson().toJson(store.get(GUILD)));
        assertEquals(0, twitch.updates);
        assertTrue(twitch.lookups.isEmpty(), "no Twitch lookups for a disabled server");
    }

    @Test
    void changingTwitchTrackingTriggersUpdate() throws Exception {
        assertFalse(settings.setTwitchTracking(ACTOR, GUILD, true));
        assertEquals(0, twitch.updates, "no change, no resync");
        assertTrue(settings.setTwitchTracking(ACTOR, GUILD, false));
        assertEquals(1, twitch.updates);
        assertFalse(store.get(GUILD).enabledTwitchTracking);
        assertTrue(settings.setTwitchTracking(ACTOR, GUILD, true));
        assertEquals(2, twitch.updates);
        assertTrue(store.get(GUILD).enabledTwitchTracking);
    }

    // --- removing and blocking servers ---

    /** Fake Discord for leaveGuild: records the order of saves and leaves. */
    private final class FakeGuilds implements ServerSettings.Guilds {
        final Map<Long, String> names = new HashMap<>();
        final List<String> events = new ArrayList<>();
        Exception failure;

        @Override
        public String name(long guildId) {
            return names.get(guildId);
        }

        @Override
        public void leave(long guildId) throws Exception {
            // The block must already be on disk when the bot leaves.
            events.add("leave blocked=" + store.get(guildId).blocked);
            if (failure != null) throw failure;
            names.remove(guildId);
        }

        @Override
        public String userName(long userId) {
            return "user" + userId;
        }
    }

    private FakeGuilds guilds() {
        FakeGuilds guilds = new FakeGuilds();
        guilds.names.put(GUILD, "Main");
        settings.setGuilds(guilds);
        settings.setClock(() -> 5000L);
        return guilds;
    }

    private java.io.ByteArrayOutputStream captureLog() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        settings.setLog(new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
        return out;
    }

    @Test
    void leaveGuildSavesBlockFirstThenLeavesAndSyncs() throws Exception {
        FakeGuilds guilds = guilds();
        java.io.ByteArrayOutputStream log = captureLog();
        int auditEntries = auditLog.read(GUILD, true).size();
        ServerSettings.LeaveResult result = settings.leaveGuild(GUILD, "  breaks the rules ", true, 999L);
        assertEquals(new ServerSettings.LeaveResult("Main", true), result);
        assertEquals(List.of("leave blocked=true"), guilds.events);
        assertEquals(1, twitch.updates);
        ServerInfo info = store.get(GUILD);
        assertTrue(info.blocked);
        assertEquals(5000L, info.blockedAt);
        assertEquals(999L, info.blockedBy);
        assertEquals("user999", info.blockedByName);
        assertEquals("breaks the rules", info.blockedReason);
        assertEquals("Main", info.blockedName);
        assertEquals(3, info.streamers.size(), "settings are kept");
        assertTrue(info.enabledTwitchTracking);
        String line = log.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(line.contains("user999 (999) removed the bot from server \"Main\" (100) and blocked it"), line);
        assertTrue(line.contains("Reason: breaks the rules"), line);
        assertEquals(auditEntries, auditLog.read(GUILD, true).size(), "audit entries come with step 17");

        assertTrue(settings.isBlocked(GUILD));
        assertEquals(List.of(new ServerSettings.BlockedServer(GUILD, "Main", 5000L, 999L, "user999", "breaks the rules")),
                settings.blockedServers());
    }

    @Test
    void leaveGuildWithoutBlock() throws Exception {
        FakeGuilds guilds = guilds();
        captureLog();
        assertEquals(new ServerSettings.LeaveResult("Main", false), settings.leaveGuild(GUILD, "", false, 999L));
        assertEquals(List.of("leave blocked=false"), guilds.events);
        assertFalse(store.get(GUILD).blocked);
        assertEquals(1, twitch.updates);
        assertTrue(settings.blockedServers().isEmpty());
    }

    @Test
    void leaveGuildRefusesWhenNotInServerOrReasonTooLong() {
        FakeGuilds guilds = guilds();
        captureLog();
        assertMessage("The bot isn't in that server (or Discord is unavailable).", () -> settings.leaveGuild(555L, null, true, 999L));
        assertFalse(store.get(555L).blocked);
        assertThrows(SettingsException.class, () -> settings.leaveGuild(GUILD, "x".repeat(ServerSettings.MAX_BLOCK_REASON + 1), true, 999L));
        assertFalse(store.get(GUILD).blocked, "nothing saved");
        assertTrue(guilds.events.isEmpty());
        assertEquals(0, twitch.updates);
    }

    @Test
    void failedLeaveKeepsTheBlock() {
        FakeGuilds guilds = guilds();
        java.io.ByteArrayOutputStream log = captureLog();
        guilds.failure = new IllegalStateException("timeout");
        SettingsException e = assertThrows(SettingsException.class, () -> settings.leaveGuild(GUILD, null, true, 999L));
        assertTrue(e.getMessage().contains("The block is saved"), e.getMessage());
        assertTrue(store.get(GUILD).blocked);
        assertEquals(1, twitch.updates, "subscriptions still dropped for the blocked server");
        assertTrue(log.toString(java.nio.charset.StandardCharsets.UTF_8).contains("leaving failed"));
    }

    @Test
    void unblockClearsTheBlockAndKeepsSettings() throws Exception {
        guilds();
        java.io.ByteArrayOutputStream log = captureLog();
        settings.leaveGuild(GUILD, "spam", true, 999L);
        ServerSettings.BlockedServer lifted = settings.unblock(GUILD, 998L);
        assertNotNull(lifted);
        assertEquals("spam", lifted.reason());
        ServerInfo info = store.get(GUILD);
        assertFalse(info.blocked);
        assertEquals(0L, info.blockedAt);
        assertNull(info.blockedReason);
        assertNull(info.blockedName);
        assertEquals(3, info.streamers.size());
        assertTrue(log.toString(java.nio.charset.StandardCharsets.UTF_8).contains("user998 (998) unblocked server \"Main\" (100)"));
        assertNull(settings.unblock(GUILD, 998L), "not blocked any more");
        assertNull(settings.unblock(777L, 998L), "no file");
        assertFalse(new File(tempDir.toFile(), "777.json").exists(), "unblocking an unknown server writes nothing");
    }

    @Test
    void blockedServersScansFilesIncludingHandEdits() throws Exception {
        // Hand-written files the store hasn't seen yet, an unreadable one, and other per-server files.
        java.nio.file.Files.writeString(tempDir.resolve("300.json"), "{\"blocked\": true, \"blockedAt\": 10, \"blockedName\": \"Old\"}");
        java.nio.file.Files.writeString(tempDir.resolve("400.json"), "{\"blocked\": true, \"blockedAt\": 20}");
        java.nio.file.Files.writeString(tempDir.resolve("500.json"), "{\"blocked\": false}");
        java.nio.file.Files.writeString(tempDir.resolve("600.json"), "{not json");
        java.nio.file.Files.writeString(tempDir.resolve("300-audit.jsonl"), "");
        java.nio.file.Files.writeString(tempDir.resolve("300.json.bak"), "{\"blocked\": true}");
        List<ServerSettings.BlockedServer> blocked = settings.blockedServers();
        assertEquals(List.of(400L, 300L), blocked.stream().map(ServerSettings.BlockedServer::guildId).toList(), "newest first");
        assertEquals("Old", blocked.get(1).name());
        assertTrue(settings.isBlocked(300L));
        assertFalse(settings.isBlocked(500L));
        assertFalse(settings.isBlocked(600L), "an unreadable file never makes the bot leave");
        assertFalse(settings.isBlocked(GUILD));
        assertEquals(List.of(100L, 300L, 400L, 500L, 600L), store.guildIds());
    }

    // --- addStreamer ---

    @Test
    void addStreamerStoresEntryAndTriggersUpdate() throws Exception {
        TwitchUser user = settings.addStreamer(ACTOR, GUILD, "Dave", 204L, 900L);
        assertEquals("Dave", user.displayName());
        ServerInfo.Streamer added = streamer("44444444");
        assertNotNull(added);
        assertEquals(204L, added.discordId);
        assertEquals(900L, added.channelToPostLiveNotifications);
        assertEquals(1, twitch.updates);
    }

    @Test
    void addStreamerWithUnknownTwitchNameGivesHelpfulError() {
        // Used to throw a NullPointerException, which came out as a generic error.
        SettingsException e = assertThrows(SettingsException.class, () -> settings.addStreamer(ACTOR, GUILD, "nobody_here", 0L, 0L));
        assertTrue(e.getMessage().contains("nobody_here"), e.getMessage());
        assertEquals(3, store.get(GUILD).streamers.size());
        assertEquals(0, twitch.updates);
    }

    @Test
    void addStreamerRejectsInvalidAndMissingNamesWithoutLookingThemUp() {
        assertThrows(SettingsException.class, () -> settings.addStreamer(ACTOR, GUILD, "https://twitch.tv/dave", 0L, 0L));
        assertThrows(SettingsException.class, () -> settings.addStreamer(ACTOR, GUILD, "", 0L, 0L));
        assertThrows(SettingsException.class, () -> settings.addStreamer(ACTOR, GUILD, null, 0L, 0L));
        assertTrue(twitch.lookups.isEmpty());
    }

    @Test
    void addStreamerRejectsDuplicates() {
        assertMessage("The specified Twitch user is already added as a streamer!", () -> settings.addStreamer(ACTOR, GUILD, "bob", 0L, 0L));
        assertMessage("The specified Discord user is already added as a streamer!", () -> settings.addStreamer(ACTOR, GUILD, "dave", 201L, 0L));
        assertEquals(3, store.get(GUILD).streamers.size());
        assertEquals(0, twitch.updates);
    }

    @Test
    void addingTwoUnlinkedStreamersIsAllowed() throws Exception {
        settings.addStreamer(ACTOR, GUILD, "dave", 0L, 0L);
        assertEquals(List.of("33333333", "11111111", "22222222", "44444444"), twitchIds());
    }

    // --- streamer lookup (getStreamer wrong-match bug) ---

    @Test
    void deleteByTwitchNameDeletesThatStreamerNotAnUnlinkedOne() throws Exception {
        ServerInfo.Streamer removed = settings.deleteStreamer(ACTOR, GUILD, new StreamerRef("bob", 0L));
        assertEquals("22222222", removed.twitchId);
        assertEquals(List.of("33333333", "11111111"), twitchIds());
        assertEquals(1, twitch.updates);
    }

    @Test
    void deleteByUnknownTwitchNameDeletesNothing() {
        // Used to match the first streamer with discordId 0.
        assertThrows(SettingsException.class, () -> settings.deleteStreamer(ACTOR, GUILD, new StreamerRef("nobody_here", 0L)));
        assertEquals(3, store.get(GUILD).streamers.size());
        assertEquals(0, twitch.updates);
    }

    @Test
    void deleteByTwitchNameNotInThisServerDeletesNothing() {
        assertMessage("No such streamer was found!", () -> settings.deleteStreamer(ACTOR, GUILD, new StreamerRef("dave", 0L)));
        assertEquals(3, store.get(GUILD).streamers.size());
    }

    @Test
    void deleteUnlinkedStreamerByTwitchName() throws Exception {
        settings.deleteStreamer(ACTOR, GUILD, new StreamerRef("carol", 0L));
        assertEquals(List.of("11111111", "22222222"), twitchIds());
    }

    @Test
    void deleteByDiscordUser() throws Exception {
        settings.deleteStreamer(ACTOR, GUILD, new StreamerRef(null, 201L));
        assertEquals(List.of("33333333", "22222222"), twitchIds());
        assertMessage("No such streamer was found!", () -> settings.deleteStreamer(ACTOR, GUILD, new StreamerRef(null, 999L)));
    }

    @Test
    void twitchNameAndDiscordUserMustAgree() throws Exception {
        assertMessage("That Twitch name and Discord user belong to different streamers!",
                () -> settings.deleteStreamer(ACTOR, GUILD, new StreamerRef("bob", 201L)));
        assertEquals(3, store.get(GUILD).streamers.size());
        settings.deleteStreamer(ACTOR, GUILD, new StreamerRef("bob", 202L));
        assertEquals(List.of("33333333", "11111111"), twitchIds());
    }

    @Test
    void emptyStreamerRefIsRejected() {
        assertMessage("You need to specify either their Twitch name or Discord name!",
                () -> settings.deleteStreamer(ACTOR, GUILD, new StreamerRef(null, 0L)));
    }

    // --- per-streamer settings ---

    @Test
    void setAndClearChannelOverride() throws Exception {
        ServerInfo.Streamer changed = settings.setChannelOverride(ACTOR, GUILD, new StreamerRef("carol", 0L), 777L);
        assertEquals(777L, changed.channelToPostLiveNotifications);
        assertEquals(777L, streamer("33333333").channelToPostLiveNotifications);
        assertEquals(0L, streamer("11111111").channelToPostLiveNotifications);
        // The returned copy isn't tied to the store.
        changed.channelToPostLiveNotifications = 1L;
        assertEquals(777L, streamer("33333333").channelToPostLiveNotifications);

        settings.setChannelOverride(ACTOR, GUILD, new StreamerRef(null, 201L), 0L);
        assertEquals(0L, streamer("11111111").channelToPostLiveNotifications);
        assertMessage("Invalid channel.", () -> settings.setChannelOverride(ACTOR, GUILD, new StreamerRef("carol", 0L), -1L));
    }

    @Test
    void setStreamerDiscordUser() throws Exception {
        settings.setStreamerDiscordUser(ACTOR, GUILD, new StreamerRef("carol", 0L), 203L);
        assertEquals(203L, streamer("33333333").discordId);
        assertMessage("The specified Discord user is already linked to another streamer!",
                () -> settings.setStreamerDiscordUser(ACTOR, GUILD, new StreamerRef("carol", 0L), 201L));
        settings.setStreamerDiscordUser(ACTOR, GUILD, new StreamerRef(null, 203L), 0L);
        assertEquals(0L, streamer("33333333").discordId);
    }

    @Test
    void streamerTemplate() throws Exception {
        settings.setStreamerTemplate(ACTOR, GUILD, new StreamerRef(null, 202L), "hi ${twitch_link}");
        assertEquals("hi ${twitch_link}", streamer("22222222").liveNotificationTemplate);
        settings.setStreamerTemplate(ACTOR, GUILD, new StreamerRef(null, 202L), null);
        assertNull(streamer("22222222").liveNotificationTemplate);
        assertMessage("No such streamer was found!", () -> settings.setStreamerTemplate(ACTOR, GUILD, new StreamerRef(null, 999L), "x"));
    }

    // --- server-wide settings ---

    @Test
    void serverWideSettings() throws Exception {
        settings.setLiveChannel(ACTOR, GUILD, 500L);
        settings.setLiveRole(ACTOR, GUILD, 600L);
        settings.setDeleteOnOffline(ACTOR, GUILD, true);
        settings.setNotificationCooldown(ACTOR, GUILD, 60_000L);
        settings.setServerTemplate(ACTOR, GUILD, "live: ${twitch_link}");
        ServerInfo info = store.get(GUILD);
        assertEquals(500L, info.channelToPostLiveNotifications);
        assertEquals(600L, info.roleForLiveUsers);
        assertTrue(info.deleteNotificationsOnOffline);
        assertEquals(60_000L, info.streamNotificationCooldown);
        assertEquals("live: ${twitch_link}", info.liveNotificationTemplate);

        settings.setServerTemplate(ACTOR, GUILD, null);
        assertNull(store.get(GUILD).liveNotificationTemplate);
        assertEquals(0, twitch.updates, "server-wide settings don't change subscriptions");
    }

    @Test
    void serverWideValidation() {
        assertMessage("The notification cooldown can't be negative.", () -> settings.setNotificationCooldown(ACTOR, GUILD, -1L));
        assertMessage("Invalid role.", () -> settings.setLiveRole(ACTOR, GUILD, -1L));
        assertMessage("The live message can't be empty.", () -> settings.setServerTemplate(ACTOR, GUILD, "  "));
    }

    @Test
    void failedChangeDoesNotWriteFile() {
        File file = store.getFile(GUILD);
        long modified = file.lastModified();
        long length = file.length();
        assertThrows(SettingsException.class, () -> settings.setChannelOverride(ACTOR, GUILD, new StreamerRef("dave", 0L), 5L));
        assertEquals(modified, file.lastModified());
        assertEquals(length, file.length());
    }

    // --- audit log ---

    private List<AuditEntry> audit() {
        return auditLog.read(GUILD, true);
    }

    private static AuditEntry.Change change(AuditEntry entry, String field) {
        for (AuditEntry.Change change : entry.changes) {
            if (change.field.equals(field)) return change;
        }
        return null;
    }

    @Test
    void serverWideChangesAreAudited() throws Exception {
        settings.setLiveChannel(ACTOR, GUILD, 500L);
        settings.setLiveChannel(ACTOR, GUILD, 501L);
        settings.setLiveRole(ACTOR, GUILD, 600L);
        settings.setDeleteOnOffline(ACTOR, GUILD, true);
        settings.setNotificationCooldown(ACTOR, GUILD, 60_000L);
        settings.setServerTemplate(ACTOR, GUILD, "live: ${twitch_link}");
        settings.setServerTemplate(ACTOR, GUILD, null);

        List<AuditEntry> entries = audit();
        assertEquals(7, entries.size());
        // Newest first.
        AuditEntry reset = entries.get(0);
        assertEquals(AuditEntry.SERVER_TEMPLATE, reset.type);
        assertEquals("live: ${twitch_link}", change(reset, "template").oldValue.value);
        assertNull(change(reset, "template").newValue);

        AuditEntry channel = entries.get(5);
        assertEquals(AuditEntry.LIVE_CHANNEL, channel.type);
        assertEquals("500", change(channel, "channel").oldValue.id);
        assertEquals("channel500", change(channel, "channel").oldValue.name);
        assertEquals("501", change(channel, "channel").newValue.id);
        assertEquals("channel501", change(channel, "channel").newValue.name);
        assertNull(entries.get(6).changes.get(0).oldValue, "unset before");

        AuditEntry role = entries.get(4);
        assertEquals(AuditEntry.LIVE_ROLE, role.type);
        assertEquals("role600", change(role, "role").newValue.name);
        assertEquals("false", change(entries.get(3), "deleteOnOffline").oldValue.value);
        assertEquals("true", change(entries.get(3), "deleteOnOffline").newValue.value);
        assertEquals("60000", change(entries.get(2), "cooldownMillis").newValue.value);

        for (AuditEntry entry : entries) {
            assertEquals(ACTOR, entry.actor);
            assertEquals("301", entry.actor.userId);
            assertEquals("admin_user", entry.actor.name);
            assertEquals(Actor.Source.COMMAND, entry.actor.source);
            assertEquals("/test", entry.actor.command);
            assertEquals(AuditEntry.VISIBILITY_SERVER, entry.visibility);
            assertTrue(entry.time > 0L);
            assertNull(entry.streamer);
        }
    }

    @Test
    void settingTheSameValueRecordsNothing() throws Exception {
        settings.setLiveChannel(ACTOR, GUILD, 500L);
        settings.setLiveChannel(ACTOR, GUILD, 500L);
        settings.setDeleteOnOffline(ACTOR, GUILD, false);
        settings.setServerTemplate(ACTOR, GUILD, null);
        settings.setChannelOverride(ACTOR, GUILD, new StreamerRef(null, 201L), 0L);
        assertEquals(1, audit().size());
    }

    @Test
    void failedChangesRecordNothing() {
        assertThrows(SettingsException.class, () -> settings.addStreamer(ACTOR, GUILD, "bob", 0L, 0L));
        assertThrows(SettingsException.class, () -> settings.setNotificationCooldown(ACTOR, GUILD, -1L));
        assertThrows(SettingsException.class, () -> settings.deleteStreamer(ACTOR, GUILD, new StreamerRef(null, 999L)));
        store.modify(GUILD, info -> info.enabledTwitchTracking = false);
        assertThrows(SettingsException.class, () -> settings.setLiveChannel(ACTOR, GUILD, 5L));
        assertTrue(audit().isEmpty());
        assertFalse(auditLog.getFile(GUILD).exists());
    }

    @Test
    void addAndRemoveStreamerAreAudited() throws Exception {
        settings.addStreamer(ACTOR, GUILD, "Dave", 204L, 900L);
        settings.deleteStreamer(ACTOR, GUILD, new StreamerRef(null, 204L));

        List<AuditEntry> entries = audit();
        assertEquals(2, entries.size());
        AuditEntry add = entries.get(1);
        assertEquals(AuditEntry.STREAMER_ADD, add.type);
        assertEquals("44444444", add.streamer.id);
        assertEquals("dave", add.streamer.name);
        assertNull(change(add, "twitch").oldValue);
        assertEquals("44444444", change(add, "twitch").newValue.id);
        assertEquals("204", change(add, "discordUser").newValue.id);
        assertEquals("user204", change(add, "discordUser").newValue.name);
        assertEquals("900", change(add, "channel").newValue.id);
        assertNull(change(add, "template"), "unset fields aren't listed");

        AuditEntry remove = entries.get(0);
        assertEquals(AuditEntry.STREAMER_REMOVE, remove.type);
        assertEquals("44444444", remove.streamer.id);
        // Removed by Discord user, so the Twitch login is looked up by ID when recording.
        assertEquals("dave", remove.streamer.name);
        assertEquals("204", change(remove, "discordUser").oldValue.id);
        assertNull(change(remove, "discordUser").newValue);
        assertEquals("900", change(remove, "channel").oldValue.id);
        assertNull(change(remove, "twitch").newValue);
    }

    @Test
    void streamerChangesAreAudited() throws Exception {
        settings.setChannelOverride(ACTOR, GUILD, new StreamerRef("carol", 0L), 777L);
        settings.setStreamerDiscordUser(ACTOR, GUILD, new StreamerRef("carol", 0L), 203L);
        settings.setStreamerTemplate(ACTOR, GUILD, new StreamerRef(null, 203L), "hi");

        List<AuditEntry> entries = audit();
        assertEquals(3, entries.size());

        AuditEntry override = entries.get(2);
        assertEquals(AuditEntry.STREAMER_CHANNEL_OVERRIDE, override.type);
        assertEquals("33333333", override.streamer.id);
        assertEquals("carol", override.streamer.name);
        assertNull(change(override, "channel").oldValue);
        assertEquals("channel777", change(override, "channel").newValue.name);

        AuditEntry link = entries.get(1);
        assertEquals(AuditEntry.STREAMER_DISCORD_USER, link.type);
        assertNull(change(link, "discordUser").oldValue);
        assertEquals("203", change(link, "discordUser").newValue.id);
        assertEquals("user203", change(link, "discordUser").newValue.name);

        AuditEntry template = entries.get(0);
        assertEquals(AuditEntry.STREAMER_TEMPLATE, template.type);
        assertEquals("carol", template.streamer.name);
        assertEquals("hi", change(template, "template").newValue.value);
        // Twitch login only looked up when the command didn't give it.
        assertEquals(List.of("33333333"), twitch.idLookups);
    }

    @Test
    void actorSourcesAreStored() throws Exception {
        settings.setLiveChannel(Actor.dashboard(302L, "dash_user"), GUILD, 1L);
        settings.setLiveChannel(Actor.superAdmin(303L, "owner"), GUILD, 2L);
        settings.setLiveChannel(Actor.system(), GUILD, 3L);
        List<AuditEntry> entries = audit();
        assertEquals(Actor.Source.SYSTEM, entries.get(0).actor.source);
        assertNull(entries.get(0).actor.userId);
        assertEquals("system", entries.get(0).actor.name);
        assertEquals(Actor.Source.SUPER_ADMIN, entries.get(1).actor.source);
        assertEquals("303", entries.get(1).actor.userId);
        assertEquals(Actor.Source.DASHBOARD, entries.get(2).actor.source);
        assertNull(entries.get(2).actor.command);
        assertThrows(IllegalArgumentException.class, () -> settings.setLiveChannel(null, GUILD, 4L));
    }

    // --- /streamerlist formatting ---

    @Test
    void streamerListLinksUseLoginNotDisplayName() {
        List<ServerInfo.Streamer> streamers = new ArrayList<>();
        streamers.add(new ServerInfo.Streamer(0L, "55555555"));
        ServerInfo.Streamer bob = new ServerInfo.Streamer(202L, "22222222");
        bob.channelToPostLiveNotifications = 900L;
        streamers.add(bob);
        streamers.add(new ServerInfo.Streamer(0L, "99999999"));
        Map<String, JsonObject> users = new HashMap<>();
        users.put("55555555", user("55555555", "jp_streamer", "日本の配信者"));
        users.put("22222222", user("22222222", "bob", "Bob"));

        String list = TwitchCommandHandler.formatStreamerList(streamers, users);
        assertTrue(list.contains("<https://www.twitch.tv/jp_streamer>"), list);
        assertFalse(list.contains("twitch.tv/日本"), list);
        assertTrue(list.contains("[日本の配信者]"), list);
        assertTrue(list.contains("<https://www.twitch.tv/bob>"), list);
        assertTrue(list.contains("Posts in: <#900>"), list);
        assertTrue(list.contains("Unknown Twitch account (ID 99999999)"), list);
        // Sorted by display name, unknown accounts last.
        assertTrue(list.indexOf("Bob") < list.indexOf("日本の配信者"));
        assertTrue(list.indexOf("日本の配信者") < list.indexOf("Unknown Twitch account"));
    }

    @Test
    void emptyStreamerList() {
        assertTrue(TwitchCommandHandler.formatStreamerList(List.of(), Map.of()).contains("Nobody is here!"));
    }

    private static JsonObject user(String id, String login, String displayName) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("login", login);
        object.addProperty("display_name", displayName);
        return object;
    }

    @FunctionalInterface
    private interface Call {
        void run() throws Exception;
    }

    private static void assertMessage(String expected, Call call) {
        SettingsException e = assertThrows(SettingsException.class, call::run);
        assertEquals(expected, e.getMessage());
    }
}

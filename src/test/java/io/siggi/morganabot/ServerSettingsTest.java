package io.siggi.morganabot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.siggi.morganabot.ServerSettings.SettingsException;
import io.siggi.morganabot.ServerSettings.StreamerRef;
import io.siggi.morganabot.ServerSettings.TwitchUser;
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

    @TempDir
    Path tempDir;

    private ServerInfoStore store;
    private FakeTwitch twitch;
    private ServerSettings settings;

    private static final class FakeTwitch implements ServerSettings.Twitch {
        final Map<String, TwitchUser> users = new HashMap<>();
        final List<String> lookups = new ArrayList<>();
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
        settings = new ServerSettings(store, twitch);
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
        assertMessage(ServerSettings.NOT_ENABLED, () -> settings.addStreamer(GUILD, "dave", 0L, 0L));
        assertMessage(ServerSettings.NOT_ENABLED, () -> settings.deleteStreamer(GUILD, new StreamerRef("bob", 0L)));
        assertMessage(ServerSettings.NOT_ENABLED, () -> settings.setChannelOverride(GUILD, new StreamerRef("bob", 0L), 5L));
        assertMessage(ServerSettings.NOT_ENABLED, () -> settings.setLiveChannel(GUILD, 5L));
        assertMessage(ServerSettings.NOT_ENABLED, () -> settings.setLiveRole(GUILD, 5L));
        assertMessage(ServerSettings.NOT_ENABLED, () -> settings.setDeleteOnOffline(GUILD, true));
        assertMessage(ServerSettings.NOT_ENABLED, () -> settings.setNotificationCooldown(GUILD, 5L));
        assertMessage(ServerSettings.NOT_ENABLED, () -> settings.setServerTemplate(GUILD, "x"));
        assertMessage(ServerSettings.NOT_ENABLED, () -> settings.setStreamerTemplate(GUILD, new StreamerRef(null, 201L), "x"));
        assertMessage(ServerSettings.NOT_ENABLED, () -> settings.requireTwitchEnabled(GUILD));
        assertEquals(before, new Gson().toJson(store.get(GUILD)));
        assertEquals(0, twitch.updates);
        assertTrue(twitch.lookups.isEmpty(), "no Twitch lookups for a disabled server");
    }

    // --- addStreamer ---

    @Test
    void addStreamerStoresEntryAndTriggersUpdate() throws Exception {
        TwitchUser user = settings.addStreamer(GUILD, "Dave", 204L, 900L);
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
        SettingsException e = assertThrows(SettingsException.class, () -> settings.addStreamer(GUILD, "nobody_here", 0L, 0L));
        assertTrue(e.getMessage().contains("nobody_here"), e.getMessage());
        assertEquals(3, store.get(GUILD).streamers.size());
        assertEquals(0, twitch.updates);
    }

    @Test
    void addStreamerRejectsInvalidAndMissingNamesWithoutLookingThemUp() {
        assertThrows(SettingsException.class, () -> settings.addStreamer(GUILD, "https://twitch.tv/dave", 0L, 0L));
        assertThrows(SettingsException.class, () -> settings.addStreamer(GUILD, "", 0L, 0L));
        assertThrows(SettingsException.class, () -> settings.addStreamer(GUILD, null, 0L, 0L));
        assertTrue(twitch.lookups.isEmpty());
    }

    @Test
    void addStreamerRejectsDuplicates() {
        assertMessage("The specified Twitch user is already added as a streamer!", () -> settings.addStreamer(GUILD, "bob", 0L, 0L));
        assertMessage("The specified Discord user is already added as a streamer!", () -> settings.addStreamer(GUILD, "dave", 201L, 0L));
        assertEquals(3, store.get(GUILD).streamers.size());
        assertEquals(0, twitch.updates);
    }

    @Test
    void addingTwoUnlinkedStreamersIsAllowed() throws Exception {
        settings.addStreamer(GUILD, "dave", 0L, 0L);
        assertEquals(List.of("33333333", "11111111", "22222222", "44444444"), twitchIds());
    }

    // --- streamer lookup (getStreamer wrong-match bug) ---

    @Test
    void deleteByTwitchNameDeletesThatStreamerNotAnUnlinkedOne() throws Exception {
        ServerInfo.Streamer removed = settings.deleteStreamer(GUILD, new StreamerRef("bob", 0L));
        assertEquals("22222222", removed.twitchId);
        assertEquals(List.of("33333333", "11111111"), twitchIds());
        assertEquals(1, twitch.updates);
    }

    @Test
    void deleteByUnknownTwitchNameDeletesNothing() {
        // Used to match the first streamer with discordId 0.
        assertThrows(SettingsException.class, () -> settings.deleteStreamer(GUILD, new StreamerRef("nobody_here", 0L)));
        assertEquals(3, store.get(GUILD).streamers.size());
        assertEquals(0, twitch.updates);
    }

    @Test
    void deleteByTwitchNameNotInThisServerDeletesNothing() {
        assertMessage("No such streamer was found!", () -> settings.deleteStreamer(GUILD, new StreamerRef("dave", 0L)));
        assertEquals(3, store.get(GUILD).streamers.size());
    }

    @Test
    void deleteUnlinkedStreamerByTwitchName() throws Exception {
        settings.deleteStreamer(GUILD, new StreamerRef("carol", 0L));
        assertEquals(List.of("11111111", "22222222"), twitchIds());
    }

    @Test
    void deleteByDiscordUser() throws Exception {
        settings.deleteStreamer(GUILD, new StreamerRef(null, 201L));
        assertEquals(List.of("33333333", "22222222"), twitchIds());
        assertMessage("No such streamer was found!", () -> settings.deleteStreamer(GUILD, new StreamerRef(null, 999L)));
    }

    @Test
    void twitchNameAndDiscordUserMustAgree() throws Exception {
        assertMessage("That Twitch name and Discord user belong to different streamers!",
                () -> settings.deleteStreamer(GUILD, new StreamerRef("bob", 201L)));
        assertEquals(3, store.get(GUILD).streamers.size());
        settings.deleteStreamer(GUILD, new StreamerRef("bob", 202L));
        assertEquals(List.of("33333333", "11111111"), twitchIds());
    }

    @Test
    void emptyStreamerRefIsRejected() {
        assertMessage("You need to specify either their Twitch name or Discord name!",
                () -> settings.deleteStreamer(GUILD, new StreamerRef(null, 0L)));
    }

    // --- per-streamer settings ---

    @Test
    void setAndClearChannelOverride() throws Exception {
        ServerInfo.Streamer changed = settings.setChannelOverride(GUILD, new StreamerRef("carol", 0L), 777L);
        assertEquals(777L, changed.channelToPostLiveNotifications);
        assertEquals(777L, streamer("33333333").channelToPostLiveNotifications);
        assertEquals(0L, streamer("11111111").channelToPostLiveNotifications);
        // The returned copy isn't tied to the store.
        changed.channelToPostLiveNotifications = 1L;
        assertEquals(777L, streamer("33333333").channelToPostLiveNotifications);

        settings.setChannelOverride(GUILD, new StreamerRef(null, 201L), 0L);
        assertEquals(0L, streamer("11111111").channelToPostLiveNotifications);
        assertMessage("Invalid channel.", () -> settings.setChannelOverride(GUILD, new StreamerRef("carol", 0L), -1L));
    }

    @Test
    void setStreamerDiscordUser() throws Exception {
        settings.setStreamerDiscordUser(GUILD, new StreamerRef("carol", 0L), 203L);
        assertEquals(203L, streamer("33333333").discordId);
        assertMessage("The specified Discord user is already linked to another streamer!",
                () -> settings.setStreamerDiscordUser(GUILD, new StreamerRef("carol", 0L), 201L));
        settings.setStreamerDiscordUser(GUILD, new StreamerRef(null, 203L), 0L);
        assertEquals(0L, streamer("33333333").discordId);
    }

    @Test
    void streamerTemplate() throws Exception {
        settings.setStreamerTemplate(GUILD, new StreamerRef(null, 202L), "hi ${twitch_link}");
        assertEquals("hi ${twitch_link}", streamer("22222222").liveNotificationTemplate);
        settings.setStreamerTemplate(GUILD, new StreamerRef(null, 202L), null);
        assertNull(streamer("22222222").liveNotificationTemplate);
        assertMessage("No such streamer was found!", () -> settings.setStreamerTemplate(GUILD, new StreamerRef(null, 999L), "x"));
    }

    // --- server-wide settings ---

    @Test
    void serverWideSettings() throws Exception {
        settings.setLiveChannel(GUILD, 500L);
        settings.setLiveRole(GUILD, 600L);
        settings.setDeleteOnOffline(GUILD, true);
        settings.setNotificationCooldown(GUILD, 60_000L);
        settings.setServerTemplate(GUILD, "live: ${twitch_link}");
        ServerInfo info = store.get(GUILD);
        assertEquals(500L, info.channelToPostLiveNotifications);
        assertEquals(600L, info.roleForLiveUsers);
        assertTrue(info.deleteNotificationsOnOffline);
        assertEquals(60_000L, info.streamNotificationCooldown);
        assertEquals("live: ${twitch_link}", info.liveNotificationTemplate);

        settings.setServerTemplate(GUILD, null);
        assertNull(store.get(GUILD).liveNotificationTemplate);
        assertEquals(0, twitch.updates, "server-wide settings don't change subscriptions");
    }

    @Test
    void serverWideValidation() {
        assertMessage("The notification cooldown can't be negative.", () -> settings.setNotificationCooldown(GUILD, -1L));
        assertMessage("Invalid role.", () -> settings.setLiveRole(GUILD, -1L));
        assertMessage("The live message can't be empty.", () -> settings.setServerTemplate(GUILD, "  "));
    }

    @Test
    void failedChangeDoesNotWriteFile() {
        File file = store.getFile(GUILD);
        long modified = file.lastModified();
        long length = file.length();
        assertThrows(SettingsException.class, () -> settings.setChannelOverride(GUILD, new StreamerRef("dave", 0L), 5L));
        assertEquals(modified, file.lastModified());
        assertEquals(length, file.length());
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

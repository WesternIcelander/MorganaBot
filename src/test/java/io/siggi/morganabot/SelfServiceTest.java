package io.siggi.morganabot;

import com.google.gson.Gson;
import io.siggi.morganabot.ServerSettings.SelfServiceChannel;
import io.siggi.morganabot.ServerSettings.SelfServiceMember;
import io.siggi.morganabot.ServerSettings.SelfSetResult;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SelfServiceTest {

    private static final long GUILD = 100L;
    private static final Actor ADMIN = Actor.command(301L, "admin_user", "/test");

    // Roles, highest first in Discord's order: STAFF > VIP > MEMBER. OTHER isn't a self-service role.
    private static final long STAFF = 50L;
    private static final long VIP = 51L;
    private static final long MEMBER = 52L;
    private static final long OTHER = 53L;
    private static final long VIP_CHANNEL = 900L;
    private static final long MEMBER_CHANNEL = 901L;
    private static final long DEFAULT_CHANNEL = 800L;

    private static final long USER = 401L;
    private static final long DAY = 24L * 60L * 60L * 1000L;

    @TempDir
    Path tempDir;

    private ServerInfoStore store;
    private ServerSettings settings;
    private AuditLog auditLog;
    private FakeTwitch twitch;
    private final Map<Long, SelfServiceMember> members = new HashMap<>();
    private long now = 1_700_000_000_000L;

    private static final class FakeTwitch implements ServerSettings.Twitch {
        final Map<String, TwitchUser> users = new HashMap<>();
        final List<String> lookups = new ArrayList<>();
        int updates;

        void add(String id, String login) {
            users.put(login, new TwitchUser(id, login, login.toUpperCase(Locale.ROOT)));
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
        twitch.add("1001", "alice");
        twitch.add("1002", "bob");
        twitch.add("1003", "carol");
        auditLog = new AuditLog(tempDir.toFile(), new Gson(), store, AuditLog.Names.NONE);
        settings = new ServerSettings(store, twitch, auditLog);
        settings.setClock(() -> now);
        settings.setMembers((guildId, userId) -> {
            SelfServiceMember member = members.get(userId);
            return member == null ? SelfServiceMember.absent(guildId, userId) : member;
        });
        store.modify(GUILD, info -> {
            info.enabledTwitchTracking = true;
            info.channelToPostLiveNotifications = DEFAULT_CHANNEL;
            // List order is deliberately not the role order.
            info.selfServiceRoles.add(new ServerInfo.SelfServiceRole(MEMBER, MEMBER_CHANNEL));
            info.selfServiceRoles.add(new ServerInfo.SelfServiceRole(STAFF, 0L));
            info.selfServiceRoles.add(new ServerInfo.SelfServiceRole(VIP, VIP_CHANNEL));
            // An admin-added streamer, linked to user 402.
            info.streamers.add(new ServerInfo.Streamer(402L, "1002"));
            return null;
        });
    }

    private SelfServiceMember member(long userId, Long... roles) {
        SelfServiceMember member = new SelfServiceMember(GUILD, userId, "user" + userId, true, List.of(roles));
        members.put(userId, member);
        return member;
    }

    private static Actor self(long userId) {
        return Actor.selfService(userId, "user" + userId, "/settwitchchannel");
    }

    private ServerInfo.Streamer byDiscord(long userId) {
        for (ServerInfo.Streamer streamer : store.get(GUILD).streamers) {
            if (streamer.discordId == userId) return streamer;
        }
        return null;
    }

    private List<AuditEntry> audit() {
        return auditLog.read(GUILD, true);
    }

    // --- which role wins ---

    @Test
    void highestRoleByDiscordOrderWins() {
        ServerInfo info = store.get(GUILD);
        assertEquals(new SelfServiceChannel(VIP, VIP_CHANNEL), ServerSettings.resolveSelfServiceChannel(info, List.of(OTHER, VIP, MEMBER)));
        assertEquals(new SelfServiceChannel(MEMBER, MEMBER_CHANNEL), ServerSettings.resolveSelfServiceChannel(info, List.of(OTHER, MEMBER)));
    }

    @Test
    void highestRoleWinsEvenWithEmptyOverride() {
        // STAFF has no channel: the member gets the server default, not VIP's or MEMBER's channel.
        ServerInfo info = store.get(GUILD);
        assertEquals(new SelfServiceChannel(STAFF, 0L), ServerSettings.resolveSelfServiceChannel(info, List.of(STAFF, VIP, MEMBER)));
    }

    @Test
    void noSelfServiceRoleIsNotEligible() {
        ServerInfo info = store.get(GUILD);
        assertFalse(ServerSettings.resolveSelfServiceChannel(info, List.of(OTHER)).eligible());
        assertFalse(ServerSettings.resolveSelfServiceChannel(info, List.of()).eligible());
        info.selfServiceRoles = null; // hand-edited file
        assertFalse(ServerSettings.resolveSelfServiceChannel(info, List.of(VIP)).eligible());
    }

    // --- /settwitchchannel ---

    @Test
    void selfSetAddsEntryWithRoleChannel() throws Exception {
        SelfSetResult result = settings.selfSetTwitch(self(USER), member(USER, OTHER, VIP, MEMBER), "Alice");
        assertTrue(result.added());
        assertEquals(VIP, result.roleId());
        assertEquals(VIP_CHANNEL, result.channelId());
        ServerInfo.Streamer streamer = byDiscord(USER);
        assertNotNull(streamer);
        assertEquals("1001", streamer.twitchId);
        assertTrue(streamer.selfService);
        assertEquals(USER, streamer.addedBy);
        assertEquals(VIP_CHANNEL, streamer.channelToPostLiveNotifications);
        assertFalse(streamer.channelOverridePinned);
        assertEquals(0L, streamer.selfServiceLostAt);
        assertEquals(1, twitch.updates);

        AuditEntry entry = audit().get(0);
        assertEquals(AuditEntry.SELF_SERVICE_SET, entry.type);
        assertEquals(Actor.Source.SELF_SERVICE, entry.actor.source);
        assertEquals("/settwitchchannel", entry.actor.command);
        assertEquals("1001", entry.streamer.id);
    }

    @Test
    void selfSetWithoutRoleIsRefusedWithoutLookup() {
        String before = new Gson().toJson(store.get(GUILD));
        assertMessage(ServerSettings.NO_SELF_SERVICE_ROLE, () -> settings.selfSetTwitch(self(USER), member(USER, OTHER), "alice"));
        assertEquals(before, new Gson().toJson(store.get(GUILD)));
        assertTrue(twitch.lookups.isEmpty());
        assertTrue(audit().isEmpty());
    }

    @Test
    void selfSetWhenTwitchIsOffIsRefused() {
        store.modify(GUILD, info -> info.enabledTwitchTracking = false);
        assertMessage(settings.notEnabledMessage(), () -> settings.selfSetTwitch(self(USER), member(USER, VIP), "alice"));
        assertMessage(settings.notEnabledMessage(), () -> settings.selfRemove(self(USER), GUILD, USER));
        assertTrue(twitch.lookups.isEmpty());
    }

    @Test
    void selfSetWithUnknownTwitchNameSaysSo() {
        SettingsException e = assertThrows(SettingsException.class, () -> settings.selfSetTwitch(self(USER), member(USER, VIP), "nobody"));
        assertTrue(e.getMessage().contains("nobody"), e.getMessage());
        assertNull(byDiscord(USER));
    }

    @Test
    void twitchAccountClaimedBySomeoneElseIsRefused() {
        // bob (1002) was added by an admin for user 402.
        SettingsException e = assertThrows(SettingsException.class, () -> settings.selfSetTwitch(self(USER), member(USER, VIP), "bob"));
        assertTrue(e.getMessage().contains("ask an admin"), e.getMessage());
        assertNull(byDiscord(USER));
        assertEquals(0, twitch.updates);
    }

    @Test
    void selfSetReplacesExistingEntryAndKeepsTemplate() throws Exception {
        // 402 was added by an admin, with a pinned-looking channel and their own template.
        store.modify(GUILD, info -> {
            ServerInfo.Streamer streamer = info.streamers.get(0);
            streamer.channelToPostLiveNotifications = 777L;
            streamer.liveNotificationTemplate = "my own message";
            return null;
        });
        SelfSetResult result = settings.selfSetTwitch(self(402L), member(402L, MEMBER), "carol");
        assertFalse(result.added());
        ServerInfo info = store.get(GUILD);
        assertEquals(1, info.streamers.size());
        ServerInfo.Streamer streamer = info.streamers.get(0);
        assertEquals("1003", streamer.twitchId);
        assertEquals(MEMBER_CHANNEL, streamer.channelToPostLiveNotifications);
        assertEquals("my own message", streamer.liveNotificationTemplate);
        assertFalse(streamer.channelOverridePinned);
    }

    @Test
    void rerunningClearsPinAndPause() throws Exception {
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        store.modify(GUILD, info -> {
            for (ServerInfo.Streamer streamer : info.streamers) {
                if (streamer.discordId == USER) {
                    streamer.channelOverridePinned = true;
                    streamer.channelToPostLiveNotifications = 5L;
                }
            }
            return null;
        });
        now += 61_000L;
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        ServerInfo.Streamer streamer = byDiscord(USER);
        assertFalse(streamer.channelOverridePinned);
        assertEquals(VIP_CHANNEL, streamer.channelToPostLiveNotifications);
    }

    @Test
    void selfSetIsRateLimitedPerMember() throws Exception {
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        now += 30_000L;
        SettingsException e = assertThrows(SettingsException.class, () -> settings.selfSetTwitch(self(USER), member(USER, VIP), "carol"));
        assertTrue(e.getMessage().contains("once a minute"), e.getMessage());
        assertEquals(List.of("alice"), twitch.lookups);
        // Someone else isn't affected.
        settings.selfSetTwitch(self(403L), member(403L, VIP), "carol");
        now += 31_000L;
        assertThrows(SettingsException.class, () -> settings.selfSetTwitch(self(USER), member(USER, VIP), "carol")); // carol is taken now
        assertEquals("1001", byDiscord(USER).twitchId);
    }

    // --- /removetwitchchannel ---

    @Test
    void selfRemoveWorksWithoutRole() throws Exception {
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        member(USER, OTHER);
        ServerInfo.Streamer removed = settings.selfRemove(self(USER), GUILD, USER);
        assertEquals("1001", removed.twitchId);
        assertNull(byDiscord(USER));
        assertEquals(2, twitch.updates);
        assertEquals(AuditEntry.SELF_SERVICE_REMOVE, audit().get(0).type);
    }

    @Test
    void selfRemoveWithoutEntrySaysSo() {
        assertThrows(SettingsException.class, () -> settings.selfRemove(self(USER), GUILD, USER));
        assertEquals(0, twitch.updates);
    }

    // --- following roles ---

    @Test
    void losingRolePausesAndRegainingResumes() throws Exception {
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        int auditCount = audit().size();

        assertEquals(1, settings.reconcileSelfService(member(USER, OTHER)));
        ServerInfo.Streamer streamer = byDiscord(USER);
        assertEquals(now, streamer.selfServiceLostAt);
        assertTrue(streamer.isSelfServicePaused());
        AuditEntry pause = audit().get(0);
        assertEquals(AuditEntry.SELF_SERVICE_PAUSE, pause.type);
        assertEquals(Actor.Source.SYSTEM, pause.actor.source);

        // Repeated events change and audit nothing.
        now += 1000L;
        assertEquals(0, settings.reconcileSelfService(member(USER, OTHER)));
        assertEquals(now - 1000L, byDiscord(USER).selfServiceLostAt);
        assertEquals(auditCount + 1, audit().size());

        assertEquals(1, settings.reconcileSelfService(member(USER, VIP)));
        assertEquals(0L, byDiscord(USER).selfServiceLostAt);
        assertEquals(AuditEntry.SELF_SERVICE_RESUME, audit().get(0).type);
        assertEquals(auditCount + 2, audit().size());
    }

    @Test
    void leavingTheServerPauses() throws Exception {
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        settings.reconcileSelfService(SelfServiceMember.absent(GUILD, USER));
        assertTrue(byDiscord(USER).isSelfServicePaused());
        assertEquals("Left the server", audit().get(0).note);
    }

    @Test
    void channelFollowsHighestRoleUnlessPinned() throws Exception {
        settings.selfSetTwitch(self(USER), member(USER, MEMBER), "alice");
        assertEquals(MEMBER_CHANNEL, byDiscord(USER).channelToPostLiveNotifications);

        settings.reconcileSelfService(member(USER, VIP, MEMBER));
        assertEquals(VIP_CHANNEL, byDiscord(USER).channelToPostLiveNotifications);
        assertEquals(AuditEntry.STREAMER_CHANNEL_OVERRIDE, audit().get(0).type);

        settings.reconcileSelfService(member(USER, STAFF, VIP, MEMBER));
        assertEquals(0L, byDiscord(USER).channelToPostLiveNotifications);

        // An admin sets the channel by hand: it's pinned and stops following.
        settings.setChannelOverride(ADMIN, GUILD, new StreamerRef(null, USER), 555L);
        assertTrue(byDiscord(USER).channelOverridePinned);
        assertEquals(0, settings.reconcileSelfService(member(USER, MEMBER)));
        assertEquals(555L, byDiscord(USER).channelOverridePinned ? byDiscord(USER).channelToPostLiveNotifications : -1L);

        // Unpinning recalculates straight away from the current roles.
        ServerInfo.Streamer unpinned = settings.unpinChannel(ADMIN, GUILD, new StreamerRef(null, USER));
        assertFalse(unpinned.channelOverridePinned);
        assertEquals(MEMBER_CHANNEL, unpinned.channelToPostLiveNotifications);
        assertEquals(AuditEntry.STREAMER_CHANNEL_PIN, audit().get(0).type);
    }

    @Test
    void settingServerDefaultByHandAlsoPins() throws Exception {
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        settings.setChannelOverride(ADMIN, GUILD, new StreamerRef(null, USER), 0L);
        ServerInfo.Streamer streamer = byDiscord(USER);
        assertTrue(streamer.channelOverridePinned);
        assertEquals(0L, streamer.channelToPostLiveNotifications);
        assertEquals(0, settings.reconcileSelfService(member(USER, VIP)));
    }

    @Test
    void unpinIsOnlyForSelfAddedStreamers() {
        member(402L, VIP);
        assertThrows(SettingsException.class, () -> settings.unpinChannel(ADMIN, GUILD, new StreamerRef(null, 402L)));
    }

    @Test
    void adminAddedStreamersAreNeverTouched() {
        String before = new Gson().toJson(store.get(GUILD));
        assertEquals(0, settings.reconcileSelfService(member(402L, OTHER)));
        assertEquals(0, settings.reconcileSelfService(SelfServiceMember.absent(GUILD, 402L)));
        assertEquals(0, settings.reconcileSelfService(GUILD));
        assertEquals(before, new Gson().toJson(store.get(GUILD)));
        assertTrue(audit().isEmpty());
    }

    @Test
    void removingASelfServiceRolePausesItsMembers() throws Exception {
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        assertTrue(settings.removeSelfServiceRole(ADMIN, GUILD, VIP));
        assertTrue(byDiscord(USER).isSelfServicePaused());
        assertFalse(settings.removeSelfServiceRole(ADMIN, GUILD, VIP));

        // Adding it back resumes them through the full check.
        settings.addSelfServiceRole(ADMIN, GUILD, VIP, 333L);
        ServerInfo.Streamer streamer = byDiscord(USER);
        assertFalse(streamer.isSelfServicePaused());
        assertEquals(333L, streamer.channelToPostLiveNotifications);
    }

    @Test
    void addSelfServiceRoleUpdatesAnExistingRole() throws Exception {
        settings.addSelfServiceRole(ADMIN, GUILD, VIP, 0L);
        List<ServerInfo.SelfServiceRole> roles = store.get(GUILD).selfServiceRoles;
        assertEquals(3, roles.size());
        for (ServerInfo.SelfServiceRole role : roles) {
            if (role.roleId == VIP) assertEquals(0L, role.channelId);
        }
        AuditEntry entry = audit().get(0);
        assertEquals(AuditEntry.SELF_SERVICE_ROLES, entry.type);
        assertEquals(String.valueOf(VIP), entry.subject.id);
        assertThrows(SettingsException.class, () -> settings.addSelfServiceRole(ADMIN, GUILD, GUILD, 0L));
    }

    @Test
    void setSelfServiceRolesAuditsEachChangedRole() throws Exception {
        settings.setSelfServiceRoles(ADMIN, GUILD, List.of(
                new ServerInfo.SelfServiceRole(VIP, VIP_CHANNEL), // unchanged
                new ServerInfo.SelfServiceRole(STAFF, 7L),        // changed
                new ServerInfo.SelfServiceRole(OTHER, 0L)));      // added; MEMBER removed
        List<AuditEntry> entries = audit();
        assertEquals(3, entries.size());
        assertEquals(3, store.get(GUILD).selfServiceRoles.size());
        // Same list again: nothing.
        settings.setSelfServiceRoles(ADMIN, GUILD, store.get(GUILD).selfServiceRoles);
        assertEquals(3, audit().size());
    }

    // --- expiry ---

    @Test
    void pausedEntriesAreRemovedAfterTheConfiguredDays() throws Exception {
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        settings.reconcileSelfService(member(USER, OTHER));
        long lostAt = now;

        now = lostAt + 7L * DAY - 1L;
        assertTrue(settings.expireSelfService(GUILD).isEmpty());
        assertNotNull(byDiscord(USER));

        now = lostAt + 7L * DAY;
        List<ServerInfo.Streamer> removed = settings.expireSelfService(GUILD);
        assertEquals(1, removed.size());
        assertNull(byDiscord(USER));
        AuditEntry entry = audit().get(0);
        assertEquals(AuditEntry.STREAMER_REMOVE, entry.type);
        assertEquals(Actor.Source.SYSTEM, entry.actor.source);
        assertNotNull(entry.note);
        // The admin-added streamer stays.
        assertEquals(1, store.get(GUILD).streamers.size());
    }

    @Test
    void zeroDaysMeansNever() throws Exception {
        settings.setSelfServiceRemoveAfterDays(ADMIN, GUILD, 0);
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        settings.reconcileSelfService(member(USER, OTHER));
        now += 10_000L * DAY;
        assertTrue(settings.expireSelfService(GUILD).isEmpty());
        assertNotNull(byDiscord(USER));
    }

    @Test
    void expiryDaysAreValidatedAndAudited() throws Exception {
        assertThrows(SettingsException.class, () -> settings.setSelfServiceRemoveAfterDays(ADMIN, GUILD, -1));
        assertThrows(SettingsException.class, () -> settings.setSelfServiceRemoveAfterDays(ADMIN, GUILD, ServerSettings.MAX_REMOVE_AFTER_DAYS + 1));
        settings.setSelfServiceRemoveAfterDays(ADMIN, GUILD, 3);
        assertEquals(3, store.get(GUILD).selfServiceRemoveAfterDays);
        assertEquals(AuditEntry.SELF_SERVICE_EXPIRY, audit().get(0).type);
    }

    @Test
    void startupCheckCatchesUpAndDoesNotRepeat() throws Exception {
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        member(USER, OTHER); // role removed while the bot was offline
        assertEquals(1, settings.reconcileSelfService(GUILD));
        int count = audit().size();
        assertEquals(0, settings.reconcileSelfService(GUILD));
        assertEquals(count, audit().size());
    }

    @Test
    void membersThatCantBeLookedUpAreLeftAlone() throws Exception {
        settings.selfSetTwitch(self(USER), member(USER, VIP), "alice");
        settings.setMembers(ServerSettings.Members.NONE);
        assertEquals(0, settings.reconcileSelfService(GUILD));
        assertFalse(byDiscord(USER).isSelfServicePaused());
    }

    // --- go-live ---

    @Test
    void pausedStreamersAreSkippedOnGoLiveOnly() {
        ServerInfo.Streamer streamer = new ServerInfo.Streamer(USER, "1001");
        streamer.selfService = true;
        assertFalse(TwitchStreamerWatcher.skip(streamer, "1001", true));
        streamer.selfServiceLostAt = now;
        assertTrue(TwitchStreamerWatcher.skip(streamer, "1001", true));
        assertFalse(TwitchStreamerWatcher.skip(streamer, "1001", false), "offline still cleans up");
        // An admin-added streamer with a stray timestamp isn't paused.
        streamer.selfService = false;
        assertFalse(TwitchStreamerWatcher.skip(streamer, "1001", true));
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

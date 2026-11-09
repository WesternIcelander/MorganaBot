package io.siggi.morganabot.diagnose;

import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.diagnose.Check.Level;
import io.siggi.morganabot.diagnose.GuildView.ChannelKind;
import io.siggi.morganabot.diagnose.GuildView.ChannelView;
import io.siggi.morganabot.diagnose.GuildView.RoleView;
import io.siggi.morganabot.twitch.SubscriptionNotes;
import net.dv8tion.jda.api.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiagnoseTest {

    private static final long LIVE = 10L;
    private static final long VIP = 11L;
    private static final long VOICE = 12L;
    private static final long LIVE_ROLE = 20L;
    private static final long PING_ROLE = 21L;
    private static final long QUIET_ROLE = 22L;

    private static final Set<Permission> POST = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
            Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY);

    private FakeGuild guild;
    private ServerInfo info;
    private FakeTwitch twitch;

    static final class FakeGuild implements GuildView {
        final Map<Long, ChannelView> channels = new HashMap<>();
        final Map<Long, RoleView> roles = new HashMap<>();
        final Set<Long> members = new HashSet<>();
        final Set<Permission> guildPermissions = EnumSet.of(Permission.MANAGE_ROLES);
        final Set<Long> unknownMembers = new HashSet<>();

        @Override
        public String botName() {
            return "MorganaBot";
        }

        @Override
        public boolean botHasPermission(Permission permission) {
            return guildPermissions.contains(permission);
        }

        @Override
        public ChannelView channel(long channelId) {
            return channels.get(channelId);
        }

        @Override
        public RoleView role(long roleId) {
            return roles.get(roleId);
        }

        @Override
        public Boolean isMember(long userId) {
            if (unknownMembers.contains(userId)) return null;
            return members.contains(userId);
        }

        void channel(long id, String name, ChannelKind kind, Set<Permission> permissions) {
            channels.put(id, new ChannelView(id, name, kind, permissions));
        }
    }

    static final class FakeTwitch implements Diagnose.TwitchInfo {
        final Map<String, List<SubscriptionNotes.Note>> notes = new HashMap<>();

        @Override
        public Map<String, String> logins(Collection<String> twitchIds) {
            Map<String, String> logins = new HashMap<>();
            for (String id : twitchIds) logins.put(id, "login" + id);
            return logins;
        }

        @Override
        public List<SubscriptionNotes.Note> subscriptionNotes(String twitchId) {
            return notes.getOrDefault(twitchId, List.of());
        }
    }

    @BeforeEach
    void setUp() {
        guild = new FakeGuild();
        guild.channel(LIVE, "live", ChannelKind.TEXT, POST);
        guild.channel(VIP, "vip-live", ChannelKind.ANNOUNCEMENT, POST);
        guild.channel(VOICE, "voice", ChannelKind.OTHER, POST);
        guild.roles.put(LIVE_ROLE, new RoleView(LIVE_ROLE, "Live", false, false, false, true));
        guild.roles.put(PING_ROLE, new RoleView(PING_ROLE, "Pings", true, false, false, true));
        guild.roles.put(QUIET_ROLE, new RoleView(QUIET_ROLE, "Quiet", false, false, false, true));
        guild.members.add(100L);
        info = new ServerInfo();
        info.enabledTwitchTracking = true;
        info.channelToPostLiveNotifications = LIVE;
        twitch = new FakeTwitch();
    }

    private ServerInfo.Streamer addStreamer(String twitchId, long discordId, long channel) {
        ServerInfo.Streamer streamer = new ServerInfo.Streamer(discordId, twitchId);
        streamer.channelToPostLiveNotifications = channel;
        info.streamers.add(streamer);
        return streamer;
    }

    private DiagnoseResult run() {
        return Diagnose.run(1L, guild, info, twitch, null, 1_700_000_000_000L);
    }

    private static Check find(List<Check> checks, String code) {
        for (Check check : checks) {
            if (check.code.equals(code)) return check;
        }
        return null;
    }

    private static List<Check> checks(DiagnoseResult result, String twitchId) {
        return result.streamer(twitchId).checks;
    }

    @Test
    void healthySetupIsAllOk() {
        addStreamer("1", 100L, 0L);
        info.roleForLiveUsers = LIVE_ROLE;
        DiagnoseResult result = run();
        assertEquals(Level.OK, result.serverLevel, result.server.toString());
        DiagnoseResult.StreamerResult streamer = result.streamer("1");
        assertEquals(Level.OK, streamer.level, streamer.checks.toString());
        assertEquals("#live (server default)", streamer.channel);
        assertEquals(LIVE, streamer.channelId);
        assertEquals("login1", streamer.twitchLogin);
        assertNotNull(find(streamer.checks, "liveRole"));
        assertEquals("1 streamer checked: 1 OK, 0 warnings, 0 errors", result.summary);
    }

    @Test
    void twitchOffComesFirst() {
        info.enabledTwitchTracking = false;
        DiagnoseResult result = run();
        assertEquals("twitch.enabled", result.server.get(0).code);
        assertEquals(Level.ERROR, result.server.get(0).level);
        assertFalse(result.twitchEnabled);
    }

    @Test
    void overrideChannelIsUsedAndLabelled() {
        addStreamer("1", 0L, VIP);
        DiagnoseResult.StreamerResult streamer = run().streamer("1");
        assertEquals("#vip-live (streamer's own channel)", streamer.channel);
        assertEquals(Level.OK, streamer.level, streamer.checks.toString());
        assertTrue(find(streamer.checks, "channel.permissions").message.contains("announcement"));
    }

    @Test
    void missingSendPermissionIsAnErrorWithFixHint() {
        guild.channel(LIVE, "live", ChannelKind.TEXT, EnumSet.of(Permission.VIEW_CHANNEL));
        addStreamer("1", 0L, 0L);
        addStreamer("2", 0L, VIP);
        DiagnoseResult result = run();
        Check server = find(result.server, "defaultChannel.permissions");
        assertEquals(Level.ERROR, server.level);
        Check check = find(checks(result, "1"), "channel.permissions");
        assertEquals(Level.ERROR, check.level);
        assertEquals("Give MorganaBot *Send Messages* in #live, or pick another default live channel.", check.fix);
        // Only the streamer posting there is affected.
        assertEquals(Level.OK, result.streamer("2").level);
        assertEquals("2 streamers checked: 1 OK, 0 warnings, 1 error", result.summary);
        assertEquals(1, result.streamersWithErrors);
        assertEquals(1, result.streamersOk);
    }

    @Test
    void channelMissingOrWrongType() {
        addStreamer("1", 0L, 999L);
        addStreamer("2", 0L, VOICE);
        DiagnoseResult result = run();
        assertEquals(Level.ERROR, find(checks(result, "1"), "channel.exists").level);
        assertTrue(result.streamer("1").channel.startsWith("deleted channel 999"));
        assertEquals(Level.ERROR, find(checks(result, "2"), "channel.type").level);
        // The default channel is fine but unused: no error.
        assertEquals(Level.OK, result.serverLevel);
    }

    @Test
    void noChannelAtAll() {
        info.channelToPostLiveNotifications = 0L;
        addStreamer("1", 0L, 0L);
        DiagnoseResult result = run();
        assertEquals(Level.ERROR, find(result.server, "defaultChannel.set").level);
        assertEquals(Level.ERROR, find(checks(result, "1"), "channel.set").level);
        assertEquals("no channel", result.streamer("1").channel);
    }

    @Test
    void brokenDefaultChannelUnusedIsOnlyAWarning() {
        info.channelToPostLiveNotifications = 999L;
        addStreamer("1", 0L, VIP);
        DiagnoseResult result = run();
        assertEquals(Level.WARNING, find(result.server, "defaultChannel.exists").level);
    }

    @Test
    void missingEmbedLinksWarns() {
        guild.channel(LIVE, "live", ChannelKind.TEXT, EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND));
        addStreamer("1", 0L, 0L);
        assertEquals(Level.WARNING, find(checks(run(), "1"), "channel.embeds").level);
    }

    @Test
    void mentionChecks() {
        ServerInfo.Streamer streamer = addStreamer("1", 0L, 0L);
        streamer.liveNotificationTemplate = "@everyone @here <@&" + PING_ROLE + "> <@&" + QUIET_ROLE + "> <@&777> <@100> <@200> live!";
        List<Check> checks = checks(run(), "1");
        assertEquals(Level.WARNING, find(checks, "mention.everyone").level);
        assertTrue(find(checks, "mention.everyone").message.contains("their own live message"));
        assertEquals(Level.WARNING, find(checks, "mention.here").level);
        List<Check> roles = checks.stream().filter(c -> c.code.equals("mention.role")).toList();
        assertEquals(3, roles.size());
        assertEquals(Level.OK, roles.get(0).level); // mentionable
        assertEquals(Level.WARNING, roles.get(1).level); // not mentionable, no Mention Everyone
        assertTrue(roles.get(2).message.contains("doesn't exist"));
        List<Check> users = checks.stream().filter(c -> c.code.equals("mention.user")).toList();
        assertEquals(Level.OK, users.get(0).level);
        assertEquals(Level.WARNING, users.get(1).level);

        // Mention Everyone covers @everyone, @here and every role.
        Set<Permission> withEveryone = EnumSet.copyOf(POST);
        withEveryone.add(Permission.MESSAGE_MENTION_EVERYONE);
        guild.channel(LIVE, "live", ChannelKind.TEXT, withEveryone);
        checks = checks(run(), "1");
        assertEquals(Level.OK, find(checks, "mention.everyone").level);
        assertEquals(Level.OK, find(checks, "mention.here").level);
        roles = checks.stream().filter(c -> c.code.equals("mention.role")).toList();
        assertEquals(Level.OK, roles.get(1).level);
    }

    @Test
    void serverTemplateIsUsedWithoutOwnTemplate() {
        info.liveNotificationTemplate = "@everyone ${streamer_name}";
        addStreamer("1", 0L, 0L);
        Check check = find(checks(run(), "1"), "mention.everyone");
        assertTrue(check.message.contains("the server-wide live message"));
    }

    @Test
    void discordNameWithoutLinkedUser() {
        info.liveNotificationTemplate = "${discord_name} is live";
        addStreamer("1", 0L, 0L);
        assertEquals(Level.WARNING, find(checks(run(), "1"), "template.discordName").level);
    }

    @Test
    void liveRoleChecks() {
        info.roleForLiveUsers = LIVE_ROLE;
        addStreamer("1", 0L, 0L);
        addStreamer("2", 100L, 0L);
        addStreamer("3", 300L, 0L);
        DiagnoseResult result = run();
        assertEquals("No Discord user is linked, so no live role will be given.", find(checks(result, "1"), "liveRole.user").message);
        assertEquals(Level.OK, find(checks(result, "2"), "liveRole").level);
        assertEquals(Level.WARNING, find(checks(result, "3"), "liveRole.user").level);

        // Above the bot, and no Manage Roles.
        guild.roles.put(LIVE_ROLE, new RoleView(LIVE_ROLE, "Live", false, false, false, false));
        guild.guildPermissions.clear();
        result = run();
        assertEquals(Level.WARNING, find(result.server, "liveRole.hierarchy").level);
        assertEquals(Level.WARNING, find(result.server, "liveRole.permission").level);
        assertEquals(Level.WARNING, find(checks(result, "2"), "liveRole.hierarchy").level);
        assertTrue(find(checks(result, "2"), "liveRole.hierarchy").message.startsWith("Won't get @Live."));

        // Deleted.
        guild.roles.remove(LIVE_ROLE);
        result = run();
        assertEquals(Level.WARNING, find(result.server, "liveRole.exists").level);
        assertEquals(Level.WARNING, find(checks(result, "2"), "liveRole.exists").level);
        assertEquals(Level.WARNING, result.serverLevel);
    }

    @Test
    void memberLookupFailureIsReported() {
        info.roleForLiveUsers = LIVE_ROLE;
        guild.unknownMembers.add(100L);
        addStreamer("1", 100L, 0L);
        assertTrue(find(checks(run(), "1"), "liveRole.user").message.startsWith("Couldn't check"));
    }

    @Test
    void deleteOnOfflineNeedsHistory() {
        info.deleteNotificationsOnOffline = true;
        addStreamer("1", 0L, 0L);
        assertEquals(Level.OK, find(checks(run(), "1"), "deleteOnOffline").level);
        guild.channel(LIVE, "live", ChannelKind.TEXT, EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS));
        Check check = find(checks(run(), "1"), "deleteOnOffline");
        assertEquals(Level.WARNING, check.level);
        assertTrue(check.message.contains("Read Message History"));
    }

    @Test
    void templateLength() {
        ServerInfo.Streamer streamer = addStreamer("1", 0L, 0L);
        streamer.liveNotificationTemplate = "x".repeat(1800) + " ${stream_name}";
        assertNull(find(checks(run(), "1"), "template.length"));
        streamer.liveNotificationTemplate = "x".repeat(1900) + " ${stream_name}";
        assertEquals(Level.ERROR, find(checks(run(), "1"), "template.length").level);
        assertEquals(140, Diagnose.EXAMPLE_TITLE.length());
    }

    @Test
    void subscriptionNotes() {
        addStreamer("1", 0L, 0L);
        addStreamer("2", 0L, 0L);
        twitch.notes.put("1", List.of(new SubscriptionNotes.Note(SubscriptionNotes.Kind.DISABLED, "stream.online",
                "notification_failures_exceeded", 1_700_000_000_000L)));
        twitch.notes.put("2", List.of(new SubscriptionNotes.Note(SubscriptionNotes.Kind.CREATE_FAILED, "stream.online",
                "HTTP 429", 1_700_000_000_000L)));
        DiagnoseResult result = run();
        Check disabled = find(checks(result, "1"), "twitch.subscription");
        assertEquals(Level.WARNING, disabled.level);
        assertTrue(disabled.message.contains("2023-11-14"));
        assertTrue(disabled.message.contains("notification_failures_exceeded"));
        assertEquals(Level.ERROR, find(checks(result, "2"), "twitch.subscription").level);
    }

    @Test
    void onlyOneStreamer() {
        addStreamer("1", 0L, 0L);
        addStreamer("2", 0L, 0L);
        DiagnoseResult result = Diagnose.run(1L, guild, info, twitch, "2", 0L);
        assertEquals(1, result.streamers.size());
        assertEquals("2", result.streamers.get(0).twitchId);
        assertFalse(result.server.isEmpty());
    }

    @Test
    void doesNotChangeServerInfo() {
        ServerInfo.Streamer streamer = addStreamer("1", 100L, 0L);
        streamer.liveNotificationTemplate = "hi";
        info.roleForLiveUsers = LIVE_ROLE;
        run();
        assertEquals("hi", streamer.liveNotificationTemplate);
        assertEquals(0L, streamer.channelToPostLiveNotifications);
        assertEquals(1, info.streamers.size());
    }

    @Test
    void summaryWording() {
        assertEquals("0 streamers checked: 0 OK, 0 warnings, 0 errors", run().summary);
        assertEquals("streamers", run().server.get(run().server.size() - 1).code);
    }
}

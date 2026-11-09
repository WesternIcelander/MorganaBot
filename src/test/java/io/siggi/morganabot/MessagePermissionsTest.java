package io.siggi.morganabot;

import com.google.gson.Gson;
import io.siggi.morganabot.Access.MemberView;
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
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 8: admin roles, message customization rules and the save-time mention check.
 */
class MessagePermissionsTest {

    private static final long GUILD = 100L;
    private static final long ADMIN_ROLE = 10L;
    private static final long VIP = 11L;
    private static final long FAN = 12L;
    private static final long PING_A = 20L;
    private static final long PING_B = 21L;
    private static final long STREAMER = 201L;
    private static final long OTHER_STREAMER = 202L;
    private static final long SUPER = 999L;
    private static final Actor ADMIN_ACTOR = Actor.command(301L, "admin", "/test");

    @TempDir
    Path tempDir;

    private ServerInfoStore store;
    private ServerSettings settings;
    private AuditLog auditLog;

    @BeforeEach
    void setUp() {
        store = new ServerInfoStore(tempDir.toFile(), new Gson());
        auditLog = new AuditLog(tempDir.toFile(), new Gson(), store, new AuditLog.Names() {
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
                return "login" + twitchId;
            }
        });
        settings = new ServerSettings(store, new ServerSettings.Twitch() {
            @Override
            public TwitchUser findUserByLogin(String login) {
                return login.equals("alice") ? new TwitchUser("1", "alice", "Alice") : null;
            }

            @Override
            public void triggerUpdate() {
            }
        }, auditLog);
        settings.setAccess(new Access(store, List.of(SUPER)));
        store.modify(GUILD, info -> {
            info.enabledTwitchTracking = true;
            info.adminRoles.add(ADMIN_ROLE);
            info.streamers.add(new ServerInfo.Streamer(STREAMER, "1"));
            info.streamers.add(new ServerInfo.Streamer(OTHER_STREAMER, "2"));
            info.messageCustomizationRules.add(new ServerInfo.MessageCustomizationRule(VIP, false, List.of(PING_A)));
            info.messageCustomizationRules.add(new ServerInfo.MessageCustomizationRule(FAN, true, List.of()));
            return null;
        });
    }

    private static MemberView member(long userId, boolean administrator, Long... roles) {
        return new MemberView(GUILD, userId, administrator, List.of(roles));
    }

    private static Actor self(long userId) {
        return Actor.dashboard(userId, "user" + userId);
    }

    private ServerInfo.Streamer streamer(long discordId) {
        for (ServerInfo.Streamer s : store.get(GUILD).streamers) {
            if (s.discordId == discordId) return s;
        }
        return null;
    }

    /**
     * The audit entries, oldest first.
     */
    private List<AuditEntry> audit() {
        List<AuditEntry> entries = new java.util.ArrayList<>(auditLog.read(GUILD, true));
        java.util.Collections.reverse(entries);
        return entries;
    }

    private static void assertMessage(String expected, Executable executable) {
        SettingsException e = assertThrows(SettingsException.class, executable);
        assertEquals(expected, e.getMessage());
    }

    private static void assertRefused(String mentions, Executable executable) {
        SettingsException e = assertThrows(SettingsException.class, executable);
        assertEquals("Your message contains mentions you aren't allowed to use: " + mentions + ". Remove them and save again.", e.getMessage());
    }

    // --- setOwnTemplate ---

    @Test
    void streamerWithRuleSavesAllowedMentions() throws Exception {
        ServerInfo.Streamer saved = settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), "Live! <@&" + PING_A + "> ${twitch_link}");
        assertEquals("Live! <@&20> ${twitch_link}", saved.liveNotificationTemplate);
        assertEquals(STREAMER, streamer(STREAMER).templateSetBy);
        AuditEntry entry = audit().get(audit().size() - 1);
        assertEquals(AuditEntry.STREAMER_TEMPLATE, entry.type);
        assertEquals("1", entry.streamer.id);
        assertEquals(Actor.Source.DASHBOARD, entry.actor.source);
    }

    @Test
    void noRuleNoEditing() {
        assertMessage("You don't have a role that lets you write your own live message. Ask an admin.",
                () -> settings.setOwnTemplate(self(STREAMER), member(STREAMER, false), "hello"));
        assertNull(streamer(STREAMER).liveNotificationTemplate);
    }

    @Test
    void noEntryNoEditing() {
        assertMessage("You don't have a streamer entry in this server.",
                () -> settings.setOwnTemplate(self(300L), member(300L, false, VIP), "hello"));
    }

    @Test
    void disallowedMentionsAreNamedAndNothingChanges() {
        assertRefused("@everyone, @here, <@&21>, <@555>",
                () -> settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP),
                        "@everyone @here <@&20> <@&21> <@555> <@!555> <@" + STREAMER + "> ${discord_name}"));
        assertNull(streamer(STREAMER).liveNotificationTemplate);
        assertTrue(audit().isEmpty());
    }

    @Test
    void rulesCombine() throws Exception {
        settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP, FAN), "@everyone @here <@&20>");
        assertEquals("@everyone @here <@&20>", streamer(STREAMER).liveNotificationTemplate);
    }

    @Test
    void mentionsInTheOwnPreviousMessageCanBeKept() throws Exception {
        // An admin put mentions in this streamer's message.
        settings.setStreamerTemplate(ADMIN_ACTOR, GUILD, new StreamerRef(null, STREAMER), "@everyone <@&21> <@555> hi");
        assertEquals(301L, streamer(STREAMER).templateSetBy);
        // The streamer can edit around them...
        settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), "<@555> hello <@&21> @everyone");
        assertEquals(STREAMER, streamer(STREAMER).templateSetBy);
        // ...but not add new ones.
        assertRefused("@here", () -> settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), "@everyone @here"));
        // Once removed, a kept mention can't come back.
        settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), "just text");
        assertRefused("@everyone", () -> settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), "@everyone"));
    }

    @Test
    void withoutOwnMessageTheServerWideOneCounts() throws Exception {
        settings.setServerTemplate(ADMIN_ACTOR, GUILD, "<@&21> ${streamer_name} is live");
        settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), "<@&21> I'm live");
        // The other streamer's own message doesn't count for this one.
        settings.setStreamerTemplate(ADMIN_ACTOR, GUILD, new StreamerRef(null, OTHER_STREAMER), "@here");
        assertRefused("@here", () -> settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), "@here"));
    }

    @Test
    void adminsAndSuperAdminsArentChecked() throws Exception {
        settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, ADMIN_ROLE), "@everyone <@&99> <@555>");
        assertEquals("@everyone <@&99> <@555>", streamer(STREAMER).liveNotificationTemplate);
        settings.setOwnTemplate(self(OTHER_STREAMER), new MemberView(GUILD, OTHER_STREAMER, true, List.of()), "@here");
        assertEquals("@here", streamer(OTHER_STREAMER).liveNotificationTemplate);
    }

    @Test
    void lostRuleKeepsTemplateButCantEdit() throws Exception {
        settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), "<@&20> live");
        settings.removeMessageRule(ADMIN_ACTOR, GUILD, VIP);
        assertEquals("<@&20> live", streamer(STREAMER).liveNotificationTemplate);
        assertThrows(SettingsException.class, () -> settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), "x"));
    }

    @Test
    void resetToServerWide() throws Exception {
        settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), "mine");
        settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), null);
        assertNull(streamer(STREAMER).liveNotificationTemplate);
        assertEquals(0L, streamer(STREAMER).templateSetBy);
    }

    @Test
    void lengthLimit() {
        String tooLong = "x".repeat(LiveMessage.MAX_LENGTH - 10) + "${stream_name}";
        SettingsException e = assertThrows(SettingsException.class,
                () -> settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), tooLong));
        assertTrue(e.getMessage().startsWith("The live message is too long"), e.getMessage());
        // Admin saves have the limit too.
        assertThrows(SettingsException.class, () -> settings.setServerTemplate(ADMIN_ACTOR, GUILD, tooLong));
        assertDoesNotThrow(() -> settings.setServerTemplate(ADMIN_ACTOR, GUILD, "x".repeat(1500) + "${stream_name}"));
    }

    @Test
    void disallowedMentionsHelper() {
        Access.MentionPermissions none = Access.MentionPermissions.NONE;
        assertEquals(List.of(), ServerSettings.disallowedMentions(Mentions.parse("<@5>"), Mentions.parse(null), none, 5L));
        assertEquals(List.of("<@6>"), ServerSettings.disallowedMentions(Mentions.parse("<@6> <@6>"), Mentions.parse(null), none, 5L));
        assertEquals(List.of(), ServerSettings.disallowedMentions(Mentions.parse("<@6> <@6>"), Mentions.parse("<@!6>"), none, 5L));
        assertEquals(List.of(), ServerSettings.disallowedMentions(Mentions.parse("@everyone <@&1> <@2>"), Mentions.parse(null),
                Access.MentionPermissions.ALL, 5L));
    }

    // --- admin roles ---

    @Test
    void onlyAdministratorsChangeAdminRoles() throws Exception {
        String refused = "Only members with Discord's Administrator permission can change the admin roles.";
        assertMessage(refused, () -> settings.addAdminRole(ADMIN_ACTOR, member(301L, false, ADMIN_ROLE), 50L));
        assertMessage(refused, () -> settings.removeAdminRole(ADMIN_ACTOR, member(301L, false, ADMIN_ROLE), ADMIN_ROLE));
        assertMessage(refused, () -> settings.setAdminRoles(ADMIN_ACTOR, member(301L, false, ADMIN_ROLE), List.of()));
        assertEquals(List.of(ADMIN_ROLE), store.get(GUILD).adminRoles);

        assertTrue(settings.addAdminRole(ADMIN_ACTOR, member(301L, true), 50L));
        assertFalse(settings.addAdminRole(ADMIN_ACTOR, member(301L, true), 50L));
        assertTrue(settings.addAdminRole(Actor.superAdmin(SUPER, "boss"), MemberView.outsider(GUILD, SUPER), 51L));
        assertEquals(List.of(ADMIN_ROLE, 50L, 51L), store.get(GUILD).adminRoles);
        assertTrue(settings.removeAdminRole(ADMIN_ACTOR, member(301L, true), 50L));
        assertFalse(settings.removeAdminRole(ADMIN_ACTOR, member(301L, true), 50L));
        assertEquals(List.of(ADMIN_ROLE, 51L), store.get(GUILD).adminRoles);

        List<AuditEntry> entries = audit();
        assertEquals(3, entries.size());
        assertEquals(AuditEntry.ADMIN_ROLES, entries.get(0).type);
        assertEquals("50", entries.get(0).subject.id);
        assertNull(entries.get(0).changes.get(0).oldValue);
        assertEquals("50", entries.get(0).changes.get(0).newValue.id);
        assertEquals(Actor.Source.SUPER_ADMIN, entries.get(1).actor.source);
        assertNull(entries.get(2).changes.get(0).newValue);
    }

    @Test
    void everyoneCantBeAnAdminRole() {
        assertMessage("The @everyone role can't be an admin role.", () -> settings.addAdminRole(ADMIN_ACTOR, member(301L, true), GUILD));
        assertMessage("The @everyone role can't be an admin role.", () -> settings.setAdminRoles(ADMIN_ACTOR, member(301L, true), List.of(GUILD)));
    }

    @Test
    void setAdminRolesAuditsEachRole() throws Exception {
        settings.setAdminRoles(ADMIN_ACTOR, member(301L, true), List.of(60L, 61L, 60L));
        assertEquals(List.of(60L, 61L), store.get(GUILD).adminRoles);
        assertEquals(3, audit().size()); // 10 removed, 60 and 61 added
        settings.setAdminRoles(ADMIN_ACTOR, member(301L, true), List.of(61L, 60L));
        assertEquals(3, audit().size());
    }

    @Test
    void adminRolesDontNeedTwitch() throws Exception {
        store.modify(GUILD, info -> info.enabledTwitchTracking = false);
        assertTrue(settings.addAdminRole(ADMIN_ACTOR, member(301L, true), 50L));
        assertMessage(settings.notEnabledMessage(), () -> settings.addMessageRule(ADMIN_ACTOR, GUILD, 70L, false, List.of()));
        assertMessage(settings.notEnabledMessage(), () -> settings.setOwnTemplate(self(STREAMER), member(STREAMER, false, VIP), "x"));
    }

    // --- message rules ---

    @Test
    void messageRulesAddReplaceRemove() throws Exception {
        settings.addMessageRule(ADMIN_ACTOR, GUILD, 70L, false, List.of(PING_A, PING_A));
        ServerInfo.MessageCustomizationRule rule = store.get(GUILD).messageCustomizationRules.get(2);
        assertEquals(70L, rule.roleId);
        assertEquals(List.of(PING_A), rule.allowedMentionRoles);

        settings.addMessageRule(ADMIN_ACTOR, GUILD, 70L, true, List.of(PING_B));
        assertEquals(3, store.get(GUILD).messageCustomizationRules.size());
        rule = store.get(GUILD).messageCustomizationRules.get(2);
        assertTrue(rule.allowEveryone);
        assertEquals(List.of(PING_B), rule.allowedMentionRoles);

        AuditEntry replaced = audit().get(1);
        assertEquals(AuditEntry.MESSAGE_RULES, replaced.type);
        assertEquals("70", replaced.subject.id);
        assertEquals(3, replaced.changes.size()); // allowEveryone, -PING_A, +PING_B

        // Same values again: nothing recorded.
        settings.addMessageRule(ADMIN_ACTOR, GUILD, 70L, true, List.of(PING_B));
        assertEquals(2, audit().size());

        assertTrue(settings.removeMessageRule(ADMIN_ACTOR, GUILD, 70L));
        assertFalse(settings.removeMessageRule(ADMIN_ACTOR, GUILD, 70L));
        assertEquals(2, store.get(GUILD).messageCustomizationRules.size());
        assertEquals(3, audit().size());
    }

    @Test
    void messageRuleValidation() {
        assertMessage("Invalid role.", () -> settings.addMessageRule(ADMIN_ACTOR, GUILD, 0L, false, List.of()));
        assertMessage("Use allow-everyone to let members mention @everyone and @here.",
                () -> settings.addMessageRule(ADMIN_ACTOR, GUILD, 70L, false, List.of(GUILD)));
    }

    @Test
    void setMessageRulesReplacesAll() throws Exception {
        settings.setMessageRules(ADMIN_ACTOR, GUILD, List.of(
                new ServerInfo.MessageCustomizationRule(VIP, false, List.of(PING_A)),
                new ServerInfo.MessageCustomizationRule(80L, true, List.of())));
        List<ServerInfo.MessageCustomizationRule> rules = store.get(GUILD).messageCustomizationRules;
        assertEquals(2, rules.size());
        assertEquals(VIP, rules.get(0).roleId);
        assertEquals(80L, rules.get(1).roleId);
        // VIP unchanged, FAN removed, 80 added.
        assertEquals(2, audit().size());
    }

    @Test
    void parseRoleList() {
        assertEquals(List.of(), TwitchCommandHandler.parseRoleList(null));
        assertEquals(List.of(5L, 6L), TwitchCommandHandler.parseRoleList("<@&5>, <@&6> 5"));
        assertEquals(List.of(7L), TwitchCommandHandler.parseRoleList(" 7 "));
        assertThrows(IllegalArgumentException.class, () -> TwitchCommandHandler.parseRoleList("@Fans"));
    }
}

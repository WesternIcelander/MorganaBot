package io.siggi.morganabot.diagnose;

import com.google.gson.JsonObject;
import io.siggi.morganabot.LiveMessage;
import io.siggi.morganabot.Mentions;
import io.siggi.morganabot.MorganaBot;
import io.siggi.morganabot.ServerSettings;
import io.siggi.morganabot.TwitchStreamerWatcher;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.diagnose.Check.Level;
import io.siggi.morganabot.diagnose.GuildView.ChannelKind;
import io.siggi.morganabot.diagnose.GuildView.ChannelView;
import io.siggi.morganabot.diagnose.GuildView.RoleView;
import io.siggi.morganabot.twitch.SubscriptionNotes;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what would happen on each streamer's next go-live, and reports anything that would fail or be degraded,
 * without waiting for a stream. It doesn't post, change or store anything.
 * <p>
 * Runs on demand (the dashboard's Diagnose button), when the dashboard loads, and after every save. Member lookups
 * may block on Discord's REST API, so don't call {@link #run(Guild)} on a JDA callback thread.
 */
public class Diagnose {

    /**
     * What Diagnose needs from Twitch. In production it's backed by {@link TwitchStreamerWatcher}.
     */
    public interface TwitchInfo {
        /** Twitch logins by Twitch ID, for labelling results. Missing entries are fine. */
        Map<String, String> logins(Collection<String> twitchIds);

        /** EventSub trouble for a streamer (step 5's subscription statuses). */
        List<SubscriptionNotes.Note> subscriptionNotes(String twitchId);

        TwitchInfo NONE = new TwitchInfo() {
            @Override
            public Map<String, String> logins(Collection<String> twitchIds) {
                return Map.of();
            }

            @Override
            public List<SubscriptionNotes.Note> subscriptionNotes(String twitchId) {
                return List.of();
            }
        };
    }

    static final String EXAMPLE_TITLE = LiveMessage.EXAMPLE_TITLE;
    static final String EXAMPLE_GAME = LiveMessage.EXAMPLE_GAME;
    static final String EXAMPLE_STREAMER = LiveMessage.EXAMPLE_STREAMER;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final MorganaBot bot;

    public Diagnose(MorganaBot bot) {
        this.bot = bot;
    }

    /**
     * Checks every streamer in {@code guild}.
     */
    public DiagnoseResult run(Guild guild) {
        return run(guild, null);
    }

    /**
     * Checks one streamer (by Twitch ID), or every streamer if {@code onlyTwitchId} is null. Server-wide checks are
     * always included.
     */
    public DiagnoseResult run(Guild guild, String onlyTwitchId) {
        ServerInfo info = bot.getServerInfoStore().get(guild.getIdLong());
        return run(guild.getIdLong(), new JdaGuildView(guild), info, twitchInfo(), onlyTwitchId, System.currentTimeMillis());
    }

    private TwitchInfo twitchInfo() {
        TwitchStreamerWatcher watcher = bot.getTwitchStreamerWatcher();
        if (watcher == null) return TwitchInfo.NONE;
        return new TwitchInfo() {
            @Override
            public Map<String, String> logins(Collection<String> twitchIds) {
                Map<String, String> logins = new HashMap<>();
                if (twitchIds.isEmpty()) return logins;
                try {
                    for (Map.Entry<String, JsonObject> entry : watcher.getMultipleStreamerInfosByIds(twitchIds).entrySet()) {
                        logins.put(entry.getKey(), entry.getValue().get("login").getAsString());
                    }
                } catch (Exception e) {
                    // Names are only labels; results still make sense with IDs.
                }
                return logins;
            }

            @Override
            public List<SubscriptionNotes.Note> subscriptionNotes(String twitchId) {
                return watcher.getSubscriptionNotes().notesFor(twitchId, System.currentTimeMillis());
            }
        };
    }

    // --- the checks ---

    /**
     * Runs every check against {@code guild} as described by {@code info}.
     */
    public static DiagnoseResult run(long guildId, GuildView guild, ServerInfo info, TwitchInfo twitch, String onlyTwitchId, long now) {
        List<Check> server = new ArrayList<>();
        if (!info.enabledTwitchTracking) {
            server.add(Check.error("twitch.enabled", "Twitch announcements are turned off for this server, so nothing will post.",
                    "Ask the bot's admin to turn Twitch announcements on for this server."));
        }

        int usingDefault = 0;
        for (ServerInfo.Streamer streamer : info.streamers) {
            if (streamer.channelToPostLiveNotifications == 0L) usingDefault++;
        }
        server.addAll(defaultChannelChecks(guild, info.channelToPostLiveNotifications, usingDefault, info.streamers.size()));

        List<Check> liveRoleProblems = liveRoleChecks(guild, info.roleForLiveUsers);
        server.addAll(liveRoleProblems);

        server.addAll(selfServiceChecks(guild, info));
        server.addAll(messageRuleChecks(guild, info));

        if (info.streamers.isEmpty()) {
            server.add(Check.ok("streamers", "No streamers have been added yet."));
        }

        List<String> ids = new ArrayList<>();
        for (ServerInfo.Streamer streamer : info.streamers) {
            if (streamer.twitchId != null && (onlyTwitchId == null || onlyTwitchId.equals(streamer.twitchId))) {
                ids.add(streamer.twitchId);
            }
        }
        Map<String, String> logins = ids.isEmpty() ? Map.of() : twitch.logins(ids);

        List<DiagnoseResult.StreamerResult> streamers = new ArrayList<>();
        for (ServerInfo.Streamer streamer : info.streamers) {
            if (streamer.twitchId == null || (onlyTwitchId != null && !onlyTwitchId.equals(streamer.twitchId))) continue;
            streamers.add(checkStreamer(guild, info, streamer, logins.get(streamer.twitchId), liveRoleProblems, twitch, now));
        }
        return new DiagnoseResult(guildId, now, info.enabledTwitchTracking, server, streamers);
    }

    private static List<Check> defaultChannelChecks(GuildView guild, long channelId, int usingDefault, int streamerCount) {
        List<Check> checks = new ArrayList<>();
        if (channelId == 0L) {
            if (usingDefault > 0) {
                checks.add(Check.error("defaultChannel.set", "No default live channel is set, so " + streamers(usingDefault)
                                + " without their own channel won't be posted.",
                        "Pick a live channel with /setlivechannel or in the server settings."));
            } else if (streamerCount > 0) {
                checks.add(Check.ok("defaultChannel.set", "No default live channel is set, but every streamer has their own channel."));
            } else {
                checks.add(Check.warning("defaultChannel.set", "No default live channel is set.",
                        "Pick a live channel with /setlivechannel or in the server settings."));
            }
            return checks;
        }
        List<Check> channelChecks = channelChecks(guild, channelId, "defaultChannel", "Change the default live channel");
        boolean unused = usingDefault == 0 && streamerCount > 0;
        for (Check check : channelChecks) {
            // A broken default channel that no streamer posts to doesn't stop anything yet.
            checks.add(unused && check.level == Level.ERROR ? check.withLevel(Level.WARNING) : check);
        }
        return checks;
    }

    /**
     * Whether the bot can post in the channel: it exists, it's a text or announcement channel, and the bot has View
     * Channel and Send Messages there. Also warns about Embed Links, without which the Twitch link has no preview.
     *
     * @param prefix    the prefix for check codes, e.g. {@code channel}
     * @param changeFix the "or ..." part of the fix hint, e.g. "pick another channel"
     */
    public static List<Check> channelChecks(GuildView guild, long channelId, String prefix, String changeFix) {
        List<Check> checks = new ArrayList<>();
        ChannelView channel = guild.channel(channelId);
        if (channel == null) {
            checks.add(Check.error(prefix + ".exists", "The channel (ID " + channelId + ") doesn't exist any more.", capitalize(changeFix) + "."));
            return checks;
        }
        String name = "#" + channel.name();
        if (!channel.canPostIn()) {
            checks.add(Check.error(prefix + ".type", name + " isn't a text or announcement channel, so live posts can't go there.",
                    capitalize(changeFix) + "."));
            return checks;
        }
        String bot = guild.botName();
        boolean view = channel.botHas(Permission.VIEW_CHANNEL);
        boolean send = channel.botHas(Permission.MESSAGE_SEND);
        if (!view || !send) {
            String missing = !view && !send ? "*View Channel* and *Send Messages*" : !view ? "*View Channel*" : "*Send Messages*";
            checks.add(Check.error(prefix + ".permissions", bot + " can't post in " + name + ": it's missing " + missing + ".",
                    "Give " + bot + " " + missing + " in " + name + ", or " + changeFix.toLowerCase() + "."));
            return checks;
        }
        checks.add(Check.ok(prefix + ".permissions", bot + " can post in " + name
                + (channel.kind() == ChannelKind.ANNOUNCEMENT ? " (announcement channel)." : ".")));
        if (!channel.botHas(Permission.MESSAGE_EMBED_LINKS)) {
            checks.add(Check.warning(prefix + ".embeds", "The Twitch link won't show a preview in " + name + ": "
                    + bot + " is missing *Embed Links*.", "Give " + bot + " *Embed Links* in " + name + "."));
        }
        return checks;
    }

    /**
     * The server-wide live role checks: it exists, the bot has Manage Roles, and the role is one the bot can give.
     * Returns an OK check if no role is set or everything's fine; otherwise only the problems, as warnings (posts
     * still go out without the role).
     */
    public static List<Check> liveRoleChecks(GuildView guild, long roleId) {
        List<Check> checks = new ArrayList<>();
        if (roleId == 0L) {
            checks.add(Check.ok("liveRole", "No live role is set."));
            return checks;
        }
        String bot = guild.botName();
        RoleView role = guild.role(roleId);
        if (role == null) {
            checks.add(Check.warning("liveRole.exists", "The live role (ID " + roleId + ") doesn't exist any more, so no live role will be given.",
                    "Pick another live role with /setstreamerrole or in the server settings."));
            return checks;
        }
        String name = "@" + role.name();
        if (role.publicRole() || role.managed()) {
            checks.add(Check.warning("liveRole.assignable", name + " can't be given to members"
                            + (role.publicRole() ? " (it's the @everyone role)." : " (it belongs to an integration or bot)."),
                    "Pick another live role."));
            return checks;
        }
        if (!guild.botHasPermission(Permission.MANAGE_ROLES)) {
            checks.add(Check.warning("liveRole.permission", bot + " is missing *Manage Roles*, so it can't give " + name + ".",
                    "Give " + bot + " *Manage Roles*."));
        }
        if (!role.botCanInteract()) {
            checks.add(Check.warning("liveRole.hierarchy", name + " is above " + bot + "'s highest role, so " + bot + " can't give it.",
                    "In Server Settings → Roles, drag " + bot + "'s role above " + name + "."));
        }
        if (checks.isEmpty()) {
            checks.add(Check.ok("liveRole", bot + " can give " + name + "."));
        }
        return checks;
    }

    /**
     * Each self-service role still exists, and the bot can post in each role's channel. A broken role channel is a
     * warning here; the streamers posting there get the error.
     */
    static List<Check> selfServiceChecks(GuildView guild, ServerInfo info) {
        List<Check> checks = new ArrayList<>();
        for (ServerInfo.SelfServiceRole entry : info.selfServiceRoles()) {
            if (entry == null) continue;
            RoleView role = guild.role(entry.roleId);
            if (role == null) {
                checks.add(Check.warning("selfService.role.exists", "A self-service role (ID " + entry.roleId
                                + ") doesn't exist any more, so nobody can add themselves through it.",
                        "Remove it from the self-service roles in the dashboard."));
                continue;
            }
            String name = "@" + role.name();
            if (entry.channelId == 0L) {
                checks.add(Check.ok("selfService.role", "Self-service role " + name + " posts in the server's default live channel."));
                continue;
            }
            for (Check check : channelChecks(guild, entry.channelId, "selfService.channel",
                    "pick another channel for self-service role " + name)) {
                checks.add(new Check(check.code, check.level == Level.ERROR ? Level.WARNING : check.level,
                        "Self-service role " + name + ": " + check.message, check.fix));
            }
        }
        return checks;
    }

    /**
     * Every role a message customization rule refers to (the rule's own role and the roles it lets members mention)
     * still exists.
     */
    static List<Check> messageRuleChecks(GuildView guild, ServerInfo info) {
        List<Check> checks = new ArrayList<>();
        for (ServerInfo.MessageCustomizationRule rule : info.messageCustomizationRules()) {
            if (rule == null) continue;
            RoleView role = guild.role(rule.roleId);
            String name = role == null ? "(deleted role " + rule.roleId + ")" : "@" + role.name();
            if (role == null) {
                checks.add(Check.warning("messageRule.role.exists", "A message permission rule's role (ID " + rule.roleId
                                + ") doesn't exist any more, so nobody gets that rule.",
                        "Remove the rule with /removemessagerole or in the dashboard."));
            }
            for (Long mentionRole : rule.allowedMentionRoles()) {
                if (mentionRole == null || guild.role(mentionRole) != null) continue;
                checks.add(Check.warning("messageRule.mentionRole.exists", "The message permission rule for " + name
                                + " lets members mention a role (ID " + mentionRole + ") that doesn't exist any more.",
                        "Update the rule with /addmessagerole or in the dashboard."));
            }
        }
        return checks;
    }

    private static DiagnoseResult.StreamerResult checkStreamer(GuildView guild, ServerInfo info, ServerInfo.Streamer streamer, String login,
                                                              List<Check> liveRoleProblems, TwitchInfo twitch, long now) {
        List<Check> checks = new ArrayList<>();
        String bot = guild.botName();

        // Which channel the next post goes to.
        boolean override = streamer.channelToPostLiveNotifications != 0L;
        long channelId = override ? streamer.channelToPostLiveNotifications : info.channelToPostLiveNotifications;
        String source = override ? "streamer's own channel" : "server default";
        if (streamer.selfService) {
            if (streamer.channelOverridePinned) {
                source = override ? "set by an admin" : "server default, set by an admin";
            } else if (!streamer.isSelfServicePaused() && streamer.discordId != 0L) {
                List<Long> roles = guild.memberRoles(streamer.discordId);
                ServerSettings.SelfServiceChannel resolved = roles == null ? ServerSettings.SelfServiceChannel.NONE
                        : ServerSettings.resolveSelfServiceChannel(info, roles);
                if (resolved.eligible()) {
                    RoleView role = guild.role(resolved.roleId());
                    String roleName = role == null ? "role " + resolved.roleId() : "role @" + role.name();
                    source = override ? "from " + roleName : "server default, from " + roleName;
                }
            }
        }
        ChannelView channel = null;
        String channelLabel;
        if (channelId == 0L) {
            channelLabel = "no channel";
            checks.add(Check.error("channel.set", "No channel to post in: this streamer has no channel of their own, and no default live channel is set.",
                    "Pick a default live channel, or give this streamer their own channel."));
        } else {
            ChannelView found = guild.channel(channelId);
            channelLabel = (found == null ? "deleted channel " + channelId : "#" + found.name()) + " (" + source + ")";
            String changeFix = override ? "pick another channel for this streamer" : "pick another default live channel";
            List<Check> channelChecks = channelChecks(guild, channelId, "channel", changeFix);
            checks.addAll(channelChecks);
            if (DiagnoseResult.worst(channelChecks) != Level.ERROR) channel = found;
        }

        // Mentions in the template.
        String template = LiveMessage.templateFor(info, streamer);
        String templateName = streamer.liveNotificationTemplate != null ? "their own live message"
                : info.liveNotificationTemplate != null ? "the server-wide live message" : "the default live message";
        checks.addAll(mentionChecks(guild, channel, Mentions.parse(template), templateName));
        if (template.contains("${discord_name}") && streamer.discordId == 0L) {
            checks.add(Check.warning("template.discordName", "The live message uses ${discord_name}, but no Discord user is linked, so it shows \"?\".",
                    "Link this streamer to their Discord user, or remove ${discord_name} from the message."));
        }

        // Live role.
        if (info.roleForLiveUsers != 0L) {
            RoleView role = guild.role(info.roleForLiveUsers);
            String roleName = role == null ? "the live role" : "@" + role.name();
            if (streamer.discordId == 0L) {
                checks.add(Check.ok("liveRole.user", "No Discord user is linked, so no live role will be given."));
            } else {
                Boolean member = guild.isMember(streamer.discordId);
                if (member == null) {
                    checks.add(Check.warning("liveRole.user", "Couldn't check whether the linked Discord user (<@" + streamer.discordId
                            + ">) is in the server.", "Run Diagnose again later."));
                } else if (!member) {
                    checks.add(Check.warning("liveRole.user", "The linked Discord user (<@" + streamer.discordId
                                    + ">) isn't in the server, so they won't get " + roleName + ".",
                            "Link this streamer to their current Discord account, or unlink them."));
                } else if (liveRoleProblems.size() == 1 && liveRoleProblems.get(0).level == Level.OK) {
                    checks.add(Check.ok("liveRole", "Gets " + roleName + " while live."));
                } else {
                    for (Check problem : liveRoleProblems) {
                        checks.add(Check.warning(problem.code, "Won't get " + roleName + ". " + problem.message, problem.fix));
                    }
                }
            }
        }

        // Deleting the post when the stream ends.
        if (info.deleteNotificationsOnOffline && channel != null) {
            boolean view = channel.botHas(Permission.VIEW_CHANNEL);
            boolean history = channel.botHas(Permission.MESSAGE_HISTORY);
            String name = "#" + channel.name();
            if (!view || !history) {
                String missing = !view ? "*View Channel* and *Read Message History*" : "*Read Message History*";
                checks.add(Check.warning("deleteOnOffline", "The live post won't be deleted when the stream ends: " + bot
                        + " is missing " + missing + " in " + name + ".", "Give " + bot + " " + missing + " in " + name + "."));
            } else {
                checks.add(Check.ok("deleteOnOffline", "The live post will be deleted when the stream ends."));
            }
        }

        // Length, with a long example title.
        String filled = LiveMessage.fill(template, EXAMPLE_GAME, EXAMPLE_TITLE, EXAMPLE_STREAMER, streamer.discordId);
        if (filled.length() > LiveMessage.MAX_LENGTH) {
            checks.add(Check.error("template.length", "The live message is too long: with a long stream title it's " + filled.length()
                            + " characters, and Discord allows " + LiveMessage.MAX_LENGTH + ".",
                    "Shorten " + templateName + "."));
        }

        // Paused self-added streamer.
        if (streamer.isSelfServicePaused()) {
            int days = info.selfServiceRemoveAfterDays;
            String removal = days > 0 ? " Their entry is removed on "
                    + DATE.format(Instant.ofEpochMilli(streamer.selfServiceLostAt + days * 24L * 60L * 60L * 1000L)) + " if that doesn't change." : "";
            checks.add(0, Check.warning("selfService.paused", "Paused: no self-service role since "
                            + DATE.format(Instant.ofEpochMilli(streamer.selfServiceLostAt)) + ", so nothing will post when they go live." + removal,
                    "Give them a self-service role again to resume, or remove them."));
        }

        // Twitch subscription status.
        for (SubscriptionNotes.Note note : twitch.subscriptionNotes(streamer.twitchId)) {
            checks.add(subscriptionCheck(note));
        }

        return new DiagnoseResult.StreamerResult(streamer.twitchId, login, streamer.discordId, channelId, channelLabel, checks);
    }

    /**
     * Whether each mention in the template will ping.
     *
     * @param channel the channel the post goes to, or null if it can't post anywhere (permissions aren't checked then)
     */
    static List<Check> mentionChecks(GuildView guild, ChannelView channel, Mentions mentions, String templateName) {
        List<Check> checks = new ArrayList<>();
        String bot = guild.botName();
        boolean mentionEveryone = channel != null && channel.botHas(Permission.MESSAGE_MENTION_EVERYONE);
        String where = channel == null ? "" : " in #" + channel.name();
        for (String everyone : new String[]{"@everyone", "@here"}) {
            if (everyone.equals("@everyone") ? !mentions.everyone() : !mentions.here()) continue;
            String code = "mention." + everyone.substring(1);
            if (channel == null) continue;
            if (mentionEveryone) {
                checks.add(Check.ok(code, everyone + " will ping."));
            } else {
                checks.add(Check.warning(code, everyone + " in " + templateName + " won't ping: " + bot
                                + " is missing *Mention Everyone*" + where + ". It's posted as plain text.",
                        "Give " + bot + " *Mention Everyone*" + where + ", or remove " + everyone + " from the message."));
            }
        }
        for (long roleId : mentions.roles()) {
            RoleView role = guild.role(roleId);
            if (role == null) {
                checks.add(Check.warning("mention.role", "A role mentioned in " + templateName + " (ID " + roleId
                        + ") doesn't exist any more, so it won't ping.", "Remove or replace the role mention in the message."));
                continue;
            }
            String name = "@" + role.name();
            if (channel == null) continue;
            if (role.mentionable() || mentionEveryone) {
                checks.add(Check.ok("mention.role", name + " will ping."));
            } else {
                checks.add(Check.warning("mention.role", name + " in " + templateName + " won't ping: the role doesn't allow anyone to mention it, and "
                                + bot + " is missing *Mention Everyone*" + where + ".",
                        "Turn on \"Allow anyone to @mention this role\" for " + name + ", or give " + bot + " *Mention Everyone*" + where + "."));
            }
        }
        for (long userId : mentions.users()) {
            Boolean member = guild.isMember(userId);
            if (member == null) {
                checks.add(Check.warning("mention.user", "Couldn't check whether <@" + userId + ">, mentioned in " + templateName
                        + ", is in the server.", "Run Diagnose again later."));
            } else if (!member) {
                checks.add(Check.warning("mention.user", "<@" + userId + ">, mentioned in " + templateName
                        + ", isn't in the server any more, so they won't be pinged.", "Remove or replace the user mention in the message."));
            } else {
                checks.add(Check.ok("mention.user", "<@" + userId + "> will be pinged."));
            }
        }
        return checks;
    }

    static Check subscriptionCheck(SubscriptionNotes.Note note) {
        boolean online = "stream.online".equals(note.type());
        String what = online ? "go-live" : "offline";
        if (note.kind() == SubscriptionNotes.Kind.CREATE_FAILED) {
            String consequence = online ? "so nothing will post when they go live" : "so their live post won't be cleaned up when they go offline";
            String message = "The bot couldn't sign up for Twitch's " + what + " notifications for this streamer (" + note.status() + "), " + consequence + ".";
            String fix = "The bot retries every hour. If it keeps failing, tell the bot's admin.";
            return online ? Check.error("twitch.subscription", message, fix) : Check.warning("twitch.subscription", message, fix);
        }
        return Check.warning("twitch.subscription", "Twitch disabled the " + what + " notifications for this streamer on "
                        + DATE.format(Instant.ofEpochMilli(note.time())) + " (" + note.status() + "). The bot signs up again on its next sync.",
                "authorization_revoked".equals(note.status()) || "user_removed".equals(note.status())
                        ? "Check that the Twitch account still exists and isn't banned."
                        : "If this keeps happening, tell the bot's admin.");
    }

    private static String streamers(int count) {
        return count + " streamer" + (count == 1 ? "" : "s");
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}

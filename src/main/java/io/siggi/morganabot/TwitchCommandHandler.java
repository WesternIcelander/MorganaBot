package io.siggi.morganabot;

import com.google.gson.JsonObject;
import io.siggi.morganabot.ServerSettings.SettingsException;
import io.siggi.morganabot.ServerSettings.StreamerRef;
import io.siggi.morganabot.audit.Actor;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.diagnose.Check;
import io.siggi.morganabot.diagnose.Diagnose;
import io.siggi.morganabot.diagnose.JdaGuildView;
import io.siggi.morganabot.health.Health;
import io.siggi.morganabot.util.Util;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Slash commands for the Twitch features. These only parse options and format replies; the settings themselves are
 * validated and changed by {@link ServerSettings}.
 */
public class TwitchCommandHandler {

    static final String LIVE_MESSAGE_HELP = "Available variables: ${game_name}, ${stream_name}, ${streamer_name}, ${twitch_link}, ${discord_name}\n\nTo insert line breaks in a message, use \\n";

    @FunctionalInterface
    private interface Action {
        String run() throws Exception;
    }

    /**
     * Runs {@code action} and replies (ephemeral) with what it returns, the {@link SettingsException} message, or a
     * generic error.
     */
    private static void handle(SlashCommandInteractionEvent event, Action action) {
        String reply;
        try {
            reply = action.run();
        } catch (SettingsException e) {
            reply = e.getMessage();
        } catch (Exception e) {
            Health.unexpected("Command /" + event.getName() + " failed", e);
            reply = "There was a problem executing that command, try again!";
        }
        event.reply(reply).setEphemeral(true).queue();
    }

    static void addStreamer(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            long guildId = event.getGuild().getIdLong();
            String twitchName = getString(event, "twitch-name");
            long discordUserId = getUserId(event, "discord-name");
            long channelId = getChannelId(event, "post-in-channel");
            ServerSettings.TwitchUser user = bot.getServerSettings().addStreamer(actor(event), guildId, twitchName, discordUserId, channelId);
            return "Added " + Util.markdownEscape(user.displayName())
                    + (discordUserId != 0L ? " / <@" + discordUserId + ">" : "")
                    + " as a Twitch streamer"
                    + (channelId != 0L ? " in <#" + channelId + ">" : "")
                    + (channelId != 0L ? channelWarnings(event, channelId, "pick another channel for this streamer") : "");
        });
    }

    static void deleteStreamer(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            long guildId = event.getGuild().getIdLong();
            ServerInfo.Streamer removed = bot.getServerSettings().deleteStreamer(actor(event), guildId, getStreamerRef(event));
            return "Deleted " + describe(removed, event);
        });
    }

    static void setChannelOverride(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            long guildId = event.getGuild().getIdLong();
            long channelId = getChannelId(event, "channel");
            OptionMapping roleBased = event.getOption("role-based");
            if (roleBased != null && roleBased.getAsBoolean()) {
                if (event.getOption("channel") != null) {
                    return "Pick either a channel or role-based, not both.";
                }
                ServerInfo.Streamer streamer = bot.getServerSettings().unpinChannel(actor(event), guildId, getStreamerRef(event));
                long effective = streamer.channelToPostLiveNotifications;
                String where;
                if (streamer.isSelfServicePaused()) {
                    where = "They have no self-service role right now, so their posts are paused; the channel will follow their role once they get one back.";
                } else if (effective == 0L) {
                    where = "Their posts now go to the server's default live channel.";
                } else {
                    where = "Their posts now go to <#" + effective + ">." + channelWarnings(event, effective, "change the channel of their self-service role");
                }
                return "The channel for " + describe(streamer, event) + " follows their role again. " + where;
            }
            ServerInfo.Streamer streamer = bot.getServerSettings().setChannelOverride(actor(event), guildId, getStreamerRef(event), channelId);
            if (channelId == 0L) {
                return "Removed override for " + describe(streamer, event);
            } else {
                return "Set override for " + describe(streamer, event) + " to <#" + channelId + ">"
                        + channelWarnings(event, channelId, "pick another channel for this streamer");
            }
        });
    }

    static void setStreamerRole(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            OptionMapping roleMapping = event.getOption("role");
            long roleId = roleMapping == null ? 0L : roleMapping.getAsRole().getIdLong();
            bot.getServerSettings().setLiveRole(actor(event), event.getGuild().getIdLong(), roleId);
            if (roleId == 0L) {
                return "Streamer role was removed";
            }
            return "Streamer role was set to <@&" + roleId + ">"
                    + warnings(Diagnose.liveRoleChecks(new JdaGuildView(event.getGuild()), roleId));
        });
    }

    static void setLiveChannel(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            long channelId = getChannelId(event, "channel");
            bot.getServerSettings().setLiveChannel(actor(event), event.getGuild().getIdLong(), channelId);
            if (channelId == 0L) {
                return "Live channel was removed";
            }
            return "Live channel was set to <#" + channelId + ">"
                    + channelWarnings(event, channelId, "pick another live channel");
        });
    }

    static void setDeleteOnOffline(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            OptionMapping deleteMapping = event.getOption("delete");
            if (deleteMapping == null) {
                return "You need to choose whether to delete notifications!";
            }
            boolean delete = deleteMapping.getAsBoolean();
            bot.getServerSettings().setDeleteOnOffline(actor(event), event.getGuild().getIdLong(), delete);
            return "Set delete notifications on offline: " + delete;
        });
    }

    static void setLiveMessage(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            long guildId = event.getGuild().getIdLong();
            String message = getString(event, "message");
            long discordUserId = getUserId(event, "discord-name");
            if (message != null) {
                message = message.replace("\\n", "\n");
                if (message.equals("default") || message.equals("reset")) {
                    message = null;
                } else if (message.equals("help")) {
                    return LIVE_MESSAGE_HELP;
                }
            }
            if (discordUserId != 0L) {
                bot.getServerSettings().setStreamerTemplate(actor(event), guildId, new StreamerRef(null, discordUserId), message);
                return "Updated user-specific notification template for <@" + discordUserId + ">!";
            } else {
                bot.getServerSettings().setServerTemplate(actor(event), guildId, message);
                return "Updated server-wide notification template!";
            }
        });
    }

    // --- self-service ---

    static void selfSetTwitch(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            Member member = event.getMember();
            ServerSettings.SelfSetResult result = bot.getServerSettings().selfSetTwitch(selfActor(event),
                    SelfServiceListener.of(member), getString(event, "twitch-name"));
            ServerInfo info = bot.getServerInfoStore().get(event.getGuild().getIdLong());
            StringBuilder sb = new StringBuilder();
            sb.append(result.added() ? "Added you as a streamer: " : "Your Twitch channel is now ")
                    .append("[").append(Util.markdownEscape(result.user().displayName())).append("](<https://www.twitch.tv/")
                    .append(result.user().login()).append(">).\n");
            long effective = result.channelId() != 0L ? result.channelId() : info.channelToPostLiveNotifications;
            if (effective == 0L) {
                sb.append("Your live posts have nowhere to go yet: this server has no default live channel. Ask an admin to set one.");
            } else {
                sb.append("Your live posts will go to <#").append(effective).append(">")
                        .append(result.channelId() != 0L ? " (from your role <@&" + result.roleId() + ">)." : " (the server's default live channel).")
                        .append(channelWarnings(event, effective, "ask an admin to pick another channel"));
            }
            return sb.toString();
        });
    }

    static void selfRemove(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            bot.getServerSettings().selfRemove(selfActor(event), event.getGuild().getIdLong(), event.getUser().getIdLong());
            return "Removed you as a streamer in this server.";
        });
    }

    static void addSelfServiceRole(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            Role role = event.getOption("role").getAsRole();
            long channelId = getChannelId(event, "channel");
            if (role.isPublicRole()) {
                return "The @everyone role can't be a self-service role.";
            }
            bot.getServerSettings().addSelfServiceRole(actor(event), event.getGuild().getIdLong(), role.getIdLong(), channelId);
            return "Members with <@&" + role.getIdLong() + "> can now add themselves with /settwitchchannel. Their live posts go to "
                    + (channelId == 0L ? "the server's default live channel" : "<#" + channelId + ">")
                    + " when that's their highest self-service role."
                    + (channelId != 0L ? channelWarnings(event, channelId, "pick another channel for this role") : "");
        });
    }

    static void removeSelfServiceRole(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            long roleId = event.getOption("role").getAsRole().getIdLong();
            boolean removed = bot.getServerSettings().removeSelfServiceRole(actor(event), event.getGuild().getIdLong(), roleId);
            return removed
                    ? "<@&" + roleId + "> is no longer a self-service role. Self-added streamers without another self-service role are paused."
                    : "<@&" + roleId + "> isn't a self-service role.";
        });
    }

    static void setSelfServiceExpiry(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            OptionMapping days = event.getOption("days");
            if (days == null) return "You need to give a number of days!";
            int value = days.getAsInt();
            bot.getServerSettings().setSelfServiceRemoveAfterDays(actor(event), event.getGuild().getIdLong(), value);
            return value == 0
                    ? "Self-added streamers who lose their self-service role are paused and never removed."
                    : "Self-added streamers are removed after " + value + " day" + (value == 1 ? "" : "s") + " without a self-service role.";
        });
    }

    static void getStreamerList(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            ServerInfo serverInfo = bot.getServerSettings().requireTwitchEnabled(event.getGuild().getIdLong());
            List<String> ids = new ArrayList<>();
            for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                ids.add(streamer.twitchId);
            }
            Map<String, JsonObject> twitchUsers = ids.isEmpty() ? Map.of() : bot.getTwitchStreamerWatcher().getMultipleStreamerInfosByIds(ids);
            return formatStreamerList(serverInfo.streamers, twitchUsers);
        });
    }

    /**
     * Builds the {@code /streamerlist} reply, sorted by Twitch display name. Links use the Twitch login, since display
     * names can contain characters (e.g. non-Latin scripts) that aren't valid in a twitch.tv URL.
     *
     * @param twitchUsers Twitch user objects (with {@code id}, {@code login}, {@code display_name}) by Twitch ID
     */
    static String formatStreamerList(List<ServerInfo.Streamer> streamers, Map<String, JsonObject> twitchUsers) {
        if (streamers.isEmpty()) {
            return "Streamers in this server:\n\nNobody is here! Add someone using `/addstreamer`!";
        }
        record Row(ServerInfo.Streamer streamer, String displayName, String login) {
        }
        List<Row> rows = new ArrayList<>();
        for (ServerInfo.Streamer streamer : streamers) {
            JsonObject user = streamer.twitchId == null ? null : twitchUsers.get(streamer.twitchId);
            if (user == null) {
                rows.add(new Row(streamer, null, null));
            } else {
                rows.add(new Row(streamer, user.get("display_name").getAsString(), user.get("login").getAsString()));
            }
        }
        rows.sort(Comparator.comparing((Row row) -> row.displayName() == null)
                .thenComparing(row -> row.displayName() == null ? "" : row.displayName(), String::compareToIgnoreCase));
        StringBuilder sb = new StringBuilder();
        sb.append("Streamers in this server:\n\n");
        for (Row row : rows) {
            ServerInfo.Streamer streamer = row.streamer();
            if (row.login() == null) {
                sb.append("- Unknown Twitch account (ID ").append(streamer.twitchId).append(")\n");
            } else {
                sb.append("- [").append(Util.markdownEscape(row.displayName())).append("](")
                        .append("<https://www.twitch.tv/").append(row.login()).append(">)\n");
            }
            if (streamer.discordId == 0L) {
                sb.append("  - Discord: unset\n");
            } else {
                sb.append("  - Discord: <@").append(streamer.discordId).append(">\n");
            }
            if (streamer.stoppedStreaming > 0L) {
                sb.append("  - Last stream: <t:").append(streamer.stoppedStreaming / 1000L).append(":f>\n");
            }
            if (streamer.channelToPostLiveNotifications != 0L) {
                sb.append("  - Posts in: <#").append(streamer.channelToPostLiveNotifications).append(">")
                        .append(streamer.selfService && streamer.channelOverridePinned ? " (set by an admin)" : "").append("\n");
            }
            if (streamer.selfService) {
                sb.append("  - Self-added");
                if (streamer.selfServiceLostAt != 0L) {
                    sb.append(", paused: no self-service role since <t:").append(streamer.selfServiceLostAt / 1000L).append(":f>");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // --- Diagnose after a save ---

    /**
     * Problems with posting in the channel that was just picked, so they're flagged straight away. Uses the JDA
     * cache only.
     */
    private static String channelWarnings(SlashCommandInteractionEvent event, long channelId, String changeFix) {
        return warnings(Diagnose.channelChecks(new JdaGuildView(event.getGuild()), channelId, "channel", changeFix));
    }

    /**
     * The warnings and errors among {@code checks}, one per line after a blank line, or "" if there are none.
     */
    static String warnings(List<Check> checks) {
        StringBuilder sb = new StringBuilder();
        for (Check check : checks) {
            if (check.level == Check.Level.OK) continue;
            sb.append(sb.length() == 0 ? "\n\n" : "\n");
            sb.append(check.level == Check.Level.ERROR ? "**Error:** " : "**Warning:** ").append(check.message);
            if (check.fix != null) sb.append(" ").append(check.fix);
        }
        return sb.toString();
    }

    // --- option parsing ---

    /**
     * The member who ran the command, for the audit log.
     */
    private static Actor actor(SlashCommandInteractionEvent event) {
        User user = event.getUser();
        return Actor.command(user.getIdLong(), user.getName(), event.getName());
    }

    /**
     * The member managing their own entry, for the audit log.
     */
    private static Actor selfActor(SlashCommandInteractionEvent event) {
        User user = event.getUser();
        return Actor.selfService(user.getIdLong(), user.getName(), event.getName());
    }

    private static StreamerRef getStreamerRef(SlashCommandInteractionEvent event) {
        return new StreamerRef(getString(event, "twitch-name"), getUserId(event, "discord-name"));
    }

    /**
     * How a reply names a streamer: their Discord mention, or their Twitch name from the command if they have no
     * linked Discord user.
     */
    private static String describe(ServerInfo.Streamer streamer, SlashCommandInteractionEvent event) {
        if (streamer.discordId != 0L) {
            return "<@" + streamer.discordId + ">";
        }
        String twitchName = getString(event, "twitch-name");
        return twitchName != null ? Util.markdownEscape(twitchName.trim()) : "Twitch user " + streamer.twitchId;
    }

    private static String getString(SlashCommandInteractionEvent event, String name) {
        OptionMapping mapping = event.getOption(name);
        return mapping == null ? null : mapping.getAsString();
    }

    private static long getUserId(SlashCommandInteractionEvent event, String name) {
        OptionMapping mapping = event.getOption(name);
        if (mapping == null) return 0L;
        User user = mapping.getAsUser();
        return user.getIdLong();
    }

    private static long getChannelId(SlashCommandInteractionEvent event, String name) {
        OptionMapping mapping = event.getOption(name);
        return mapping == null ? 0L : mapping.getAsChannel().getIdLong();
    }
}

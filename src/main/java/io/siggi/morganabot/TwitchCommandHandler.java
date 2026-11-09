package io.siggi.morganabot;

import com.google.gson.JsonObject;
import io.siggi.morganabot.ServerSettings.SettingsException;
import io.siggi.morganabot.ServerSettings.StreamerRef;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.util.Util;
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
            e.printStackTrace();
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
            ServerSettings.TwitchUser user = bot.getServerSettings().addStreamer(guildId, twitchName, discordUserId, channelId);
            return "Added " + Util.markdownEscape(user.displayName())
                    + (discordUserId != 0L ? " / <@" + discordUserId + ">" : "")
                    + " as a Twitch streamer"
                    + (channelId != 0L ? " in <#" + channelId + ">" : "");
        });
    }

    static void deleteStreamer(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            long guildId = event.getGuild().getIdLong();
            ServerInfo.Streamer removed = bot.getServerSettings().deleteStreamer(guildId, getStreamerRef(event));
            return "Deleted " + describe(removed, event);
        });
    }

    static void setChannelOverride(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            long guildId = event.getGuild().getIdLong();
            long channelId = getChannelId(event, "channel");
            ServerInfo.Streamer streamer = bot.getServerSettings().setChannelOverride(guildId, getStreamerRef(event), channelId);
            if (channelId == 0L) {
                return "Removed override for " + describe(streamer, event);
            } else {
                return "Set override for " + describe(streamer, event) + " to <#" + channelId + ">";
            }
        });
    }

    static void setStreamerRole(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            OptionMapping roleMapping = event.getOption("role");
            long roleId = roleMapping == null ? 0L : roleMapping.getAsRole().getIdLong();
            bot.getServerSettings().setLiveRole(event.getGuild().getIdLong(), roleId);
            return "Streamer role was set to <@&" + roleId + ">";
        });
    }

    static void setLiveChannel(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            long channelId = getChannelId(event, "channel");
            bot.getServerSettings().setLiveChannel(event.getGuild().getIdLong(), channelId);
            return "Live channel was set to <#" + channelId + ">";
        });
    }

    static void setDeleteOnOffline(MorganaBot bot, SlashCommandInteractionEvent event) {
        handle(event, () -> {
            OptionMapping deleteMapping = event.getOption("delete");
            if (deleteMapping == null) {
                return "You need to choose whether to delete notifications!";
            }
            boolean delete = deleteMapping.getAsBoolean();
            bot.getServerSettings().setDeleteOnOffline(event.getGuild().getIdLong(), delete);
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
                bot.getServerSettings().setStreamerTemplate(guildId, new StreamerRef(null, discordUserId), message);
                return "Updated user-specific notification template for <@" + discordUserId + ">!";
            } else {
                bot.getServerSettings().setServerTemplate(guildId, message);
                return "Updated server-wide notification template!";
            }
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
                sb.append("  - Posts in: <#").append(streamer.channelToPostLiveNotifications).append(">\n");
            }
        }
        return sb.toString();
    }

    // --- option parsing ---

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

package io.siggi.morganabot;

import com.google.gson.JsonObject;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.util.Util;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class TwitchCommandHandler {
    static void addStreamer(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            long guildId = event.getGuild().getIdLong();
            if (!bot.getServerInfoStore().get(guildId).enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            OptionMapping twitchMapping = event.getInteraction().getOption("twitch-name");
            String twitchName = twitchMapping == null ? null : twitchMapping.getAsString();
            OptionMapping discordMapping = event.getInteraction().getOption("discord-name");
            User discordUser = discordMapping == null ? null : discordMapping.getAsUser();
            OptionMapping channelMapping = event.getInteraction().getOption("post-in-channel");
            long selectedChannel = getChannelId(channelMapping);
            if (twitchName == null) {
                event.reply("Whoops!").setEphemeral(true).queue();
                return;
            }
            long discordUserId = discordUser == null ? 0L : discordUser.getIdLong();
            JsonObject streamerInfoByUsername = bot.getTwitchStreamerWatcher().getStreamerInfoByUsername(twitchName);
            String streamerUsername = streamerInfoByUsername.get("display_name").getAsString();
            String streamerId = streamerInfoByUsername.get("id").getAsString();
            String error = bot.getServerInfoStore().modify(guildId, serverInfo -> {
                for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                    if (streamerId.equals(streamer.twitchId)) {
                        return "The specified Twitch user is already added as a streamer!";
                    } else if (discordUserId != 0L && discordUserId == streamer.discordId) {
                        return "The specified Discord user is already added as a streamer!";
                    }
                }
                ServerInfo.Streamer streamer = new ServerInfo.Streamer(discordUserId, streamerId);
                streamer.channelToPostLiveNotifications = selectedChannel;
                serverInfo.streamers.add(streamer);
                return null;
            });
            if (error != null) {
                event.reply(error).setEphemeral(true).queue();
                return;
            }
            bot.getTwitchStreamerWatcher().triggerUpdate();
            event.reply("Added " + Util.markdownEscape(streamerUsername) + " / " + "<@" + discordUserId + "> as a Twitch streamer" + (selectedChannel != 0L ? (" in <#" + selectedChannel + ">") : "")).setEphemeral(true).queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    /**
     * Which streamer a command refers to: the Discord user ID (0 if not given) and the Twitch ID (null if not
     * given or not found). Looked up before taking the server lock, since it may call the Twitch API.
     */
    private record StreamerQuery(long discordUserId, String twitchId) {
    }

    private static StreamerQuery getStreamerQuery(MorganaBot bot, OptionMapping twitchMapping, OptionMapping discordMapping, IReplyCallback event) throws IOException {
        String twitchName = twitchMapping == null ? null : twitchMapping.getAsString();
        User discordUser = discordMapping == null ? null : discordMapping.getAsUser();
        if (twitchName == null && discordUser == null) {
            event.reply("You need to specify either their Twitch name or Discord name!").setEphemeral(true).queue();
            return null;
        }
        long discordUserId = discordUser == null ? 0L : discordUser.getIdLong();
        JsonObject streamerInfoByUsername = twitchName == null ? null : bot.getTwitchStreamerWatcher().getStreamerInfoByUsername(twitchName);
        String streamerId = streamerInfoByUsername == null ? null : streamerInfoByUsername.get("id").getAsString();
        return new StreamerQuery(discordUserId, streamerId);
    }

    private static ServerInfo.Streamer findStreamer(ServerInfo serverInfo, StreamerQuery query) {
        for (ServerInfo.Streamer streamer : serverInfo.streamers) {
            if (streamer.discordId == query.discordUserId() || (streamer.twitchId != null && streamer.twitchId.equals(query.twitchId()))) {
                return streamer;
            }
        }
        return null;
    }

    static void deleteStreamer(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            long guildId = event.getGuild().getIdLong();
            if (!bot.getServerInfoStore().get(guildId).enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            OptionMapping twitchMapping = event.getInteraction().getOption("twitch-name");
            OptionMapping discordMapping = event.getInteraction().getOption("discord-name");
            StreamerQuery query = getStreamerQuery(bot, twitchMapping, discordMapping, event);
            if (query == null) {
                return;
            }
            Long deletedDiscordId = bot.getServerInfoStore().modify(guildId, serverInfo -> {
                ServerInfo.Streamer streamer = findStreamer(serverInfo, query);
                if (streamer == null) return null;
                serverInfo.streamers.remove(streamer);
                return streamer.discordId;
            });
            if (deletedDiscordId == null) {
                event.reply("No such streamer was found!").setEphemeral(true).queue();
                return;
            }
            bot.getTwitchStreamerWatcher().triggerUpdate();
            event.reply("Deleted <@" + deletedDiscordId + ">").setEphemeral(true).queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    static void setChannelOverride(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            long guildId = event.getGuild().getIdLong();
            if (!bot.getServerInfoStore().get(guildId).enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            OptionMapping twitchMapping = event.getInteraction().getOption("twitch-name");
            OptionMapping discordMapping = event.getInteraction().getOption("discord-name");
            OptionMapping channelMapping = event.getInteraction().getOption("channel");
            long channelToPostLiveNotifications = getChannelId(channelMapping);
            StreamerQuery query = getStreamerQuery(bot, twitchMapping, discordMapping, event);
            if (query == null) {
                return;
            }
            Long streamerDiscordId = bot.getServerInfoStore().modify(guildId, serverInfo -> {
                ServerInfo.Streamer streamer = findStreamer(serverInfo, query);
                if (streamer == null) return null;
                streamer.channelToPostLiveNotifications = channelToPostLiveNotifications;
                return streamer.discordId;
            });
            if (streamerDiscordId == null) {
                event.reply("No such streamer was found!").setEphemeral(true).queue();
                return;
            }
            bot.getTwitchStreamerWatcher().triggerUpdate();
            if (channelToPostLiveNotifications == 0L) {
                event.reply("Removed override for <@" + streamerDiscordId + ">").setEphemeral(true).queue();
            } else {
                event.reply("Set override for <@" + streamerDiscordId + "> to <#" + channelToPostLiveNotifications + ">").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    static void setStreamerRole(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            long guildId = event.getGuild().getIdLong();
            if (!bot.getServerInfoStore().get(guildId).enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            OptionMapping roleMapping = event.getInteraction().getOption("role");
            long roleForLiveUsers = roleMapping == null ? 0L : roleMapping.getAsLong();
            bot.getServerInfoStore().modify(guildId, serverInfo -> serverInfo.roleForLiveUsers = roleForLiveUsers);
            event.reply("Streamer role was set to <@&" + roleForLiveUsers + ">").setEphemeral(true).queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    static void setLiveChannel(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            long guildId = event.getGuild().getIdLong();
            if (!bot.getServerInfoStore().get(guildId).enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            OptionMapping channelMapping = event.getInteraction().getOption("channel");
            long channelToPostLiveNotifications = getChannelId(channelMapping);
            bot.getServerInfoStore().modify(guildId, serverInfo -> serverInfo.channelToPostLiveNotifications = channelToPostLiveNotifications);
            event.reply("Live channel was set to <#" + channelToPostLiveNotifications + ">").setEphemeral(true).queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    static void setDeleteOnOffline(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            long guildId = event.getGuild().getIdLong();
            if (!bot.getServerInfoStore().get(guildId).enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            OptionMapping deleteMapping = event.getInteraction().getOption("delete");
            boolean deleteNotificationsOnOffline = deleteMapping.getAsBoolean();
            bot.getServerInfoStore().modify(guildId, serverInfo -> serverInfo.deleteNotificationsOnOffline = deleteNotificationsOnOffline);
            event.reply("Set delete notifications on offline: " + deleteNotificationsOnOffline).setEphemeral(true).queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    static void setLiveMessage(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            long guildId = event.getGuild().getIdLong();
            if (!bot.getServerInfoStore().get(guildId).enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            OptionMapping messageMapping = event.getInteraction().getOption("message");
            String message = messageMapping == null ? null : messageMapping.getAsString();
            OptionMapping discordNameMapping = event.getInteraction().getOption("discord-name");
            User discordUser = discordNameMapping == null ? null : discordNameMapping.getAsUser();
            if (message != null) {
                message = message.replace("\\n", "\n");
                if (message.equals("default") || message.equals("reset")) {
                    message = null;
                } else if (message.equals("help")) {
                    event.reply("Available variables: ${game_name}, ${stream_name}, ${streamer_name}, ${twitch_link}, ${discord_name}\n\nTo insert line breaks in a message, use \\n").setEphemeral(true).queue();
                    return;
                }
            }
            String template = message;
            String replyMessage = bot.getServerInfoStore().modify(guildId, serverInfo -> {
                ServerInfo.Streamer streamer = null;
                if (discordUser != null) {
                    for (ServerInfo.Streamer s : serverInfo.streamers) {
                        if (s.discordId == discordUser.getIdLong()) {
                            streamer = s;
                            break;
                        }
                    }
                    if (streamer == null) {
                        return "Specified user is not added as a streamer.";
                    }
                }
                if (streamer != null) {
                    streamer.liveNotificationTemplate = template;
                    return "Updated user-specific notification template for <@" + discordUser.getIdLong() + ">!";
                } else {
                    serverInfo.liveNotificationTemplate = template;
                    return "Updated server-wide notification template!";
                }
            });
            event.reply(replyMessage).setEphemeral(true).queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    static void getStreamerList(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            ServerInfo serverInfo = bot.getServerInfoStore().get(event.getGuild().getIdLong());
            if (!serverInfo.enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            List<String> ids = new ArrayList<>();
            for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                ids.add(streamer.twitchId);
            }
            Map<String, JsonObject> multipleStreamerInfosByIds = bot.getTwitchStreamerWatcher().getMultipleStreamerInfosByIds(ids);
            TreeSet<String> alphabeticalStreamers = new TreeSet<>(String::compareToIgnoreCase);
            Map<String, JsonObject> streamersByName = new HashMap<>();
            for (Map.Entry<String,JsonObject> entry : multipleStreamerInfosByIds.entrySet()) {
                String streamerName = entry.getValue().get("display_name").getAsString();
                alphabeticalStreamers.add(streamerName);
                streamersByName.put(streamerName, entry.getValue());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Streamers in this server:\n\n");
            for (String streamerName : alphabeticalStreamers) {
                JsonObject jsonObject = streamersByName.get(streamerName);
                String twitchId = jsonObject.get("id").getAsString();
                ServerInfo.Streamer streamer = null;
                for (ServerInfo.Streamer s : serverInfo.streamers) {
                    if (twitchId.equals(s.twitchId)) {
                        streamer = s;
                        break;
                    }
                }
                sb.append("- [");
                sb.append(streamerName);
                sb.append("](");
                sb.append("<https://www.twitch.tv/").append(streamerName);
                sb.append(">)\n");
                if (streamer != null) {
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
            }
            if (ids.isEmpty()) {
                sb.append("Nobody is here! Add someone using `/addstreamer`!");
            }
            String message = sb.toString();
            event.reply(message).setEphemeral(true).queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    private static long getChannelId(OptionMapping channelMapping) {
        if (channelMapping == null) return 0L;
        try {
            return channelMapping.getAsLong();
        } catch (NumberFormatException e) {
            // This is to get around a bug in JDA where it will sometimes include the <# part of the channel ID when
            // parsing the 64bit integer wrapped around <# and >
            String string = channelMapping.getAsString();
            int offset = string.indexOf("#") + 1;
            int endOffset = string.indexOf(">");
            if (endOffset == -1) endOffset = string.length();
            return Long.parseLong(string.substring(offset, endOffset));
        }
    }
}

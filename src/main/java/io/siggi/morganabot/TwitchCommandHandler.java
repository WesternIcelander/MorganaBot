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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class TwitchCommandHandler {
    static void addStreamer(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            ServerInfo serverInfo = bot.getServerInfo(event.getGuild().getIdLong());
            if (!serverInfo.enabledTwitchTracking) {
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
            for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                if (streamerId.equals(streamer.twitchId)) {
                    event.reply("The specified Twitch user is already added as a streamer!").setEphemeral(true).queue();
                    return;
                } else if (discordUserId != 0L && discordUserId == streamer.discordId) {
                    event.reply("The specified Discord user is already added as a streamer!").setEphemeral(true).queue();
                    return;
                }
            }
            ServerInfo.Streamer streamer = new ServerInfo.Streamer(discordUserId, streamerId);
            streamer.channelToPostLiveNotifications = selectedChannel;
            serverInfo.streamers.add(streamer);
            serverInfo.save();
            bot.getTwitchStreamerWatcher().triggerUpdate();
            event.reply("Added " + Util.markdownEscape(streamerUsername) + " / " + "<@" + discordUserId + "> as a Twitch streamer" + (selectedChannel != 0L ? (" in <#" + selectedChannel + ">") : "")).setEphemeral(true).queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    private static ServerInfo.Streamer getStreamer(MorganaBot bot, ServerInfo serverInfo, OptionMapping twitchMapping, OptionMapping discordMapping, IReplyCallback event) throws IOException {
        String twitchName = twitchMapping == null ? null : twitchMapping.getAsString();
        User discordUser = discordMapping == null ? null : discordMapping.getAsUser();
        if (twitchName == null && discordUser == null) {
            event.reply("You need to specify either their Twitch name or Discord name!").setEphemeral(true).queue();
            return null;
        }
        long discordUserId = discordUser == null ? 0L : discordUser.getIdLong();
        JsonObject streamerInfoByUsername = twitchName == null ? null : bot.getTwitchStreamerWatcher().getStreamerInfoByUsername(twitchName);
        String streamerId = streamerInfoByUsername == null ? null : streamerInfoByUsername.get("id").getAsString();
        for (Iterator<ServerInfo.Streamer> it = serverInfo.streamers.iterator(); it.hasNext(); ) {
            ServerInfo.Streamer streamer = it.next();
            if (streamer.discordId == discordUserId || (streamer.twitchId != null && streamer.twitchId.equals(streamerId))) {
                return streamer;
            }
        }
        event.reply("No such streamer was found!").setEphemeral(true).queue();
        return null;
    }

    static void deleteStreamer(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            ServerInfo serverInfo = bot.getServerInfo(event.getGuild().getIdLong());
            if (!serverInfo.enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            OptionMapping twitchMapping = event.getInteraction().getOption("twitch-name");
            OptionMapping discordMapping = event.getInteraction().getOption("discord-name");
            ServerInfo.Streamer streamer = getStreamer(bot, serverInfo, twitchMapping, discordMapping, event);
            if (streamer == null) {
                return;
            }
            serverInfo.streamers.remove(streamer);
            serverInfo.save();
            bot.getTwitchStreamerWatcher().triggerUpdate();
            event.reply("Deleted <@" + streamer.discordId + ">").setEphemeral(true).queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    static void setChannelOverride(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            ServerInfo serverInfo = bot.getServerInfo(event.getGuild().getIdLong());
            if (!serverInfo.enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            OptionMapping twitchMapping = event.getInteraction().getOption("twitch-name");
            OptionMapping discordMapping = event.getInteraction().getOption("discord-name");
            OptionMapping channelMapping = event.getInteraction().getOption("channel");
            long channelToPostLiveNotifications = getChannelId(channelMapping);
            ServerInfo.Streamer streamer = getStreamer(bot, serverInfo, twitchMapping, discordMapping, event);
            if (streamer == null) {
                return;
            }
            streamer.channelToPostLiveNotifications = channelToPostLiveNotifications;
            serverInfo.save();
            bot.getTwitchStreamerWatcher().triggerUpdate();
            if (channelToPostLiveNotifications == 0L) {
                event.reply("Removed override for <@" + streamer.discordId + ">").setEphemeral(true).queue();
            } else {
                event.reply("Set override for <@" + streamer.discordId + "> to <#" + channelToPostLiveNotifications + ">").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    static void setStreamerRole(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            ServerInfo serverInfo = bot.getServerInfo(event.getGuild().getIdLong());
            if (!serverInfo.enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            OptionMapping roleMapping = event.getInteraction().getOption("role");
            serverInfo.roleForLiveUsers = roleMapping == null ? 0L : roleMapping.getAsLong();
            serverInfo.save();
            event.reply("Streamer role was set to <@&" + serverInfo.roleForLiveUsers + ">").setEphemeral(true).queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    static void setLiveChannel(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            ServerInfo serverInfo = bot.getServerInfo(event.getGuild().getIdLong());
            if (!serverInfo.enabledTwitchTracking) {
                event.reply("This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.").setEphemeral(true).queue();
                return;
            }
            OptionMapping channelMapping = event.getInteraction().getOption("channel");
            serverInfo.channelToPostLiveNotifications = getChannelId(channelMapping);
            serverInfo.save();
            event.reply("Live channel was set to <#" + serverInfo.channelToPostLiveNotifications + ">").setEphemeral(true).queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command, try again!").setEphemeral(true).queue();
        }
    }

    static void getStreamerList(MorganaBot bot, SlashCommandInteractionEvent event) {
        try {
            ServerInfo serverInfo = bot.getServerInfo(event.getGuild().getIdLong());
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
            sb.append("Streamers in this server:\n");
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
                sb.append("\n");
                sb.append(Util.markdownEscape(streamerName));
                sb.append(" / ");
                if (streamer != null) {
                    if (streamer.discordId == 0L) {
                        sb.append("Discord user not set");
                    } else {
                        sb.append("<@").append(streamer.discordId).append(">");
                    }
                    if (streamer.channelToPostLiveNotifications != 0L) {
                        sb.append(", override: <#").append(streamer.channelToPostLiveNotifications).append(">");
                    }
                } else {
                    sb.append("?");
                }
            }
            if (ids.isEmpty()) {
                sb.append("\nNobody is here! Add someone using `/addstreamer`!");
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

package io.siggi.morganabot;

import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.util.Util;

/**
 * Fills in a live message template. Shared by posting and by Diagnose's length check, so both see the same text.
 */
public final class LiveMessage {

    public static final String DEFAULT_TEMPLATE = "${streamer_name} has gone live on Twitch, playing ${game_name}! Check 'em out!\n${twitch_link}";

    /** Discord's maximum message length. */
    public static final int MAX_LENGTH = 2000;

    private LiveMessage() {
    }

    /**
     * The template a streamer's posts use: their own, else the server-wide one, else the built-in default.
     */
    public static String templateFor(ServerInfo serverInfo, ServerInfo.Streamer streamer) {
        if (streamer != null && streamer.liveNotificationTemplate != null) return streamer.liveNotificationTemplate;
        if (serverInfo.liveNotificationTemplate != null) return serverInfo.liveNotificationTemplate;
        return DEFAULT_TEMPLATE;
    }

    /**
     * @param gameName     the game, as Twitch gives it (escaped here)
     * @param streamName   the stream title, as Twitch gives it (escaped here)
     * @param streamerName the broadcaster's name, as Twitch gives it (escaped here; also used for the link)
     * @param discordId    the linked Discord user, or 0
     */
    public static String fill(String template, String gameName, String streamName, String streamerName, long discordId) {
        String discordMention = discordId == 0L ? "?" : ("<@" + discordId + ">");
        return template
                .replace("${game_name}", Util.markdownEscape(gameName))
                .replace("${stream_name}", Util.markdownEscape(streamName))
                .replace("${streamer_name}", Util.markdownEscape(streamerName))
                .replace("${twitch_link}", "https://www.twitch.tv/" + streamerName)
                .replace("${discord_name}", discordMention);
    }
}

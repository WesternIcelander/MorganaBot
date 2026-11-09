package io.siggi.morganabot;

import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.util.Util;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills in a live message template. Shared by posting and by Diagnose's length check, so both see the same text.
 * <p>
 * Twitch can never ping: values from Twitch (stream title, game, streamer name) get a zero-width space after every
 * {@code @}, and posts only allow the mentions written in the template text itself ({@link Filled#mentions()}). The
 * template text is never altered.
 */
public final class LiveMessage {

    public static final String DEFAULT_TEMPLATE = "${streamer_name} has gone live on Twitch, playing ${game_name}! Check 'em out!\n${twitch_link}";

    /** Discord's maximum message length. */
    public static final int MAX_LENGTH = 2000;

    // An example Twitch stream title at Twitch's 140-character maximum, plus long game and streamer names, for the
    // template length checks (Diagnose and saving).
    public static final String EXAMPLE_TITLE = "A very long example stream title, written to fill the whole 140 characters "
            + "that Twitch allows for a stream title, just like this one, okay!!";
    public static final String EXAMPLE_GAME = "Example Game With A Rather Long Name: Deluxe Edition";
    public static final String EXAMPLE_STREAMER = "an_example_streamer_name_";
    /** A Discord user ID as long as they get, for the length check. */
    private static final long EXAMPLE_DISCORD_ID = 9_000_000_000_000_000_000L;

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{(game_name|stream_name|streamer_name|twitch_link|discord_name)}");

    private LiveMessage() {
    }

    /**
     * A filled-in message and the mentions it may ping.
     *
     * @param mentions the mentions written in the template, plus the linked user if the template uses
     *                 {@code ${discord_name}}
     */
    public record Filled(String text, Mentions mentions, Set<Long> users) {
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
     * {@link #fill(String, String, String, String, String, long)} with the display name used for the link too.
     */
    public static String fill(String template, String gameName, String streamName, String streamerName, long discordId) {
        return fill(template, gameName, streamName, streamerName, streamerName, discordId);
    }

    /**
     * @param gameName      the game, as Twitch gives it (escaped and defused here)
     * @param streamName    the stream title, as Twitch gives it (escaped and defused here)
     * @param streamerName  the broadcaster's display name, as Twitch gives it (escaped and defused here)
     * @param streamerLogin the broadcaster's login, for {@code ${twitch_link}}
     * @param discordId     the linked Discord user, or 0
     */
    public static String fill(String template, String gameName, String streamName, String streamerName, String streamerLogin, long discordId) {
        // One pass, so text from Twitch that looks like a variable isn't filled in itself.
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "game_name" -> twitchValue(gameName);
                case "stream_name" -> twitchValue(streamName);
                case "streamer_name" -> twitchValue(streamerName);
                case "twitch_link" -> "https://www.twitch.tv/" + (streamerLogin == null ? "" : streamerLogin);
                default -> discordId == 0L ? "?" : ("<@" + discordId + ">");
            };
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Fills in the template for posting, with the mentions it may ping: exactly those written in the template text.
     */
    public static Filled build(String template, String gameName, String streamName, String streamerName, String streamerLogin, long discordId) {
        String text = fill(template, gameName, streamName, streamerName, streamerLogin, discordId);
        Mentions mentions = Mentions.parse(template);
        Set<Long> users = new LinkedHashSet<>(mentions.users());
        if (discordId != 0L && template.contains("${discord_name}")) users.add(discordId);
        return new Filled(text, mentions, users);
    }

    /**
     * How long the template gets with long example Twitch values, to check it against {@link #MAX_LENGTH}.
     */
    public static int exampleLength(String template) {
        return fill(template, EXAMPLE_GAME, EXAMPLE_TITLE, EXAMPLE_STREAMER, EXAMPLE_STREAMER, EXAMPLE_DISCORD_ID).length();
    }

    /**
     * A value from Twitch: Markdown escaped, and a zero-width space after every {@code @}, so {@code @everyone},
     * {@code @here}, {@code <@&id>} and {@code <@id>} in it show as text and can't ping.
     */
    static String twitchValue(String value) {
        if (value == null) return "";
        return Util.markdownEscape(value).replace("@", "@​");
    }
}

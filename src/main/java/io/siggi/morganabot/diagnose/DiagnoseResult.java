package io.siggi.morganabot.diagnose;

import java.util.List;

/**
 * Everything Diagnose found for one server: server-wide checks first, then one group per streamer. Plain fields so
 * it serializes to JSON as is.
 */
public final class DiagnoseResult {

    public final long guildId;
    public final long time;
    public final boolean twitchEnabled;
    public final List<Check> server;
    public final List<StreamerResult> streamers;
    /** The worst level of the server-wide checks. */
    public final Check.Level serverLevel;
    public final int streamersOk;
    public final int streamersWithWarnings;
    public final int streamersWithErrors;
    /** e.g. "12 streamers checked: 9 OK, 2 warnings, 1 error". */
    public final String summary;

    public DiagnoseResult(long guildId, long time, boolean twitchEnabled, List<Check> server, List<StreamerResult> streamers) {
        this.guildId = guildId;
        this.time = time;
        this.twitchEnabled = twitchEnabled;
        this.server = List.copyOf(server);
        this.streamers = List.copyOf(streamers);
        this.serverLevel = worst(server);
        int ok = 0, warnings = 0, errors = 0;
        for (StreamerResult streamer : streamers) {
            switch (streamer.level) {
                case OK -> ok++;
                case WARNING -> warnings++;
                case ERROR -> errors++;
            }
        }
        this.streamersOk = ok;
        this.streamersWithWarnings = warnings;
        this.streamersWithErrors = errors;
        this.summary = streamers.size() + " streamer" + (streamers.size() == 1 ? "" : "s") + " checked: "
                + ok + " OK, " + warnings + " warning" + (warnings == 1 ? "" : "s") + ", "
                + errors + " error" + (errors == 1 ? "" : "s");
    }

    /** The streamer's result, or null if they weren't checked. */
    public StreamerResult streamer(String twitchId) {
        for (StreamerResult streamer : streamers) {
            if (streamer.twitchId.equals(twitchId)) return streamer;
        }
        return null;
    }

    static Check.Level worst(List<Check> checks) {
        Check.Level level = Check.Level.OK;
        for (Check check : checks) level = level.worst(check.level);
        return level;
    }

    /**
     * One streamer's checks.
     */
    public static final class StreamerResult {
        public final String twitchId;
        /** Twitch login, or null if it couldn't be looked up. */
        public final String twitchLogin;
        /** Linked Discord user, or 0. */
        public final long discordId;
        /** The channel their next post would go to, or 0 if none. */
        public final long channelId;
        /** Where it would post and why, e.g. "#live (server default)". */
        public final String channel;
        public final Check.Level level;
        public final List<Check> checks;

        public StreamerResult(String twitchId, String twitchLogin, long discordId, long channelId, String channel, List<Check> checks) {
            this.twitchId = twitchId;
            this.twitchLogin = twitchLogin;
            this.discordId = discordId;
            this.channelId = channelId;
            this.channel = channel;
            this.checks = List.copyOf(checks);
            this.level = worst(checks);
        }
    }
}

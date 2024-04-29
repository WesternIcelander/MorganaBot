package io.siggi.morganabot.config;

import java.util.ArrayList;
import java.util.List;

public class ServerInfo extends Data {

    public boolean enabledTwitchTracking = false;
    public long channelToPostLiveNotifications;
    public long roleForLiveUsers;
    public final List<Streamer> streamers = new ArrayList<>();
    public String liveNotificationTemplate;
    public long streamNotificationCooldown = 1000L * 60L * 30L;

    public static class Streamer {
        public Streamer() {
        }
        public Streamer(long discordId, String twitchId) {
            this.discordId = discordId;
            this.twitchId = twitchId;
        }
        public long channelToPostLiveNotifications;
        public long discordId;
        public String twitchId;
        public String liveNotificationTemplate;
        public long startedStreaming;
        public long stoppedStreaming;
    }
}

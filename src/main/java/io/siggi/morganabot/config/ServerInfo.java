package io.siggi.morganabot.config;

import java.util.ArrayList;
import java.util.List;

public class ServerInfo extends Data {

    public static final int DEFAULT_SELF_SERVICE_REMOVE_AFTER_DAYS = 7;

    public boolean enabledTwitchTracking = false;
    public long channelToPostLiveNotifications;
    public boolean deleteNotificationsOnOffline;
    public long roleForLiveUsers;
    public final List<Streamer> streamers = new ArrayList<>();
    public String liveNotificationTemplate;
    public long streamNotificationCooldown = 1000L * 60L * 30L;
    /**
     * Roles whose members may add themselves with {@code /settwitchchannel}, each with an optional channel override.
     * Empty (or missing in the file) turns self-service off. Order doesn't matter: Discord's role order decides which
     * one applies to a member.
     */
    public List<SelfServiceRole> selfServiceRoles = new ArrayList<>();
    /**
     * Self-added streamers who have been without a self-service role for this many days are removed. 0 means never.
     */
    public int selfServiceRemoveAfterDays = DEFAULT_SELF_SERVICE_REMOVE_AFTER_DAYS;

    /**
     * The self-service roles, never null (a hand-edited file may have {@code "selfServiceRoles": null}).
     */
    public List<SelfServiceRole> selfServiceRoles() {
        if (selfServiceRoles == null) selfServiceRoles = new ArrayList<>();
        return selfServiceRoles;
    }

    public static class SelfServiceRole {
        public long roleId;
        /**
         * The channel live posts go to for members whose highest self-service role is this one, or 0 for the
         * server's default live channel.
         */
        public long channelId;

        public SelfServiceRole() {
        }

        public SelfServiceRole(long roleId, long channelId) {
            this.roleId = roleId;
            this.channelId = channelId;
        }
    }

    public static class Streamer {
        public Streamer() {
        }
        public Streamer(long discordId, String twitchId) {
            this.discordId = discordId;
            this.twitchId = twitchId;
        }
        public long channelToPostLiveNotifications;
        public long lastLiveNotificationId;
        public long discordId;
        public String twitchId;
        public String liveNotificationTemplate;
        public long startedStreaming;
        public long stoppedStreaming;
        /**
         * Added by the member themself with {@code /settwitchchannel}. Only these entries follow the member's roles.
         */
        public boolean selfService;
        /**
         * Discord user who created the entry, or 0 if unknown (entries from before this was recorded).
         */
        public long addedBy;
        /**
         * When a self-added streamer lost their last self-service role (or left the server), or 0 if they still
         * have one. While set, nothing is posted for them.
         */
        public long selfServiceLostAt;
        /**
         * An admin set this self-added streamer's channel by hand, so it no longer follows their roles.
         */
        public boolean channelOverridePinned;

        public boolean isSelfServicePaused() {
            return selfService && selfServiceLostAt != 0L;
        }
    }
}

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
     * Roles whose members get full dashboard access, like members with Discord's Administrator permission. Missing
     * means none.
     */
    public List<Long> adminRoles = new ArrayList<>();
    /**
     * Which non-admin streamers may write their own live message, and which pings it may contain. Missing means
     * nobody but admins can.
     */
    public List<MessageCustomizationRule> messageCustomizationRules = new ArrayList<>();

    /**
     * The self-service roles, never null (a hand-edited file may have {@code "selfServiceRoles": null}).
     */
    public List<SelfServiceRole> selfServiceRoles() {
        if (selfServiceRoles == null) selfServiceRoles = new ArrayList<>();
        return selfServiceRoles;
    }

    /**
     * The admin roles, never null.
     */
    public List<Long> adminRoles() {
        if (adminRoles == null) adminRoles = new ArrayList<>();
        return adminRoles;
    }

    /**
     * The message customization rules, never null.
     */
    public List<MessageCustomizationRule> messageCustomizationRules() {
        if (messageCustomizationRules == null) messageCustomizationRules = new ArrayList<>();
        return messageCustomizationRules;
    }

    /**
     * Members with {@link #roleId} may edit their own live message. {@link #allowEveryone} lets them use
     * {@code @everyone} and {@code @here}; {@link #allowedMentionRoles} are the roles they may mention. A member with
     * several matching rules gets all of them combined.
     */
    public static class MessageCustomizationRule {
        public long roleId;
        public boolean allowEveryone;
        public List<Long> allowedMentionRoles = new ArrayList<>();

        public MessageCustomizationRule() {
        }

        public MessageCustomizationRule(long roleId, boolean allowEveryone, List<Long> allowedMentionRoles) {
            this.roleId = roleId;
            this.allowEveryone = allowEveryone;
            this.allowedMentionRoles = allowedMentionRoles == null ? new ArrayList<>() : new ArrayList<>(allowedMentionRoles);
        }

        /**
         * The allowed role mentions, never null.
         */
        public List<Long> allowedMentionRoles() {
            if (allowedMentionRoles == null) allowedMentionRoles = new ArrayList<>();
            return allowedMentionRoles;
        }
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
        /**
         * Discord user who last saved {@link #liveNotificationTemplate}, or 0 if unknown. Only for display and audit;
         * posting doesn't check it.
         */
        public long templateSetBy;

        public boolean isSelfServicePaused() {
            return selfService && selfServiceLostAt != 0L;
        }
    }
}

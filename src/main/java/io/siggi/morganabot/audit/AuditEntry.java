package io.siggi.morganabot.audit;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One line of a server's audit log ({@code data/servers/<guildId>-audit.jsonl}).
 * <p>
 * Channels, roles, users and Twitch accounts are stored with their ID plus their name at the time, so an entry still
 * makes sense after one of them is renamed or deleted. Names are filled in by {@link AuditLog#record} if the code that
 * made the change only knew the ID.
 */
public class AuditEntry {

    // Entry types. Strings (not an enum) so an older build can still read entries with types it doesn't know.
    public static final String LIVE_CHANNEL = "liveChannel";
    public static final String LIVE_ROLE = "liveRole";
    public static final String DELETE_ON_OFFLINE = "deleteOnOffline";
    public static final String NOTIFICATION_COOLDOWN = "notificationCooldown";
    public static final String SERVER_TEMPLATE = "serverTemplate";
    public static final String STREAMER_ADD = "streamerAdd";
    public static final String STREAMER_REMOVE = "streamerRemove";
    public static final String STREAMER_CHANNEL_OVERRIDE = "streamerChannelOverride";
    public static final String STREAMER_DISCORD_USER = "streamerDiscordUser";
    public static final String STREAMER_TEMPLATE = "streamerTemplate";
    public static final String STREAMER_CHANNEL_PIN = "streamerChannelPin";
    public static final String SELF_SERVICE_SET = "selfServiceSet";
    public static final String SELF_SERVICE_REMOVE = "selfServiceRemove";
    public static final String SELF_SERVICE_PAUSE = "selfServicePause";
    public static final String SELF_SERVICE_RESUME = "selfServiceResume";
    public static final String SELF_SERVICE_ROLES = "selfServiceRoles";
    public static final String SELF_SERVICE_EXPIRY = "selfServiceExpiry";
    public static final String ADMIN_ROLES = "adminRoles";
    public static final String MESSAGE_RULES = "messageRules";
    /** {@code enabledTwitchTracking} turned on or off (super admins only). */
    public static final String TWITCH_TRACKING = "twitchTracking";

    // Super-admin-only entry types (always written with VISIBILITY_SUPER_ADMIN; subject is the server).
    /** A super admin removed the bot from the server (change {@code blocked} if it was blocked too). */
    public static final String SERVER_REMOVE = "serverRemove";
    /** A super admin blocked the server, but leaving it failed (the bot leaves on its next join or startup). */
    public static final String SERVER_BLOCK = "serverBlock";
    /** A super admin lifted the server's block. */
    public static final String SERVER_UNBLOCK = "serverUnblock";
    /**
     * A blocked server added the bot back and it left straight away. Attempts within
     * {@link AuditLog#JOIN_COMBINE_WINDOW} of each other are combined ({@link #count}, {@link #firstTime}).
     */
    public static final String BLOCKED_JOIN = "blockedJoin";
    /** The bot was still in a blocked server at startup and left it. */
    public static final String BLOCKED_STARTUP_LEAVE = "blockedStartupLeave";

    public static final String VISIBILITY_SERVER = "server";
    public static final String VISIBILITY_SUPER_ADMIN = "superAdmin";

    /**
     * When the change was made, in milliseconds since the epoch.
     */
    public long time;
    public String type;
    public Actor actor;
    /**
     * {@link #VISIBILITY_SERVER} (shown to the server's dashboard) or {@link #VISIBILITY_SUPER_ADMIN} (only shown to
     * super admins).
     */
    public String visibility = VISIBILITY_SERVER;
    /**
     * The streamer the change is about (a {@link Value#TWITCH} value), or null for server-wide settings.
     */
    public Value streamer;
    /**
     * What else the change is about, if anything: e.g. the self-service role whose channel changed. Null otherwise.
     */
    public Value subject;
    /**
     * What changed, as old → new. Settings that didn't actually change are left out.
     */
    public List<Change> changes = new ArrayList<>();
    /**
     * Optional free text, e.g. a reason.
     */
    public String note;
    /**
     * Facts about the event that aren't changes, e.g. a blocked server's owner and member count. Null if none (most
     * entries).
     */
    public List<Fact> facts;
    /**
     * How many times this event happened, for combined entries ({@link #BLOCKED_JOIN}); null means once.
     */
    public Integer count;
    /**
     * When a combined entry first happened; {@link #time} is the latest. Null if not combined.
     */
    public Long firstTime;

    public AuditEntry() {
    }

    public AuditEntry(String type) {
        this.type = type;
    }

    /**
     * Adds a change if {@code oldValue} and {@code newValue} differ.
     *
     * @return this
     */
    public AuditEntry change(String field, Value oldValue, Value newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            changes.add(new Change(field, oldValue, newValue));
        }
        return this;
    }

    /**
     * Adds a fact (skipped if {@code value} is null).
     *
     * @return this
     */
    public AuditEntry fact(String field, Value value) {
        if (value == null) return this;
        if (facts == null) facts = new ArrayList<>();
        facts.add(new Fact(field, value));
        return this;
    }

    /**
     * Marks this entry as only visible to super admins.
     *
     * @return this
     */
    public AuditEntry superAdminOnly() {
        visibility = VISIBILITY_SUPER_ADMIN;
        return this;
    }

    /**
     * How many times this happened (1 unless combined).
     */
    public int times() {
        return count == null || count < 1 ? 1 : count;
    }

    public boolean hasChanges() {
        return !changes.isEmpty();
    }

    public boolean isSuperAdminOnly() {
        return VISIBILITY_SUPER_ADMIN.equals(visibility);
    }

    public static class Change {
        public String field;
        @SerializedName("old")
        public Value oldValue;
        @SerializedName("new")
        public Value newValue;

        public Change() {
        }

        public Change(String field, Value oldValue, Value newValue) {
            this.field = field;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }

    /**
     * A fact about an event, e.g. {@code owner} → a user.
     */
    public static class Fact {
        public String field;
        public Value value;

        public Fact() {
        }

        public Fact(String field, Value value) {
            this.field = field;
            this.value = value;
        }
    }

    /**
     * A single value in a change. Null stands for "unset". Equality only looks at kind, ID and value, not the name, so
     * a rename alone never counts as a change.
     */
    public static class Value {
        public static final String CHANNEL = "channel";
        public static final String ROLE = "role";
        public static final String USER = "user";
        public static final String TWITCH = "twitch";
        public static final String TEXT = "text";
        public static final String BOOLEAN = "boolean";
        public static final String NUMBER = "number";
        public static final String SERVER = "server";

        public String kind;
        /**
         * Discord snowflake or Twitch user ID, for channels, roles, users and Twitch accounts.
         */
        public String id;
        /**
         * Name at the time of the change, if known: channel or role name, Discord username, Twitch login.
         */
        public String name;
        /**
         * The value itself, for text, boolean and number kinds.
         */
        public String value;

        public Value() {
        }

        private Value(String kind, String id, String name, String value) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.value = value;
        }

        /**
         * @return the channel, or null for 0 (unset)
         */
        public static Value channel(long id) {
            return id == 0L ? null : new Value(CHANNEL, Long.toString(id), null, null);
        }

        /**
         * @return the role, or null for 0 (unset)
         */
        public static Value role(long id) {
            return id == 0L ? null : new Value(ROLE, Long.toString(id), null, null);
        }

        /**
         * @return the Discord user, or null for 0 (unset)
         */
        public static Value user(long id) {
            return id == 0L ? null : new Value(USER, Long.toString(id), null, null);
        }

        /**
         * @param login the Twitch login, or null if not known (it's looked up when the entry is recorded)
         * @return the Twitch account, or null if {@code id} is null
         */
        public static Value twitch(String id, String login) {
            return id == null ? null : new Value(TWITCH, id, login, null);
        }

        /**
         * @param name the server's name at the time, if known
         * @return the Discord server, or null for 0
         */
        public static Value server(long id, String name) {
            return id == 0L ? null : new Value(SERVER, Long.toString(id), name, null);
        }

        /**
         * @param name the user's name at the time, if known (otherwise it's looked up when the entry is recorded)
         * @return the Discord user, or null for 0 (unset)
         */
        public static Value user(long id, String name) {
            return id == 0L ? null : new Value(USER, Long.toString(id), name, null);
        }

        /**
         * @return the text, or null if {@code text} is null
         */
        public static Value text(String text) {
            return text == null ? null : new Value(TEXT, null, null, text);
        }

        public static Value bool(boolean value) {
            return new Value(BOOLEAN, null, null, Boolean.toString(value));
        }

        public static Value number(long value) {
            return new Value(NUMBER, null, null, Long.toString(value));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Value other)) return false;
            return Objects.equals(kind, other.kind) && Objects.equals(id, other.id) && Objects.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, id, value);
        }

        @Override
        public String toString() {
            if (id == null) return value;
            String prefix = switch (kind == null ? "" : kind) {
                case CHANNEL -> "#";
                case ROLE, USER -> "@";
                default -> "";
            };
            return (name == null ? "" : prefix + name + " ") + "(" + id + ")";
        }
    }
}

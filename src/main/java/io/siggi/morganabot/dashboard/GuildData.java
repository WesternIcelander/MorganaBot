package io.siggi.morganabot.dashboard;

import io.siggi.morganabot.Access;
import io.siggi.morganabot.ServerSettings;
import io.siggi.morganabot.diagnose.DiagnoseResult;

import java.util.List;

/**
 * What the dashboard API needs to know about one Discord server: its channels, roles and members, as plain values.
 * {@link JdaGuildData} is the JDA-backed one; tests use a fake.
 * <p>
 * The value classes have public fields so they're sent as JSON as is (snowflakes as strings, see
 * {@link WebResponses#GSON}).
 */
public interface GuildData {

    /**
     * A channel live posts can go to (text or announcement).
     */
    final class ChannelInfo {
        public static final String TEXT = "text";
        public static final String ANNOUNCEMENT = "announcement";

        public final long id;
        public final String name;
        /** {@link #TEXT} or {@link #ANNOUNCEMENT}. */
        public final String type;
        /** The category it's in, or null. */
        public final String category;
        /** The bot has View Channel and Send Messages there, so live posts work. */
        public final boolean botCanPost;
        /** The bot has Mention Everyone there, so {@code @everyone}, {@code @here} and every role mention ping. */
        public final boolean botCanMentionEveryone;

        public ChannelInfo(long id, String name, String type, String category, boolean botCanPost, boolean botCanMentionEveryone) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.category = category;
            this.botCanPost = botCanPost;
            this.botCanMentionEveryone = botCanMentionEveryone;
        }
    }

    final class RoleInfo {
        public final long id;
        public final String name;
        /** RGB colour, 0 for none. */
        public final int color;
        /** Position in Discord's role order (higher is higher); the list is sorted highest first. */
        public final int position;
        /** It's the {@code @everyone} role. */
        public final boolean everyone;
        /** It belongs to a bot or integration, so nobody can give it out. */
        public final boolean managed;
        public final boolean mentionable;
        /**
         * The bot can give and take this role: it's below the bot's highest role, isn't managed or {@code @everyone},
         * and the bot has Manage Roles.
         */
        public final boolean botCanAssign;
        /** Why {@link #botCanAssign} is false, or null. */
        public final String botCantAssignReason;

        public RoleInfo(long id, String name, int color, int position, boolean everyone, boolean managed,
                        boolean mentionable, boolean botCanAssign, String botCantAssignReason) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.position = position;
            this.everyone = everyone;
            this.managed = managed;
            this.mentionable = mentionable;
            this.botCanAssign = botCanAssign;
            this.botCantAssignReason = botCantAssignReason;
        }
    }

    final class MemberInfo {
        public final long id;
        public final String username;
        /** Server nickname, or global display name, or username. */
        public final String displayName;
        public final String avatarUrl;
        public final boolean bot;

        public MemberInfo(long id, String username, String displayName, String avatarUrl, boolean bot) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
            this.avatarUrl = avatarUrl;
            this.bot = bot;
        }
    }

    long id();

    String name();

    /** The server icon URL, or null. */
    String iconUrl();

    /** Text and announcement channels, in Discord's order. */
    List<ChannelInfo> channels();

    /**
     * A text or announcement channel of this server, or null if there's no such channel here (or it's another kind,
     * see {@link #hasChannel}).
     */
    ChannelInfo channel(long channelId);

    /** Whether this server has a channel (of any kind) with this ID. */
    boolean hasChannel(long channelId);

    /** Every role, highest first. */
    List<RoleInfo> roles();

    /** A role of this server, or null. */
    RoleInfo role(long roleId);

    /** Members whose username, display name or nickname contains {@code query} (or whose ID it is), at most {@code limit}. */
    List<MemberInfo> searchMembers(String query, int limit);

    /** The member, or null if they aren't in the server. May block on a REST lookup. */
    MemberInfo member(long userId);

    /** The member from the member cache only (no REST lookup), or null. For labelling lists. */
    MemberInfo cachedMember(long userId);

    /** How access checks see the member, or null if they aren't in the server (from the member cache only). */
    Access.MemberView memberView(long userId);

    /** The member as self-service sees them, or null if that can't be told right now. */
    ServerSettings.SelfServiceMember selfServiceMember(long userId);

    /** Runs Diagnose now, for every streamer or only {@code onlyTwitchId}. May block on REST member lookups. */
    DiagnoseResult diagnose(String onlyTwitchId);

    /**
     * Finds servers for the API.
     */
    interface Provider {
        /**
         * @return the server, or null if the bot isn't in it
         * @throws ApiError {@link ApiError#discordUnavailable()} if Discord isn't connected
         */
        GuildData guild(long guildId) throws ApiError;

        /** Every server the bot is in, or an empty list if Discord isn't connected. */
        List<GuildData> all();

        /** Whether Discord is connected. */
        boolean discordAvailable();

        /** A Discord user's name (for audit entries), or null if unknown. */
        String userName(long userId);
    }
}

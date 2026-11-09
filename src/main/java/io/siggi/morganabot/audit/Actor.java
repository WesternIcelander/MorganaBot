package io.siggi.morganabot.audit;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

/**
 * Who made a change and where it came from. Stored as part of every {@link AuditEntry}.
 * <p>
 * User IDs are stored as strings, since snowflakes are too big for JSON numbers in many readers (JavaScript included).
 */
public final class Actor {

    public enum Source {
        /**
         * A slash command; {@link #command} holds which one.
         */
        @SerializedName("command") COMMAND,
        /**
         * The web dashboard, by someone with normal access to the server.
         */
        @SerializedName("dashboard") DASHBOARD,
        /**
         * The web dashboard, from a super admin session. Shown to the server as "Bot admin".
         */
        @SerializedName("superAdmin") SUPER_ADMIN,
        /**
         * The bot itself (e.g. automatic clean-up).
         */
        @SerializedName("system") SYSTEM
    }

    /**
     * Discord user ID, or null for {@link Source#SYSTEM}.
     */
    public String userId;
    /**
     * The user's name at the time of the change, or "system".
     */
    public String name;
    public Source source;
    /**
     * The slash command, e.g. {@code /setlivechannel}, for {@link Source#COMMAND}; null otherwise.
     */
    public String command;

    /**
     * For Gson.
     */
    private Actor() {
    }

    private Actor(String userId, String name, Source source, String command) {
        this.userId = userId;
        this.name = name;
        this.source = source;
        this.command = command;
    }

    public static Actor command(long userId, String name, String command) {
        return new Actor(Long.toString(userId), name, Source.COMMAND, command.startsWith("/") ? command : "/" + command);
    }

    public static Actor dashboard(long userId, String name) {
        return new Actor(Long.toString(userId), name, Source.DASHBOARD, null);
    }

    public static Actor superAdmin(long userId, String name) {
        return new Actor(Long.toString(userId), name, Source.SUPER_ADMIN, null);
    }

    public static Actor system() {
        return new Actor(null, "system", Source.SYSTEM, null);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Actor other)) return false;
        return Objects.equals(userId, other.userId) && Objects.equals(name, other.name)
                && source == other.source && Objects.equals(command, other.command);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, name, source, command);
    }

    @Override
    public String toString() {
        return switch (source) {
            case SYSTEM -> "system";
            case COMMAND -> name + " (" + userId + ") via " + command;
            case DASHBOARD -> name + " (" + userId + ") via dashboard";
            case SUPER_ADMIN -> "bot admin " + name + " (" + userId + ")";
        };
    }
}

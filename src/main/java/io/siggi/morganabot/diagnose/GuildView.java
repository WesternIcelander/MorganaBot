package io.siggi.morganabot.diagnose;

import net.dv8tion.jda.api.Permission;

import java.util.EnumSet;
import java.util.Set;

/**
 * What Diagnose needs to know about a guild, from the bot's point of view. In production it's read from the JDA cache
 * ({@link JdaGuildView}); tests build it by hand.
 */
public interface GuildView {

    /** The bot's name in the server, for fix hints ("Give MorganaBot ..."). */
    String botName();

    /** Whether the bot has {@code permission} server-wide (from its roles). */
    boolean botHasPermission(Permission permission);

    /** The channel, or null if there's no such channel in the server. */
    ChannelView channel(long channelId);

    /** The role, or null if there's no such role in the server. */
    RoleView role(long roleId);

    /**
     * Whether the user is in the server: true, false, or null if it couldn't be checked.
     */
    Boolean isMember(long userId);

    enum ChannelKind {
        TEXT, ANNOUNCEMENT, OTHER
    }

    /**
     * @param botPermissions the bot's effective permissions in the channel, channel overrides included
     */
    record ChannelView(long id, String name, ChannelKind kind, Set<Permission> botPermissions) {
        public ChannelView {
            botPermissions = botPermissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(botPermissions);
        }

        public boolean botHas(Permission permission) {
            return botPermissions.contains(permission);
        }

        public boolean canPostIn() {
            return kind != ChannelKind.OTHER;
        }
    }

    /**
     * @param mentionable    "Allow anyone to @mention this role" is on
     * @param managed        the role belongs to an integration or bot, so it can't be given to members
     * @param publicRole     it's the {@code @everyone} role
     * @param botCanInteract the role is below the bot's highest role
     */
    record RoleView(long id, String name, boolean mentionable, boolean managed, boolean publicRole, boolean botCanInteract) {
    }
}

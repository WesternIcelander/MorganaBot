package io.siggi.morganabot;

import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who may do what in a server's dashboard, worked out from the member's current roles and permissions every time it's
 * asked (nothing is cached, so a removed admin role takes effect straight away).
 * <ul>
 *     <li>{@link Level#ADMIN}: Discord's Administrator permission (the owner always has it), one of the server's
 *     {@code adminRoles}, or a super admin.</li>
 *     <li>{@link Level#STREAMER}: anyone else with a streamer entry linked to their Discord account.</li>
 *     <li>{@link Level#NONE}: everyone else.</li>
 * </ul>
 */
public class Access {

    public enum Level {
        ADMIN, STREAMER, NONE
    }

    /**
     * A guild member as access checks see them.
     *
     * @param administrator they have Discord's Administrator permission in the guild (true for the owner)
     * @param roles         their role IDs, including the guild ID for the @everyone role
     */
    public record MemberView(long guildId, long userId, boolean administrator, List<Long> roles) {
        public MemberView {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        /**
         * Someone who isn't in the guild (e.g. a super admin working on another server): no roles, no permissions.
         */
        public static MemberView outsider(long guildId, long userId) {
            return new MemberView(guildId, userId, false, List.of(guildId));
        }
    }

    /**
     * Which mentions someone may add to a live message.
     *
     * @param all      anything goes (admins)
     * @param everyone {@code @everyone} and {@code @here}
     * @param roles    the roles they may mention
     */
    public record MentionPermissions(boolean all, boolean everyone, Set<Long> roles) {
        public static final MentionPermissions ALL = new MentionPermissions(true, true, Set.of());
        public static final MentionPermissions NONE = new MentionPermissions(false, false, Set.of());

        public MentionPermissions {
            roles = roles == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
        }

        public boolean allowsEveryone() {
            return all || everyone;
        }

        public boolean allowsRole(long roleId) {
            return all || roles.contains(roleId);
        }

        /**
         * User mentions: only admins may add them.
         */
        public boolean allowsUsers() {
            return all;
        }
    }

    private final ServerInfoStore store;
    private final Set<Long> superAdmins;

    public Access(ServerInfoStore store, Collection<Long> superAdmins) {
        this.store = store;
        Set<Long> set = new LinkedHashSet<>();
        if (superAdmins != null) {
            for (Long id : superAdmins) {
                if (id != null) set.add(id);
            }
        }
        this.superAdmins = Collections.unmodifiableSet(set);
    }

    /**
     * The first super admin in config, or null if there are none.
     */
    public Long firstSuperAdmin() {
        return superAdmins.isEmpty() ? null : superAdmins.iterator().next();
    }

    public boolean isSuperAdmin(long userId) {
        return superAdmins.contains(userId);
    }

    /**
     * How access checks see a JDA member.
     */
    public static MemberView view(Member member) {
        List<Long> roles = new ArrayList<>();
        for (Role role : member.getRoles()) {
            roles.add(role.getIdLong());
        }
        roles.add(member.getGuild().getIdLong());
        return new MemberView(member.getGuild().getIdLong(), member.getIdLong(),
                member.isOwner() || member.hasPermission(Permission.ADMINISTRATOR), roles);
    }

    // --- with the guild's current settings ---

    public Level of(Member member) {
        return of(view(member));
    }

    public Level of(MemberView member) {
        return level(store.get(member.guildId()), member, isSuperAdmin(member.userId()));
    }

    /**
     * The level for a dashboard request. {@code superAdminSession} is the session's own super admin flag (set at
     * login, e.g. by the console recovery link), which counts as well as the configured list.
     */
    public Level of(MemberView member, boolean superAdminSession) {
        return level(store.get(member.guildId()), member, superAdminSession || isSuperAdmin(member.userId()));
    }

    /**
     * Only Discord Administrators and super admins may change the admin role list, so an admin through an admin role
     * can't add or remove admins.
     */
    public boolean canEditAdminRoles(Member member) {
        return canEditAdminRoles(view(member));
    }

    public boolean canEditAdminRoles(MemberView member) {
        return member.administrator() || isSuperAdmin(member.userId());
    }

    /**
     * Whether they may edit their own live message: admins always; others if they hold any customization rule's
     * role. (Having a streamer entry to edit is checked when saving.)
     */
    public boolean canCustomizeMessage(Member member) {
        return canCustomizeMessage(view(member));
    }

    public boolean canCustomizeMessage(MemberView member) {
        ServerInfo info = store.get(member.guildId());
        if (isAdmin(info, member, isSuperAdmin(member.userId()))) return true;
        return hasRule(info, member.roles());
    }

    /**
     * The mentions they may add: everything for admins, otherwise all their matching rules combined.
     */
    public MentionPermissions allowedMentions(Member member) {
        return allowedMentions(view(member));
    }

    public MentionPermissions allowedMentions(MemberView member) {
        ServerInfo info = store.get(member.guildId());
        if (isAdmin(info, member, isSuperAdmin(member.userId()))) return MentionPermissions.ALL;
        return allowedMentions(info, member.roles());
    }

    // --- the rules ---

    public static Level level(ServerInfo info, MemberView member, boolean superAdmin) {
        if (isAdmin(info, member, superAdmin)) return Level.ADMIN;
        for (ServerInfo.Streamer streamer : info.streamers) {
            if (streamer.discordId != 0L && streamer.discordId == member.userId()) return Level.STREAMER;
        }
        return Level.NONE;
    }

    public static boolean isAdmin(ServerInfo info, MemberView member, boolean superAdmin) {
        if (superAdmin || member.administrator()) return true;
        for (Long roleId : info.adminRoles()) {
            if (roleId != null && roleId != 0L && member.roles().contains(roleId)) return true;
        }
        return false;
    }

    static boolean hasRule(ServerInfo info, List<Long> roles) {
        for (ServerInfo.MessageCustomizationRule rule : info.messageCustomizationRules()) {
            if (rule != null && rule.roleId != 0L && roles.contains(rule.roleId)) return true;
        }
        return false;
    }

    /**
     * All matching rules combined: {@code @everyone}/{@code @here} if any of them allows it, and every role any of
     * them allows. No matching rule means no mentions.
     */
    public static MentionPermissions allowedMentions(ServerInfo info, List<Long> roles) {
        boolean everyone = false;
        Set<Long> allowedRoles = new LinkedHashSet<>();
        for (ServerInfo.MessageCustomizationRule rule : info.messageCustomizationRules()) {
            if (rule == null || rule.roleId == 0L || !roles.contains(rule.roleId)) continue;
            everyone |= rule.allowEveryone;
            for (Long roleId : rule.allowedMentionRoles()) {
                if (roleId != null && roleId != 0L) allowedRoles.add(roleId);
            }
        }
        return new MentionPermissions(false, everyone, allowedRoles);
    }
}

package io.siggi.morganabot.diagnose;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link GuildView} backed by JDA. Channels, roles and permissions come from the cache. Members not in the cache are
 * looked up over REST (blocking), so {@link #isMember} must not be called on a JDA callback thread; the answers are
 * remembered for the lifetime of this view.
 */
public final class JdaGuildView implements GuildView {

    private final Guild guild;
    private final Member self;
    private final Map<Long, Boolean> members = new HashMap<>();

    public JdaGuildView(Guild guild) {
        this.guild = guild;
        this.self = guild.getSelfMember();
    }

    @Override
    public String botName() {
        return self.getEffectiveName();
    }

    @Override
    public boolean botHasPermission(Permission permission) {
        return self.hasPermission(permission);
    }

    @Override
    public ChannelView channel(long channelId) {
        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (channel == null) return null;
        ChannelKind kind = channel instanceof NewsChannel ? ChannelKind.ANNOUNCEMENT
                : channel instanceof TextChannel ? ChannelKind.TEXT
                : ChannelKind.OTHER;
        return new ChannelView(channel.getIdLong(), channel.getName(), kind, self.getPermissions(channel));
    }

    @Override
    public RoleView role(long roleId) {
        Role role = guild.getRoleById(roleId);
        if (role == null) return null;
        return new RoleView(role.getIdLong(), role.getName(), role.isMentionable(), role.isManaged(), role.isPublicRole(),
                self.canInteract(role));
    }

    @Override
    public List<Long> memberRoles(long userId) {
        // Every member is cached (members intent), so no REST lookup is needed.
        Member member = guild.getMemberById(userId);
        if (member == null) return null;
        List<Long> roles = new ArrayList<>();
        for (Role role : member.getRoles()) {
            roles.add(role.getIdLong());
        }
        return roles;
    }

    @Override
    public synchronized Boolean isMember(long userId) {
        if (members.containsKey(userId)) return members.get(userId);
        Boolean result;
        if (guild.getMemberById(userId) != null) {
            result = true;
        } else {
            try {
                result = guild.retrieveMemberById(userId).complete() != null;
            } catch (ErrorResponseException e) {
                result = e.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER || e.getErrorResponse() == ErrorResponse.UNKNOWN_USER
                        ? Boolean.FALSE : null;
            } catch (Exception e) {
                result = null;
            }
        }
        members.put(userId, result);
        return result;
    }
}

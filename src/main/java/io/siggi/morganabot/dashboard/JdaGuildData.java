package io.siggi.morganabot.dashboard;

import io.siggi.morganabot.Access;
import io.siggi.morganabot.SelfServiceListener;
import io.siggi.morganabot.ServerSettings;
import io.siggi.morganabot.diagnose.Diagnose;
import io.siggi.morganabot.diagnose.DiagnoseResult;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@link GuildData} from the JDA cache. Every member is cached (members intent), so member searches don't need
 * Discord's REST API.
 */
public final class JdaGuildData implements GuildData {

    private final Guild guild;
    private final Diagnose diagnose;

    public JdaGuildData(Guild guild, Diagnose diagnose) {
        this.guild = guild;
        this.diagnose = diagnose;
    }

    /**
     * Servers from JDA; Discord counts as unavailable unless JDA is connected.
     */
    public static Provider provider(Supplier<JDA> jdaSupplier, Supplier<Diagnose> diagnose) {
        return new Provider() {
            @Override
            public GuildData guild(long guildId) throws ApiError {
                JDA jda = connected();
                if (jda == null) throw ApiError.discordUnavailable();
                Guild guild = jda.getGuildById(guildId);
                return guild == null ? null : new JdaGuildData(guild, diagnose.get());
            }

            @Override
            public List<GuildData> all() {
                JDA jda = connected();
                if (jda == null) return List.of();
                List<GuildData> list = new ArrayList<>();
                for (Guild guild : jda.getGuilds()) list.add(new JdaGuildData(guild, diagnose.get()));
                return list;
            }

            @Override
            public boolean discordAvailable() {
                return connected() != null;
            }

            @Override
            public String userName(long userId) {
                JDA jda = jdaSupplier.get();
                if (jda == null || userId == 0L) return null;
                User user = jda.getUserById(userId);
                if (user == null) {
                    try {
                        user = jda.retrieveUserById(userId).complete();
                    } catch (Exception e) {
                        return null;
                    }
                }
                return user == null ? null : user.getName();
            }

            private JDA connected() {
                JDA jda = jdaSupplier.get();
                return jda != null && jda.getStatus() == JDA.Status.CONNECTED ? jda : null;
            }
        };
    }

    @Override
    public long id() {
        return guild.getIdLong();
    }

    @Override
    public String name() {
        return guild.getName();
    }

    @Override
    public String iconUrl() {
        return guild.getIconUrl();
    }

    @Override
    public int memberCount() {
        return guild.getMemberCount();
    }

    @Override
    public List<ChannelInfo> channels() {
        List<ChannelInfo> list = new ArrayList<>();
        for (GuildChannel channel : guild.getChannels()) {
            ChannelInfo info = info(channel);
            if (info != null) list.add(info);
        }
        return list;
    }

    @Override
    public ChannelInfo channel(long channelId) {
        return info(guild.getGuildChannelById(channelId));
    }

    @Override
    public boolean hasChannel(long channelId) {
        return guild.getGuildChannelById(channelId) != null;
    }

    private ChannelInfo info(GuildChannel channel) {
        if (channel == null) return null;
        String type = channel instanceof NewsChannel ? ChannelInfo.ANNOUNCEMENT
                : channel instanceof TextChannel ? ChannelInfo.TEXT : null;
        if (type == null) return null;
        Category category = channel instanceof ICategorizableChannel c ? c.getParentCategory() : null;
        EnumSet<Permission> permissions = guild.getSelfMember().getPermissions(channel);
        return new ChannelInfo(channel.getIdLong(), channel.getName(), type, category == null ? null : category.getName(),
                permissions.contains(Permission.VIEW_CHANNEL) && permissions.contains(Permission.MESSAGE_SEND),
                permissions.contains(Permission.MESSAGE_MENTION_EVERYONE));
    }

    @Override
    public List<RoleInfo> roles() {
        List<RoleInfo> list = new ArrayList<>();
        for (Role role : guild.getRoles()) list.add(info(role));
        Role everyone = guild.getPublicRole();
        if (list.stream().noneMatch(r -> r.id == everyone.getIdLong())) list.add(info(everyone));
        return list;
    }

    @Override
    public RoleInfo role(long roleId) {
        return info(guild.getRoleById(roleId));
    }

    @SuppressWarnings("deprecation")
    private RoleInfo info(Role role) {
        if (role == null) return null;
        Member self = guild.getSelfMember();
        String reason = null;
        if (role.isPublicRole()) {
            reason = "It's the @everyone role.";
        } else if (role.isManaged()) {
            reason = "It belongs to a bot or integration.";
        } else if (!self.hasPermission(Permission.MANAGE_ROLES)) {
            reason = "The bot doesn't have the Manage Roles permission.";
        } else if (!self.canInteract(role)) {
            reason = "It's above the bot's highest role.";
        }
        int color = role.getColorRaw() == Role.DEFAULT_COLOR_RAW ? 0 : role.getColorRaw() & 0xFFFFFF;
        return new RoleInfo(role.getIdLong(), role.getName(), color, role.getPosition(), role.isPublicRole(),
                role.isManaged(), role.isMentionable(), reason == null, reason);
    }

    @Override
    public List<MemberInfo> searchMembers(String query, int limit) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return List.of();
        long id = parseId(q);
        List<Member> exact = new ArrayList<>();
        List<Member> prefix = new ArrayList<>();
        List<Member> contains = new ArrayList<>();
        guild.getMemberCache().forEach(member -> {
            if (member.getIdLong() == id) {
                exact.add(member);
                return;
            }
            int best = 0;
            for (String name : names(member)) {
                if (name == null) continue;
                String n = name.toLowerCase(Locale.ROOT);
                if (n.equals(q)) best = Math.max(best, 3);
                else if (n.startsWith(q)) best = Math.max(best, 2);
                else if (n.contains(q)) best = Math.max(best, 1);
            }
            if (best == 3) exact.add(member);
            else if (best == 2) prefix.add(member);
            else if (best == 1) contains.add(member);
        });
        Comparator<Member> byName = Comparator.comparing(m -> m.getEffectiveName().toLowerCase(Locale.ROOT));
        prefix.sort(byName);
        contains.sort(byName);
        List<MemberInfo> result = new ArrayList<>();
        for (List<Member> group : List.of(exact, prefix, contains)) {
            for (Member member : group) {
                if (result.size() >= limit) return result;
                result.add(info(member));
            }
        }
        return result;
    }

    private static List<String> names(Member member) {
        List<String> names = new ArrayList<>();
        names.add(member.getUser().getName());
        names.add(member.getUser().getGlobalName());
        names.add(member.getNickname());
        return names;
    }

    private static long parseId(String q) {
        try {
            return Long.parseLong(q);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static MemberInfo info(Member member) {
        return new MemberInfo(member.getIdLong(), member.getUser().getName(), member.getEffectiveName(),
                member.getEffectiveAvatarUrl(), member.getUser().isBot());
    }

    @Override
    public MemberInfo member(long userId) {
        if (userId <= 0L) return null;
        Member member = guild.getMemberById(userId);
        if (member == null) {
            try {
                member = guild.retrieveMemberById(userId).complete();
            } catch (ErrorResponseException e) {
                return null;
            } catch (RuntimeException e) {
                return null;
            }
        }
        return member == null ? null : info(member);
    }

    @Override
    public MemberInfo cachedMember(long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : info(member);
    }

    @Override
    public Access.MemberView memberView(long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : Access.view(member);
    }

    @Override
    public ServerSettings.SelfServiceMember selfServiceMember(long userId) {
        Member member = guild.getMemberById(userId);
        if (member != null) return SelfServiceListener.of(member);
        return guild.isLoaded() ? ServerSettings.SelfServiceMember.absent(guild.getIdLong(), userId) : null;
    }

    @Override
    public DiagnoseResult diagnose(String onlyTwitchId) {
        return diagnose.run(guild, onlyTwitchId);
    }
}

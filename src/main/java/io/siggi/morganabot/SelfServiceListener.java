package io.siggi.morganabot;

import io.siggi.morganabot.ServerSettings.SelfServiceMember;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.health.Component;
import io.siggi.morganabot.health.Health;
import io.siggi.morganabot.health.Severity;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Keeps self-added streamers in line with their roles as they change: role added or removed, member joined or left.
 * The work (file writes, audit entries with name lookups) runs on its own thread, not on JDA's event thread.
 * <p>
 * Changes made while the bot was offline are caught by {@link #maintain}, which the Twitch watcher runs at startup
 * and every hour.
 */
public class SelfServiceListener extends ListenerAdapter {

    private final MorganaBot bot;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Self-service roles");
        thread.setDaemon(true);
        return thread;
    });

    public SelfServiceListener(MorganaBot bot) {
        this.bot = bot;
    }

    /**
     * The member as self-service sees them: present, with their roles highest first.
     */
    public static SelfServiceMember of(Member member) {
        List<Long> roles = new ArrayList<>();
        for (Role role : member.getRoles()) {
            roles.add(role.getIdLong());
        }
        return new SelfServiceMember(member.getGuild().getIdLong(), member.getIdLong(), member.getUser().getName(), true, roles);
    }

    /**
     * Members from the JDA cache. Every member is cached (members intent, {@code MemberCachePolicy.ALL}), so a member
     * missing from a fully loaded guild has left it.
     */
    public static ServerSettings.Members members(Supplier<JDA> jda) {
        return (guildId, userId) -> {
            JDA current = jda.get();
            if (current == null) return null;
            Guild guild = current.getGuildById(guildId);
            if (guild == null) return null;
            Member member = guild.getMemberById(userId);
            if (member != null) return of(member);
            return guild.isLoaded() ? SelfServiceMember.absent(guildId, userId) : null;
        };
    }

    @Override
    public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
        reconcile(of(event.getMember()));
    }

    @Override
    public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
        reconcile(of(event.getMember()));
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        reconcile(of(event.getMember()));
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        reconcile(SelfServiceMember.absent(event.getGuild().getIdLong(), event.getUser().getIdLong()));
    }

    private void reconcile(SelfServiceMember member) {
        executor.execute(() -> {
            try {
                ServerInfo info = bot.getServerInfoStore().get(member.guildId());
                if (!hasSelfAddedEntry(info, member.userId())) return;
                bot.getServerSettings().reconcileSelfService(member);
            } catch (Exception e) {
                Health.log(Component.BOT, Severity.ERROR, "Could not update self-service streamer " + member.userId()
                        + " in guild " + member.guildId() + ": " + Health.describe(e), e);
            }
        });
    }

    private static boolean hasSelfAddedEntry(ServerInfo info, long userId) {
        for (ServerInfo.Streamer streamer : info.streamers) {
            if (streamer.selfService && streamer.discordId == userId) return true;
        }
        return false;
    }

    /**
     * Checks every self-added streamer in every guild against their current roles, then removes those that have
     * been without a role for too long. Run at startup and hourly (by the Twitch watcher, which syncs subscriptions
     * right after, so removed streamers' subscriptions are dropped).
     *
     * @return whether any streamer was removed
     */
    public static boolean maintain(MorganaBot bot) {
        JDA jda = bot.getJDA();
        if (jda == null) return false;
        boolean removed = false;
        for (Guild guild : jda.getGuilds()) {
            long guildId = guild.getIdLong();
            try {
                ServerSettings settings = bot.getServerSettings();
                settings.reconcileSelfService(guildId);
                if (!settings.expireSelfService(guildId).isEmpty()) removed = true;
            } catch (Exception e) {
                Health.log(Component.BOT, Severity.ERROR, "Self-service check failed for guild " + guildId + ": "
                        + Health.describe(e), e);
            }
        }
        return removed;
    }
}

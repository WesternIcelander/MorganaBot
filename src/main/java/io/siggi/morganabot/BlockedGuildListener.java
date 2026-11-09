package io.siggi.morganabot;

import io.siggi.morganabot.audit.Actor;
import io.siggi.morganabot.audit.AuditEntry;
import io.siggi.morganabot.audit.AuditEntry.Value;
import io.siggi.morganabot.audit.AuditLog;
import io.siggi.morganabot.health.Component;
import io.siggi.morganabot.health.Health;
import io.siggi.morganabot.health.Severity;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.audit.AuditLogEntry;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;

/**
 * Keeps the bot out of servers a super admin blocked ({@code blocked} in the server's file): it leaves straight away
 * when one adds it back ({@link GuildJoinEvent}), and {@link #sweep} leaves any it's still in at startup. Both are
 * written to stdout and to the server's audit log as super-admin-only entries ({@link #auditEntry}), with who added
 * the bot if the bot may read the server's audit log.
 */
public class BlockedGuildListener extends ListenerAdapter {

    /** Why the bot is leaving, for the log line. */
    public enum Reason {
        JOIN("tried to add the bot"),
        STARTUP("was still joined at startup");

        final String text;

        Reason(String text) {
            this.text = text;
        }
    }

    /**
     * What's known about a blocked server when the bot leaves it.
     *
     * @param ownerName   the owner's username, or null if unknown
     * @param inviterId   who added the bot (from the server's audit log), or 0 if unknown
     * @param inviterName their username, or null
     */
    public record Visit(long guildId, String name, long ownerId, String ownerName, int memberCount, long inviterId,
                        String inviterName) {
    }

    /** Records a leave (the audit log in production). */
    public interface Recorder {
        void record(Visit visit, Reason reason);

        Recorder NONE = (visit, reason) -> {
        };
    }

    /** How long to wait for the server's audit log before leaving without knowing who added the bot. */
    static final long INVITER_TIMEOUT_SECONDS = 10L;

    private final LongPredicate blocked;
    private final PrintStream log;
    private final Recorder recorder;

    public BlockedGuildListener(LongPredicate blocked, PrintStream log, Recorder recorder) {
        this.blocked = blocked;
        this.log = log;
        this.recorder = recorder == null ? Recorder.NONE : recorder;
    }

    /**
     * Writes leaves to {@code auditLog}: join attempts combined within {@link AuditLog#JOIN_COMBINE_WINDOW}, startup
     * leaves one entry each.
     */
    public static Recorder auditRecorder(AuditLog auditLog) {
        return (visit, reason) -> {
            AuditEntry entry = auditEntry(visit, reason);
            if (reason == Reason.JOIN) {
                auditLog.recordCombined(visit.guildId(), Actor.system(), entry, AuditLog.JOIN_COMBINE_WINDOW);
            } else {
                auditLog.record(visit.guildId(), Actor.system(), entry);
            }
        };
    }

    /**
     * The super-admin-only audit entry for a leave: the server (name at the time), its owner, member count and who
     * added the bot ("unknown" if that couldn't be found).
     */
    static AuditEntry auditEntry(Visit visit, Reason reason) {
        AuditEntry entry = new AuditEntry(reason == Reason.JOIN ? AuditEntry.BLOCKED_JOIN : AuditEntry.BLOCKED_STARTUP_LEAVE)
                .superAdminOnly();
        entry.subject = Value.server(visit.guildId(), visit.name());
        entry.fact("owner", Value.user(visit.ownerId(), visit.ownerName()));
        entry.fact("memberCount", Value.number(visit.memberCount()));
        entry.fact("addedBy", visit.inviterId() == 0L ? Value.text("unknown") : Value.user(visit.inviterId(), visit.inviterName()));
        return entry;
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        leaveIfBlocked(event.getGuild(), Reason.JOIN);
    }

    /**
     * Leaves every blocked server the bot is in. Called once Discord is ready.
     *
     * @return how many it left
     */
    public int sweep(JDA jda) {
        int left = 0;
        for (Guild guild : jda.getGuilds()) {
            if (leaveIfBlocked(guild, Reason.STARTUP)) left++;
        }
        return left;
    }

    /**
     * Leaves the server if it's blocked: first looks up who added the bot (if allowed, at most
     * {@link #INVITER_TIMEOUT_SECONDS}), then records the leave and leaves. That part runs in the background.
     *
     * @return whether it was blocked (and a leave is on its way)
     */
    public boolean leaveIfBlocked(Guild guild, Reason reason) {
        if (!blocked.test(guild.getIdLong())) return false;
        log.println(logLine(guild.getIdLong(), guild.getName(), guild.getOwnerIdLong(), guild.getMemberCount(), reason));
        inviter(guild).completeOnTimeout(null, INVITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(error -> null)
                .thenAcceptAsync(inviter -> {
                    try {
                        Member owner = guild.getOwner();
                        recorder.record(new Visit(guild.getIdLong(), guild.getName(), guild.getOwnerIdLong(),
                                owner == null ? null : owner.getUser().getName(), guild.getMemberCount(),
                                inviter == null ? 0L : inviter.getIdLong(), inviter == null ? null : inviter.getName()), reason);
                    } catch (RuntimeException e) {
                        Health.unexpected("Could not record leaving blocked server " + guild.getIdLong(), e);
                    }
                    guild.leave().queue(null, error -> Health.log(Component.DISCORD, Severity.WARNING,
                            "Could not leave blocked server " + guild.getIdLong() + ": " + Health.describe(error), error));
                });
        return true;
    }

    /** Who added the bot, from the server's audit log; null if the bot can't read it or it isn't there. */
    private static CompletableFuture<User> inviter(Guild guild) {
        try {
            if (!guild.getSelfMember().hasPermission(Permission.VIEW_AUDIT_LOGS)) return CompletableFuture.completedFuture(null);
            long self = guild.getSelfMember().getIdLong();
            return guild.retrieveAuditLogs().type(ActionType.BOT_ADD).limit(25).submit()
                    .thenApply((List<AuditLogEntry> entries) -> {
                        for (AuditLogEntry entry : entries) {
                            if (entry.getTargetIdLong() == self) return entry.getUser();
                        }
                        return null;
                    });
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    static String logLine(long guildId, String name, long ownerId, int memberCount, Reason reason) {
        return "[Blocked server] Server \"" + name + "\" (" + guildId + ", owner " + ownerId + ", " + memberCount
                + (memberCount == 1 ? " member" : " members") + ") " + reason.text + "; leaving.";
    }
}

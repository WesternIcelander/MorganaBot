package io.siggi.morganabot;

import io.siggi.morganabot.health.Component;
import io.siggi.morganabot.health.Health;
import io.siggi.morganabot.health.Severity;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.io.PrintStream;
import java.util.function.LongPredicate;

/**
 * Keeps the bot out of servers a super admin blocked ({@code blocked} in the server's file): it leaves straight away
 * when one adds it back ({@link GuildJoinEvent}), and {@link #sweep} leaves any it's still in at startup. Both are
 * written to stdout.
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

    private final LongPredicate blocked;
    private final PrintStream log;

    public BlockedGuildListener(LongPredicate blocked, PrintStream log) {
        this.blocked = blocked;
        this.log = log;
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
     * Leaves the server if it's blocked.
     *
     * @return whether it was blocked (and a leave was sent)
     */
    public boolean leaveIfBlocked(Guild guild, Reason reason) {
        if (!blocked.test(guild.getIdLong())) return false;
        log.println(logLine(guild.getIdLong(), guild.getName(), guild.getOwnerIdLong(), guild.getMemberCount(), reason));
        guild.leave().queue(null, error -> Health.log(Component.DISCORD, Severity.WARNING,
                "Could not leave blocked server " + guild.getIdLong() + ": " + Health.describe(error), error));
        return true;
    }

    static String logLine(long guildId, String name, long ownerId, int memberCount, Reason reason) {
        return "[Blocked server] Server \"" + name + "\" (" + guildId + ", owner " + ownerId + ", " + memberCount
                + (memberCount == 1 ? " member" : " members") + ") " + reason.text + "; leaving.";
    }
}

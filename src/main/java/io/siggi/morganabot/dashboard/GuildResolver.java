package io.siggi.morganabot.dashboard;

import io.siggi.morganabot.Access;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;

import java.util.function.Supplier;

/**
 * Finds a guild and the logged-in user's membership in it, for the per-request access check.
 */
public interface GuildResolver {

    /**
     * @param guild  the JDA guild (null only in tests)
     * @param member the user's JDA member, or null if they aren't in the guild
     * @param view   how access checks see them ({@link Access.MemberView#outsider} if they aren't in the guild)
     */
    record GuildMember(Guild guild, Member member, Access.MemberView view) {
    }

    /**
     * @return the guild and the user's membership, or null if the bot isn't in that guild
     * @throws ApiError {@link ApiError#discordUnavailable()} if Discord isn't connected
     */
    GuildMember resolve(long guildId, long userId) throws ApiError;

    /**
     * The JDA-backed resolver. Discord counts as unavailable unless JDA is connected. Members come from the member
     * cache, falling back to a REST lookup.
     */
    static GuildResolver jda(Supplier<JDA> jdaSupplier) {
        return (guildId, userId) -> {
            JDA jda = jdaSupplier.get();
            if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) throw ApiError.discordUnavailable();
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) return null;
            Member member = userId == 0L ? null : guild.getMemberById(userId);
            if (member == null && userId != 0L) {
                try {
                    member = guild.retrieveMemberById(userId).complete();
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MEMBER
                            && e.getErrorResponse() != ErrorResponse.UNKNOWN_USER) {
                        throw ApiError.discordUnavailable();
                    }
                } catch (RuntimeException e) {
                    throw ApiError.discordUnavailable();
                }
            }
            Access.MemberView view = member == null ? Access.MemberView.outsider(guildId, userId) : Access.view(member);
            return new GuildMember(guild, member, view);
        };
    }
}

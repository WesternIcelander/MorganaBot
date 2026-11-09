package io.siggi.morganabot.dashboard;

import io.siggi.morganabot.Access;
import io.siggi.morganabot.health.Component;
import io.siggi.morganabot.health.Health;
import io.siggi.morganabot.health.Severity;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;

import java.util.List;

/**
 * {@code /dashboard}: proves who you are and hands you a login link. It grants nothing by itself; what the session can
 * open is checked on every request.
 */
public class DashboardCommand {
    public static final String NO_ACCESS = "You don't have access to the dashboard of any server MorganaBot is in. "
            + "Server admins and streamers added to a server can use it.";
    public static final String REPLY = "Here's your dashboard login. The button works once, for 30 seconds.";

    private final LoginTokenStore tokens;
    private final Access access;
    private final String apiEndpoint;

    public DashboardCommand(LoginTokenStore tokens, Access access, String apiEndpoint) {
        this.tokens = tokens;
        this.access = access;
        this.apiEndpoint = apiEndpoint;
    }

    /**
     * The login URL for a token.
     */
    public static String loginUrl(String apiEndpoint, String token) {
        String base = apiEndpoint == null ? "" : apiEndpoint.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + LoginEndpoints.LOGIN_PATH + "?token=" + token;
    }

    public void handle(SlashCommandInteractionEvent event) {
        User user = event.getUser();
        boolean superAdmin = access.isSuperAdmin(user.getIdLong());
        Guild here = event.getGuild();
        long landingGuild = 0L;
        if (here != null && event.getMember() != null && access.of(event.getMember()) != Access.Level.NONE) {
            landingGuild = here.getIdLong();
        }
        if (!superAdmin && landingGuild == 0L && !hasAnyAccess(event, user)) {
            event.reply(NO_ACCESS).setEphemeral(true).queue();
            return;
        }
        PendingReply pending = new PendingReply();
        String token = tokens.issue(new LoginTokenStore.Login(user.getIdLong(), superAdmin, landingGuild),
                LoginTokenStore.COMMAND_TTL, pending);
        event.reply(REPLY)
                .setEphemeral(true)
                .addComponents(ActionRow.of(Button.link(loginUrl(apiEndpoint, token), "Open dashboard")))
                .queue(hook -> pending.attach(text -> edit(hook, text)),
                        error -> Health.log(Component.DISCORD, Severity.WARNING,
                                "Could not send the /dashboard reply: " + Health.describe(error), null));
    }

    private boolean hasAnyAccess(SlashCommandInteractionEvent event, User user) {
        for (Guild guild : event.getJDA().getMutualGuilds(user)) {
            Member member = guild.getMember(user);
            if (member != null && access.of(member) != Access.Level.NONE) return true;
        }
        return false;
    }

    private static void edit(InteractionHook hook, String text) {
        hook.editOriginal(new MessageEditBuilder().setContent(text).setComponents(List.of()).build())
                .queue(null, error -> {
                    // The reply may have been dismissed; nothing else to do.
                });
    }
}

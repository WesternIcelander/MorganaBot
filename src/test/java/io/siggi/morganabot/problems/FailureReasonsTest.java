package io.siggi.morganabot.problems;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class FailureReasonsTest {

    /** A Guild whose methods all return defaults; enough to build a permission exception. */
    private static Guild fakeGuild() {
        return (Guild) Proxy.newProxyInstance(Guild.class.getClassLoader(), new Class<?>[]{Guild.class}, (proxy, method, args) -> {
            Class<?> type = method.getReturnType();
            if (type == long.class) return 1L;
            if (type == int.class) return 0;
            if (type == boolean.class) return false;
            if (type == String.class) return "guild";
            return null;
        });
    }

    @Test
    void insufficientPermissionInChannel() {
        InsufficientPermissionException e = new InsufficientPermissionException(fakeGuild(), Permission.MESSAGE_SEND);
        assertEquals("Missing permission Send Messages in #live",
                FailureReasons.describe(e, Problem.SEND_LIVE_MESSAGE, "live", null));
        assertEquals("Missing permission Send Messages in the live channel",
                FailureReasons.describe(e, Problem.SEND_LIVE_MESSAGE, null, null));
    }

    @Test
    void insufficientPermissionForRole() {
        InsufficientPermissionException e = new InsufficientPermissionException(fakeGuild(), Permission.MANAGE_ROLES);
        assertEquals("Missing permission Manage Roles",
                FailureReasons.describe(e, Problem.ADD_LIVE_ROLE, null, "Live"));
    }

    @Test
    void hierarchy() {
        HierarchyException e = new HierarchyException("Can't modify a role with higher or equal highest role than yourself!");
        assertEquals("Role @Live is above the bot's highest role",
                FailureReasons.describe(e, Problem.ADD_LIVE_ROLE, null, "Live"));
        assertEquals("Role @Live is above the bot's highest role",
                FailureReasons.describe(e, Problem.REMOVE_LIVE_ROLE, null, "Live"));
    }

    @Test
    void wrappedExceptionsAreUnwrapped() {
        HierarchyException e = new HierarchyException("x");
        assertEquals("Role @Live is above the bot's highest role",
                FailureReasons.describe(new CompletionException(e), Problem.ADD_LIVE_ROLE, null, "Live"));
    }

    @Test
    void errorResponses() {
        assertEquals("Unknown channel (deleted?)",
                FailureReasons.describe(ErrorResponse.UNKNOWN_CHANNEL, null, Problem.SEND_LIVE_MESSAGE, "live", null));
        assertEquals("Live message not found (already deleted?)",
                FailureReasons.describe(ErrorResponse.UNKNOWN_MESSAGE, null, Problem.DELETE_LIVE_MESSAGE, "live", null));
        assertEquals("The streamer's linked Discord user isn't in the server",
                FailureReasons.describe(ErrorResponse.UNKNOWN_MEMBER, null, Problem.ADD_LIVE_ROLE, null, "Live"));
        assertEquals("Missing permission in #live (the bot needs View Channel and Send Messages)",
                FailureReasons.describe(ErrorResponse.MISSING_PERMISSIONS, null, Problem.SEND_LIVE_MESSAGE, "live", null));
        assertEquals("Missing permission to delete the live message in #live",
                FailureReasons.describe(ErrorResponse.MISSING_PERMISSIONS, null, Problem.DELETE_LIVE_MESSAGE, "live", null));
        assertEquals("Missing permission Manage Roles, or role @Live is above the bot's highest role",
                FailureReasons.describe(ErrorResponse.MISSING_PERMISSIONS, null, Problem.REMOVE_LIVE_ROLE, null, "Live"));
        assertEquals("The bot can't see #live (missing View Channel)",
                FailureReasons.describe(ErrorResponse.MISSING_ACCESS, null, Problem.SEND_LIVE_MESSAGE, "live", null));
        assertEquals("Role @Live not found (deleted?)",
                FailureReasons.describe(ErrorResponse.UNKNOWN_ROLE, null, Problem.ADD_LIVE_ROLE, null, "Live"));
        assertTrue(FailureReasons.describe(ErrorResponse.SERVER_ERROR, null, Problem.SEND_LIVE_MESSAGE, "live", null)
                .startsWith("Discord error: "));
    }

    @Test
    void unknownErrors() {
        assertEquals("Unexpected error: boom",
                FailureReasons.describe(new RuntimeException("boom"), Problem.SEND_LIVE_MESSAGE, "live", null));
        assertEquals("Unexpected error: IllegalStateException",
                FailureReasons.describe(new IllegalStateException(), Problem.SEND_LIVE_MESSAGE, "live", null));
    }
}

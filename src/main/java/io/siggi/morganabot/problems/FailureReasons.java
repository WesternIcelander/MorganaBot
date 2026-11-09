package io.siggi.morganabot.problems;

import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Turns JDA errors into readable reasons for the problem log.
 */
public final class FailureReasons {

    private FailureReasons() {
    }

    /**
     * @param action      one of the {@link Problem} action constants
     * @param channelName the channel the action was in, without '#', or null if unknown / not a channel action
     * @param roleName    the role the action was about, without '@', or null if unknown / not a role action
     */
    public static String describe(Throwable error, String action, String channelName, String roleName) {
        error = unwrap(error);
        if (error instanceof InsufficientPermissionException e) {
            String permission = e.getPermission() == null ? "?" : e.getPermission().getName();
            if (isChannelAction(action)) {
                return "Missing permission " + permission + " in " + channel(channelName);
            }
            return "Missing permission " + permission;
        }
        if (error instanceof HierarchyException) {
            if (isRoleAction(action)) {
                return "Role " + role(roleName) + " is above the bot's highest role";
            }
            return "Discord role hierarchy: " + error.getMessage();
        }
        if (error instanceof ErrorResponseException e) {
            return describe(e.getErrorResponse(), e.getMeaning(), action, channelName, roleName);
        }
        String message = error == null ? null : error.getMessage();
        String type = error == null ? "Unknown error" : error.getClass().getSimpleName();
        return "Unexpected error: " + (message == null || message.isBlank() ? type : message);
    }

    /**
     * Reason for a Discord error response. Split out from {@link #describe(Throwable, String, String, String)}
     * because {@link ErrorResponseException} can't easily be created in tests.
     */
    static String describe(ErrorResponse response, String meaning, String action, String channelName, String roleName) {
        if (response == null) {
            return "Discord error: " + (meaning == null || meaning.isBlank() ? "unknown" : meaning);
        }
        switch (response) {
            case MISSING_PERMISSIONS:
                if (Problem.SEND_LIVE_MESSAGE.equals(action)) {
                    return "Missing permission in " + channel(channelName) + " (the bot needs View Channel and Send Messages)";
                }
                if (Problem.DELETE_LIVE_MESSAGE.equals(action)) {
                    return "Missing permission to delete the live message in " + channel(channelName);
                }
                if (isRoleAction(action)) {
                    return "Missing permission Manage Roles, or role " + role(roleName) + " is above the bot's highest role";
                }
                return "Missing permissions";
            case MISSING_ACCESS:
                if (isChannelAction(action)) {
                    return "The bot can't see " + channel(channelName) + " (missing View Channel)";
                }
                return "Missing access";
            case UNKNOWN_CHANNEL:
                return "Unknown channel (deleted?)";
            case UNKNOWN_MESSAGE:
                return "Live message not found (already deleted?)";
            case UNKNOWN_MEMBER:
            case UNKNOWN_USER:
                return "The streamer's linked Discord user isn't in the server";
            case UNKNOWN_ROLE:
                return "Role " + role(roleName) + " not found (deleted?)";
            default:
                String text = meaning != null && !meaning.isBlank() ? meaning : response.getMeaning();
                return "Discord error: " + text;
        }
    }

    public static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    static boolean isChannelAction(String action) {
        return Problem.SEND_LIVE_MESSAGE.equals(action) || Problem.DELETE_LIVE_MESSAGE.equals(action);
    }

    static boolean isRoleAction(String action) {
        return Problem.ADD_LIVE_ROLE.equals(action) || Problem.REMOVE_LIVE_ROLE.equals(action);
    }

    static String channel(String name) {
        return name == null ? "the live channel" : "#" + name;
    }

    static String role(String name) {
        return name == null ? "the live role" : "@" + name;
    }
}

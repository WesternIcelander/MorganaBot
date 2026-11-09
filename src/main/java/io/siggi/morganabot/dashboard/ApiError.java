package io.siggi.morganabot.dashboard;

/**
 * An API failure with a status, a machine-readable code and a readable message, sent as
 * {@code {"error": message, "code": code, "field": field}} (see {@link WebResponses#sendError}).
 * <p>
 * Handlers throw it; {@link ApiRouter} turns it into the response. {@code field} names the request field the error is
 * about, so the frontend can show it next to that field; it's left out when null.
 */
public class ApiError extends Exception {
    private final int status;
    private final String code;
    private final String field;

    public ApiError(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiError(int status, String code, String message, String field) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }

    // --- the common ones ---

    public static ApiError badRequest(String message) {
        return new ApiError(400, "bad_request", message);
    }

    /** A value that isn't allowed, shown next to {@code field}. */
    public static ApiError invalid(String field, String message) {
        return new ApiError(400, "invalid", message, field);
    }

    public static ApiError notLoggedIn() {
        return new ApiError(401, "not_logged_in", "You're not logged in, or your session has expired. Run /dashboard again.");
    }

    public static ApiError csrf() {
        return new ApiError(403, "csrf", "Missing or wrong " + DashboardSession.CSRF_HEADER + " header");
    }

    public static ApiError noAccess() {
        return new ApiError(403, "no_access", "You don't have access to this server's dashboard");
    }

    public static ApiError forbidden(String message) {
        return new ApiError(403, "forbidden", message);
    }

    public static ApiError notFound(String message) {
        return new ApiError(404, "not_found", message);
    }

    public static ApiError methodNotAllowed() {
        return new ApiError(405, "method_not_allowed", "Method not allowed");
    }

    public static ApiError tooLarge() {
        return new ApiError(413, "too_large", "Request body is too large");
    }

    public static ApiError notJson() {
        return new ApiError(415, "not_json", "Request body must be JSON (Content-Type: application/json)");
    }

    public static ApiError discordUnavailable() {
        return new ApiError(503, "discord_unavailable",
                "Discord is unavailable right now, so server settings can't be loaded. Try again in a few minutes.");
    }

    public static ApiError internal() {
        return new ApiError(500, "internal", "Something went wrong on the server. Try again, or ask the bot's owner.");
    }
}

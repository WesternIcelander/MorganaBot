package io.siggi.morganabot.dashboard;

import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPServer;
import io.siggi.morganabot.Access;
import io.siggi.morganabot.health.Health;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Routes {@code /dashboard/api/…} requests and does the checks every endpoint needs, in this order:
 * <ol>
 *     <li>the path and method match a route (404 / 405)</li>
 *     <li>the session is logged in ({@link DashboardSession#from}, so old super admin sessions are cut off) (401)</li>
 *     <li>writes (anything but GET/HEAD) carry the session's {@code X-Morgana-CSRF} header (403), and a body, if
 *     any, is JSON (415)</li>
 *     <li>{@link Requirement#SUPER_ADMIN} routes need a super admin session (403); they never need Discord</li>
 *     <li>guild routes ({@code {guildId}} in the path): Discord must be connected (503 "Discord unavailable"), the
 *     bot must be in the guild, and the user's access level is worked out now with {@link Access#of(Access.MemberView,
 *     boolean)}: {@link Requirement#GUILD_ADMIN} needs {@code ADMIN}, {@link Requirement#GUILD_STREAMER} at least
 *     {@code STREAMER}. Super admin sessions are {@code ADMIN} in every guild the bot is in.</li>
 * </ol>
 * The handler's return value is sent as JSON (null sends {@code {"ok": true}}, an {@link Response} picks the status).
 * An {@link ApiError} is sent as {@code {"error", "code", "field"}}; any other exception is logged and becomes a 500.
 * <p>
 * Paths are registered relative to {@code /dashboard/api}, e.g. {@code "/guilds/{guildId}/settings"}. A
 * {@code {name}} segment matches any one segment. Every response gets the API security headers
 * ({@link WebResponses#apiHeaders}).
 */
public final class ApiRouter {
    public static final String BASE = "/dashboard/api";

    public enum Requirement {
        /** Any logged-in session. */
        SESSION,
        /** Super admin sessions only. */
        SUPER_ADMIN,
        /** A guild route open to streamers (own entry) and admins. */
        GUILD_STREAMER,
        /** A guild route for server admins (and super admins). */
        GUILD_ADMIN
    }

    @FunctionalInterface
    public interface Handler {
        Object handle(ApiRequest request) throws Exception;
    }

    /** A handler result with a status other than 200. */
    public record Response(int status, Object body) {
    }

    private record Route(String method, String[] segments, Requirement requirement, Handler handler) {
        Map<String, String> match(String[] path) {
            if (path.length != segments.length) return null;
            Map<String, String> params = new HashMap<>();
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    if (path[i].isEmpty()) return null;
                    params.put(segment.substring(1, segment.length() - 1), path[i]);
                } else if (!segment.equals(path[i])) {
                    return null;
                }
            }
            return params;
        }
    }

    private final Access access;
    private final GuildResolver guilds;
    private final LongSupplier clock;
    private final List<Route> routes = new ArrayList<>();

    public ApiRouter(Access access, GuildResolver guilds, LongSupplier clock) {
        this.access = access;
        this.guilds = guilds;
        this.clock = clock;
    }

    /**
     * Registers the router for everything under {@link #BASE}. With SiggiHTTP's registry the first matching
     * registration wins, so register more specific paths under {@code /dashboard/api} (the logout endpoint) first.
     */
    @SuppressWarnings("deprecation")
    public void register(HTTPServer server) {
        server.responderRegistry.register(BASE, this::respond, true, true);
    }

    public ApiRouter get(String path, Requirement requirement, Handler handler) {
        return route("GET", path, requirement, handler);
    }

    public ApiRouter post(String path, Requirement requirement, Handler handler) {
        return route("POST", path, requirement, handler);
    }

    public ApiRouter put(String path, Requirement requirement, Handler handler) {
        return route("PUT", path, requirement, handler);
    }

    public ApiRouter patch(String path, Requirement requirement, Handler handler) {
        return route("PATCH", path, requirement, handler);
    }

    public ApiRouter delete(String path, Requirement requirement, Handler handler) {
        return route("DELETE", path, requirement, handler);
    }

    public synchronized ApiRouter route(String method, String path, Requirement requirement, Handler handler) {
        String[] segments = split(path);
        boolean hasGuild = List.of(segments).contains("{guildId}");
        boolean guildRoute = requirement == Requirement.GUILD_ADMIN || requirement == Requirement.GUILD_STREAMER;
        if (hasGuild != guildRoute) {
            throw new IllegalArgumentException("Guild routes (and only those) have a {guildId} segment: " + path);
        }
        routes.add(new Route(method.toUpperCase(Locale.ROOT), segments, requirement, handler));
        return this;
    }

    static String[] split(String path) {
        String p = path;
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? new String[0] : p.split("/", -1);
    }

    static boolean isJson(String contentType) {
        if (contentType == null) return false;
        String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.equals("application/json");
    }

    static boolean isWrite(String method) {
        return !method.equals("GET") && !method.equals("HEAD") && !method.equals("OPTIONS");
    }

    private static boolean hasBody(HTTPRequest request) {
        String length = request.getHeader("Content-Length");
        if (length != null) {
            try {
                return Long.parseLong(length.trim()) > 0;
            } catch (NumberFormatException e) {
                return true;
            }
        }
        return request.getHeader("Transfer-Encoding") != null;
    }

    void respond(HTTPRequest request) throws IOException {
        Object result;
        try {
            result = dispatch(request);
        } catch (ApiError e) {
            if (e.status() == 405) request.response.setHeader("Allow", String.join(", ", allowed(request.path())));
            WebResponses.sendError(request, e);
            return;
        } catch (Exception e) {
            Health.unexpected("Dashboard API " + request.method() + " " + request.path() + " failed", e);
            WebResponses.sendError(request, ApiError.internal());
            return;
        }
        if (result instanceof Response response) {
            if (response.status() == 204) {
                request.response.setHeader(WebResponses.statusLine(204));
                WebResponses.apiHeaders(request.response);
                request.response.sendHeaders();
                return;
            }
            WebResponses.sendJson(request, response.status(), response.body() == null ? Map.of("ok", true) : response.body());
        } else {
            WebResponses.sendJson(request, 200, result == null ? Map.of("ok", true) : result);
        }
    }

    private synchronized List<Route> snapshot() {
        return new ArrayList<>(routes);
    }

    private Set<String> allowed(String fullPath) {
        String[] path = split(relative(fullPath));
        Set<String> methods = new LinkedHashSet<>();
        for (Route route : snapshot()) {
            if (route.match(path) != null) {
                methods.add(route.method());
                if (route.method().equals("GET")) methods.add("HEAD");
            }
        }
        return methods;
    }

    private static String relative(String fullPath) {
        return fullPath.length() <= BASE.length() ? "" : fullPath.substring(BASE.length());
    }

    Object dispatch(HTTPRequest request) throws Exception {
        String method = request.method().toUpperCase(Locale.ROOT);
        String matchMethod = method.equals("HEAD") ? "GET" : method;
        String[] path = split(relative(request.path()));

        Route route = null;
        Map<String, String> params = null;
        boolean pathMatched = false;
        for (Route candidate : snapshot()) {
            Map<String, String> p = candidate.match(path);
            if (p == null) continue;
            pathMatched = true;
            if (candidate.method().equals(matchMethod)) {
                route = candidate;
                params = p;
                break;
            }
        }
        if (route == null) {
            if (pathMatched) throw ApiError.methodNotAllowed();
            throw ApiError.notFound("No such API endpoint");
        }

        DashboardSession session = DashboardSession.from(request.getSessionIfExists(), clock.getAsLong());
        if (session == null) throw ApiError.notLoggedIn();
        if (isWrite(method)) {
            if (!session.csrfMatches(request.getHeader(DashboardSession.CSRF_HEADER))) throw ApiError.csrf();
            if (hasBody(request) && !isJson(request.getHeader("Content-Type"))) throw ApiError.notJson();
        }

        switch (route.requirement()) {
            case SESSION -> {
                return route.handler().handle(new ApiRequest(request, session, params, 0L, null, null));
            }
            case SUPER_ADMIN -> {
                if (!session.superAdmin()) throw ApiError.forbidden("Only super admins can do this");
                return route.handler().handle(new ApiRequest(request, session, params, 0L, null, null));
            }
            default -> {
            }
        }

        long guildId;
        try {
            guildId = Long.parseLong(params.get("guildId"));
        } catch (NumberFormatException e) {
            throw ApiError.notFound("Server not found");
        }
        if (guildId <= 0L) throw ApiError.notFound("Server not found");
        GuildResolver.GuildMember guildMember = guilds.resolve(guildId, session.userId());
        if (guildMember == null) {
            // Only super admins learn whether the bot is in a server they have no other link to.
            if (session.superAdmin()) throw ApiError.notFound("The bot isn't in this server");
            throw ApiError.noAccess();
        }
        Access.Level level = access.of(guildMember.view(), session.superAdmin());
        if (level == Access.Level.NONE) throw ApiError.noAccess();
        if (route.requirement() == Requirement.GUILD_ADMIN && level != Access.Level.ADMIN) {
            throw ApiError.forbidden("This needs server admin access");
        }
        return route.handler().handle(new ApiRequest(request, session, params, guildId, guildMember, level));
    }
}

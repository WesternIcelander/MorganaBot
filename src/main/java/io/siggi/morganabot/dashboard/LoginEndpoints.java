package io.siggi.morganabot.dashboard;

import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPServer;
import io.siggi.http.session.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * {@code GET /dashboard/login?token=} and {@code POST /dashboard/api/logout}.
 * <p>
 * Login uses up the token, replaces the session ({@code resetSession()}, so a session ID planted before login is
 * useless), stores the login and redirects straight away, so the token doesn't stay in the address bar.
 * {@code Referrer-Policy: no-referrer} keeps it from being sent anywhere.
 */
public class LoginEndpoints {
    public static final String LOGIN_PATH = "/dashboard/login";
    public static final String LOGOUT_PATH = "/dashboard/api/logout";

    private final LoginTokenStore tokens;
    private final LongSupplier clock;

    public LoginEndpoints(LoginTokenStore tokens) {
        this(tokens, System::currentTimeMillis);
    }

    public LoginEndpoints(LoginTokenStore tokens, LongSupplier clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    public void register(HTTPServer server) {
        server.responderRegistry.register(LOGIN_PATH, this::login, false, true);
        server.responderRegistry.register(LOGOUT_PATH, this::logout, false, true);
    }

    /**
     * Where a login lands: the dashboard, opened on the server {@code /dashboard} was run in, if any.
     */
    public static String landingPage(long guildId) {
        return guildId == 0L ? "/dashboard/" : "/dashboard/?guild=" + guildId;
    }

    void login(HTTPRequest request) throws Exception {
        request.allowedMethods("GET");
        WebResponses.pageHeaders(request.response, "no-store");
        LoginTokenStore.Login login = tokens.redeem(request.get.get("token"));
        if (login == null) {
            sendPage(request, "400 Bad Request",
                    "This login link has expired or was already used. Run /dashboard again to get a new one.");
            return;
        }
        Session session = request.resetSession();
        DashboardSession.login(session, login.userId(), login.superAdmin(), clock.getAsLong());
        request.response.redirect(landingPage(login.guildId()));
    }

    void logout(HTTPRequest request) throws Exception {
        request.allowedMethods("POST");
        WebResponses.apiHeaders(request.response);
        Session session = request.getSessionIfExists();
        DashboardSession login = DashboardSession.from(session, clock.getAsLong());
        if (login != null && !login.csrfMatches(request.getHeader(DashboardSession.CSRF_HEADER))) {
            WebResponses.sendError(request, ApiError.csrf());
            return;
        }
        if (session != null) session.delete();
        request.response.addHeader("Set-Cookie", request.server.getSessionCookieName()
                + "=; path=/; Max-Age=0; HttpOnly;" + (request.isSecure() ? " Secure;" : ""));
        WebResponses.sendOk(request);
    }

    private static void sendPage(HTTPRequest request, String status, String message) throws IOException {
        // The message is a fixed string, never user input, so it's safe to put in HTML as is.
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>MorganaBot dashboard</title>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>"
                + "<body style=\"font-family: system-ui, sans-serif; margin: 3em auto; max-width: 32em; padding: 0 1em\">"
                + "<h1>MorganaBot dashboard</h1><p>" + message + "</p></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        request.response.setHeader(status);
        request.response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        request.response.setContentType("text/html; charset=utf-8");
        request.response.contentLength(body.length);
        request.response.write(body);
    }
}

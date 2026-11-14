package io.siggi.morganabot.dashboard;

import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPServer;
import io.siggi.http.session.SessionHandle;
import io.siggi.http.session.Sessions;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * Test-only endpoints for making and inspecting dashboard sessions over HTTP, since SiggiHTTP sessions are only
 * reachable through a request. Register them on the test server, then call the static helpers with the server's base
 * URL ({@code http://127.0.0.1:port}).
 */
final class TestSessions {
    private static final String PATH = "/test-session/";

    private TestSessions() {
    }

    /** A logged-in session's ID and CSRF token. */
    record Login(String sessionId, String csrf) {
    }

    static void register(HTTPServer server, Sessions sessions, LongSupplier clock) {
        server.responderRegistry.register(PATH + "login", request -> {
            long userId = Long.parseLong(request.get.get("user"));
            boolean superAdmin = Boolean.parseBoolean(request.get.get("super"));
            String body;
            try (SessionHandle session = sessions.resetSession(request)) {
                DashboardSession login = DashboardSession.login(session, userId, superAdmin, clock.getAsLong());
                body = session.getSessionId() + " " + login.csrfToken();
            }
            send(request, body);
        }, false, true);
        server.responderRegistry.register(PATH + "plant", request -> {
            String body;
            try (SessionHandle session = sessions.openSession(request)) {
                session.set("x", "y");
                body = session.getSessionId();
            }
            send(request, body);
        }, false, true);
        server.responderRegistry.register(PATH + "read", request -> {
            SessionHandle session = sessions.readSession(request);
            DashboardSession login = DashboardSession.from(session, clock.getAsLong());
            String body = !session.exists() ? "missing"
                    : login == null ? "anonymous"
                    : login.userId() + " " + login.superAdmin() + " " + login.csrfToken() + " " + login.loginTime();
            send(request, body);
        }, false, true);
    }

    /** Logs in a new session directly, as the login endpoint would. */
    static Login login(String base, long userId, boolean superAdmin) throws IOException {
        String[] parts = get(base, "login?user=" + userId + "&super=" + superAdmin, null).split(" ");
        return new Login(parts[0], parts[1]);
    }

    /** A session that exists but isn't logged in; returns its ID. */
    static String plant(String base) throws IOException {
        return get(base, "plant", null);
    }

    /** The login in a session, or null if the session is gone or not logged in. */
    static DashboardSession read(String base, String sessionId) throws IOException {
        String body = get(base, "read", sessionId);
        if (body.equals("missing") || body.equals("anonymous")) return null;
        String[] parts = body.split(" ");
        return new DashboardSession(Long.parseLong(parts[0]), Boolean.parseBoolean(parts[1]), parts[2], Long.parseLong(parts[3]));
    }

    /** Whether a session with this ID still exists. */
    static boolean exists(String base, String sessionId) throws IOException {
        return !get(base, "read", sessionId).equals("missing");
    }

    private static void send(HTTPRequest request, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        request.response.setContentType("text/plain; charset=utf-8");
        request.response.contentLength(bytes.length);
        request.response.write(bytes);
    }

    private static String get(String base, String path, String sessionId) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + PATH + path).openConnection();
        if (sessionId != null) c.setRequestProperty("Cookie", "SessID=" + sessionId);
        if (c.getResponseCode() != 200) throw new IOException("Test session endpoint failed: " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

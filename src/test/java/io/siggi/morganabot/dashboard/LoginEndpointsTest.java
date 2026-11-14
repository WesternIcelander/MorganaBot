package io.siggi.morganabot.dashboard;

import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;
import io.siggi.http.session.Sessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the login and logout endpoints on a real SiggiHTTP server.
 */
class LoginEndpointsTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final LoginTokenStore tokens = new LoginTokenStore(now::get);
    private final Sessions sessions = DashboardSession.createSessions();
    private ServerSocket socket;
    private HTTPServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        socket = new ServerSocket(0);
        server = new HTTPServerBuilder().build();
        TestSessions.register(server, sessions, now::get);
        new LoginEndpoints(tokens, sessions, now::get).register(server);
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    Socket accept = socket.accept();
                    server.handle(accept);
                }
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        base = "http://127.0.0.1:" + socket.getLocalPort();
    }

    @AfterEach
    void stop() throws IOException {
        socket.close();
    }

    private record Response(int code, HttpURLConnection connection, String body) {
        String header(String name) {
            return connection.getHeaderField(name);
        }

        List<String> cookies() {
            List<String> list = connection.getHeaderFields().get("Set-Cookie");
            return list == null ? List.of() : list;
        }
    }

    private Response request(String method, String path, String cookie, String csrf) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        if (cookie != null) c.setRequestProperty("Cookie", "SessID=" + cookie);
        if (csrf != null) c.setRequestProperty(DashboardSession.CSRF_HEADER, csrf);
        if (method.equals("POST")) {
            c.setDoOutput(true);
            c.getOutputStream().close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : new String(in.readAllBytes());
        return new Response(code, c, body);
    }

    /** The session IDs set by a response that are live, logged-in sessions. */
    private List<String> loggedInSessions(Response response) throws IOException {
        List<String> ids = new ArrayList<>();
        for (String cookie : response.cookies()) {
            if (!cookie.startsWith("SessID=")) continue;
            String id = cookie.substring("SessID=".length(), cookie.indexOf(';'));
            if (TestSessions.read(base, id) != null) ids.add(id);
        }
        return ids;
    }

    @Test
    void loginRedirectsAndCreatesSession() throws IOException {
        String token = tokens.issue(new LoginTokenStore.Login(42L, false, 7L), LoginTokenStore.COMMAND_TTL, null);
        Response response = request("GET", "/dashboard/login?token=" + token, null, null);
        assertEquals(302, response.code());
        assertEquals("/dashboard/?guild=7", response.header("Location"));
        assertEquals("no-referrer", response.header("Referrer-Policy"));
        assertEquals("no-store", response.header("Cache-Control"));
        List<String> ids = loggedInSessions(response);
        assertEquals(1, ids.size());
        DashboardSession login = TestSessions.read(base, ids.get(0));
        assertEquals(42L, login.userId());
        assertFalse(login.superAdmin());
        assertEquals(now.get(), login.loginTime());
        for (String cookie : response.cookies()) {
            assertTrue(cookie.contains("HttpOnly"), cookie);
            assertTrue(cookie.contains("SameSite=Lax"), cookie);
        }
    }

    @Test
    void dmLoginGoesToDashboardRoot() throws IOException {
        String token = tokens.issue(new LoginTokenStore.Login(1L, true, 0L), LoginTokenStore.RECOVERY_TTL, null);
        Response response = request("GET", "/dashboard/login?token=" + token, null, null);
        assertEquals("/dashboard/", response.header("Location"));
        DashboardSession login = TestSessions.read(base, loggedInSessions(response).get(0));
        assertTrue(login.superAdmin());
    }

    @Test
    void secondUseAndExpiredAndMissingTokensAreRejected() throws IOException {
        String token = tokens.issue(new LoginTokenStore.Login(42L, false, 0L), LoginTokenStore.COMMAND_TTL, null);
        assertEquals(302, request("GET", "/dashboard/login?token=" + token, null, null).code());
        Response again = request("GET", "/dashboard/login?token=" + token, null, null);
        assertEquals(400, again.code());
        assertTrue(again.body().contains("Run /dashboard again"));
        assertEquals("no-referrer", again.header("Referrer-Policy"));
        assertTrue(loggedInSessions(again).isEmpty());

        String old = tokens.issue(new LoginTokenStore.Login(42L, false, 0L), LoginTokenStore.COMMAND_TTL, null);
        now.addAndGet(LoginTokenStore.COMMAND_TTL);
        assertEquals(400, request("GET", "/dashboard/login?token=" + old, null, null).code());
        assertEquals(400, request("GET", "/dashboard/login", null, null).code());
    }

    @Test
    void loginReplacesAnExistingSession() throws IOException {
        String plantedId = TestSessions.plant(base);
        assertTrue(TestSessions.exists(base, plantedId));
        String token = tokens.issue(new LoginTokenStore.Login(42L, false, 0L), LoginTokenStore.COMMAND_TTL, null);
        Response response = request("GET", "/dashboard/login?token=" + token, plantedId, null);
        assertEquals(302, response.code());
        assertFalse(TestSessions.exists(base, plantedId), "the session from before login is gone");
        List<String> ids = loggedInSessions(response);
        assertEquals(1, ids.size());
        assertNotEquals(plantedId, ids.get(0));
    }

    @Test
    void loginOnlyAcceptsGet() throws IOException {
        String token = tokens.issue(new LoginTokenStore.Login(42L, false, 0L), LoginTokenStore.COMMAND_TTL, null);
        assertEquals(405, request("POST", "/dashboard/login?token=" + token, null, null).code());
        assertNotNull(tokens.redeem(token), "a refused method doesn't use the token up");
    }

    @Test
    void logoutNeedsCsrfAndEndsSession() throws IOException {
        String token = tokens.issue(new LoginTokenStore.Login(42L, false, 0L), LoginTokenStore.COMMAND_TTL, null);
        String id = loggedInSessions(request("GET", "/dashboard/login?token=" + token, null, null)).get(0);
        String csrf = TestSessions.read(base, id).csrfToken();

        assertEquals(403, request("POST", "/dashboard/api/logout", id, null).code());
        assertEquals(403, request("POST", "/dashboard/api/logout", id, "wrong").code());
        assertNotNull(TestSessions.read(base, id), "still logged in");
        assertEquals(405, request("GET", "/dashboard/api/logout", id, csrf).code());

        Response out = request("POST", "/dashboard/api/logout", id, csrf);
        assertEquals(200, out.code());
        assertEquals("{\"ok\":true}", out.body());
        assertFalse(TestSessions.exists(base, id));
        assertTrue(out.cookies().stream().anyMatch(c -> c.startsWith("SessID=;") && c.contains("Max-Age=0")));
    }

    @Test
    void logoutWithoutSessionIsFine() throws IOException {
        assertEquals(200, request("POST", "/dashboard/api/logout", null, null).code());
    }
}

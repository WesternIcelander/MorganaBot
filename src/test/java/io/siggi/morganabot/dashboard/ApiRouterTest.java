package io.siggi.morganabot.dashboard;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;
import io.siggi.http.session.Session;
import io.siggi.http.session.Sessions;
import io.siggi.morganabot.Access;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;
import io.siggi.morganabot.dashboard.ApiRouter.Requirement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the API router and static files on a real SiggiHTTP server, registered in the same order as
 * {@code WebHandler}, with a fake Discord.
 */
class ApiRouterTest {
    private static final long GUILD = 100L;
    private static final long OTHER_GUILD = 101L;
    private static final long NOT_IN_GUILD = 555L;
    private static final long ADMIN_ROLE = 10L;
    private static final long ADMIN = 1L;
    private static final long ROLE_ADMIN = 2L;
    private static final long STREAMER = 3L;
    private static final long NOBODY = 4L;
    private static final long SUPER = 999L;

    @TempDir
    Path tempDir;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Sessions sessions = Sessions.create(DashboardSession.SESSION_TTL);
    private final AtomicBoolean discordUp = new AtomicBoolean(true);
    /** guild → user → member view; users missing from a guild's map aren't in it. */
    private final Map<Long, Map<Long, Access.MemberView>> members = new HashMap<>();
    private ServerInfoStore store;
    private ServerSocket socket;
    private String base;

    @BeforeEach
    void start() throws IOException {
        store = new ServerInfoStore(tempDir.toFile(), new Gson());
        store.modify(GUILD, info -> {
            info.adminRoles.add(ADMIN_ROLE);
            info.streamers.add(new ServerInfo.Streamer(STREAMER, "1"));
            return null;
        });
        Map<Long, Access.MemberView> guild = new HashMap<>();
        guild.put(ADMIN, new Access.MemberView(GUILD, ADMIN, true, List.of(GUILD)));
        guild.put(ROLE_ADMIN, new Access.MemberView(GUILD, ROLE_ADMIN, false, List.of(GUILD, ADMIN_ROLE)));
        guild.put(STREAMER, new Access.MemberView(GUILD, STREAMER, false, List.of(GUILD)));
        guild.put(NOBODY, new Access.MemberView(GUILD, NOBODY, false, List.of(GUILD)));
        members.put(GUILD, guild);
        members.put(OTHER_GUILD, new HashMap<>());

        GuildResolver resolver = (guildId, userId) -> {
            if (!discordUp.get()) throw ApiError.discordUnavailable();
            Map<Long, Access.MemberView> m = members.get(guildId);
            if (m == null) return null;
            Access.MemberView view = m.get(userId);
            return new GuildResolver.GuildMember(null, null,
                    view == null ? Access.MemberView.outsider(guildId, userId) : view);
        };
        ApiRouter api = new ApiRouter(new Access(store, List.of(SUPER)), resolver, now::get);
        api.get("/me", Requirement.SESSION, r -> Map.of("userId", r.userId(), "superAdmin", r.isSuperAdmin()));
        api.get("/admin/system", Requirement.SUPER_ADMIN, r -> Map.of("ok", "system"));
        api.get("/guilds/{guildId}/me/streamer", Requirement.GUILD_STREAMER,
                r -> Map.of("level", r.level().name(), "guildId", r.guildId()));
        api.get("/guilds/{guildId}/settings", Requirement.GUILD_ADMIN, r -> Map.of("level", r.level().name()));
        api.put("/guilds/{guildId}/settings", Requirement.GUILD_ADMIN, r -> {
            JsonObject body = r.body(JsonObject.class);
            return Map.of("got", body.get("value").getAsString());
        });
        api.delete("/guilds/{guildId}/streamers/{twitchId}", Requirement.GUILD_ADMIN,
                r -> new ApiRouter.Response(204, null));
        api.get("/guilds/{guildId}/streamers/{twitchId}", Requirement.GUILD_ADMIN,
                r -> Map.of("twitchId", r.longParam("twitchId")));
        api.get("/boom", Requirement.SESSION, r -> {
            throw new IllegalStateException("broken");
        });
        api.post("/invalid", Requirement.SESSION, r -> {
            throw ApiError.invalid("channelId", "That channel isn't in this server");
        });

        HTTPServer server = new HTTPServerBuilder().setSessions(sessions).build();
        new LoginEndpoints(new LoginTokenStore(now::get), now::get).register(server);
        api.register(server);
        new StaticFiles(getClass().getClassLoader(), "web-test").register(server);

        socket = new ServerSocket(0);
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

    private record Login(String sessionId, String csrf) {
    }

    private Login login(long userId, boolean superAdmin) {
        Session session = sessions.newSession();
        DashboardSession login = DashboardSession.login(session, userId, superAdmin, now.get());
        return new Login(session.getSessionId(), login.csrfToken());
    }

    private record Response(int code, HttpURLConnection connection, String body) {
        String header(String name) {
            return connection.getHeaderField(name);
        }

        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }

        String error() {
            return json().get("code").getAsString();
        }
    }

    private Response request(String method, String path, Login login, String body) throws IOException {
        return request(method, path, login, login == null ? null : login.csrf(), body, "application/json");
    }

    private Response request(String method, String path, Login login, String csrf, String body, String contentType)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        if (login != null) c.setRequestProperty("Cookie", "SessID=" + login.sessionId());
        if (csrf != null) c.setRequestProperty(DashboardSession.CSRF_HEADER, csrf);
        if (body != null) {
            c.setDoOutput(true);
            if (contentType != null) c.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new Response(code, c, text);
    }

    private static void assertApiHeaders(Response r) {
        assertEquals("no-store", r.header("Cache-Control"));
        assertEquals("no-referrer", r.header("Referrer-Policy"));
        assertEquals("DENY", r.header("X-Frame-Options"));
        assertEquals("nosniff", r.header("X-Content-Type-Options"));
        assertEquals(WebResponses.API_CSP, r.header("Content-Security-Policy"));
        assertTrue(r.header("Content-Type").startsWith("application/json"), r.header("Content-Type"));
    }

    @Test
    void notLoggedInGets401WithHeaders() throws IOException {
        Response r = request("GET", "/dashboard/api/me", null, null);
        assertEquals(401, r.code());
        assertEquals("not_logged_in", r.error());
        assertTrue(r.json().get("error").getAsString().contains("/dashboard"));
        assertApiHeaders(r);
    }

    @Test
    void meReturnsSessionAndLongsAsStrings() throws IOException {
        Response r = request("GET", "/dashboard/api/me", login(ADMIN, false), null);
        assertEquals(200, r.code());
        assertApiHeaders(r);
        assertEquals("1", r.json().get("userId").getAsJsonPrimitive().getAsString());
        assertTrue(r.json().get("userId").getAsJsonPrimitive().isString(), "snowflakes are sent as strings");
        assertFalse(r.json().get("superAdmin").getAsBoolean());
    }

    @Test
    void expiredSuperAdminSessionIsRejected() throws IOException {
        Login login = login(SUPER, true);
        assertEquals(200, request("GET", "/dashboard/api/admin/system", login, null).code());
        now.addAndGet(DashboardSession.SUPER_ADMIN_MAX_AGE);
        assertEquals(401, request("GET", "/dashboard/api/admin/system", login, null).code());
    }

    @Test
    void unknownPathsAndMethods() throws IOException {
        Login login = login(ADMIN, false);
        Response missing = request("GET", "/dashboard/api/nope", login, null);
        assertEquals(404, missing.code());
        assertEquals("not_found", missing.error());
        assertApiHeaders(missing);
        Response wrongMethod = request("POST", "/dashboard/api/me", login, "{}");
        assertEquals(405, wrongMethod.code());
        assertEquals("method_not_allowed", wrongMethod.error());
        assertTrue(wrongMethod.header("Allow").contains("GET"));
    }

    @Test
    void writesNeedCsrf() throws IOException {
        Login login = login(ADMIN, false);
        String path = "/dashboard/api/guilds/" + GUILD + "/settings";
        Response none = request("PUT", path, login, null, "{\"value\":\"x\"}", "application/json");
        assertEquals(403, none.code());
        assertEquals("csrf", none.error());
        assertEquals(403, request("PUT", path, login, "wrong", "{\"value\":\"x\"}", "application/json").code());
        assertEquals(403, request("DELETE", "/dashboard/api/guilds/" + GUILD + "/streamers/5", login, null, null, null).code());
        Response ok = request("PUT", path, login, "{\"value\":\"x\"}");
        assertEquals(200, ok.code());
        assertEquals("x", ok.json().get("got").getAsString());
        // Reads don't need it.
        assertEquals(200, request("GET", path, login, null, null, null).code());
    }

    @Test
    void writeBodiesMustBeJson() throws IOException {
        Login login = login(ADMIN, false);
        String path = "/dashboard/api/guilds/" + GUILD + "/settings";
        Response form = request("PUT", path, login, login.csrf(), "value=x", "application/x-www-form-urlencoded");
        assertEquals(415, form.code());
        assertEquals("not_json", form.error());
        assertEquals(415, request("PUT", path, login, login.csrf(), "{\"value\":\"x\"}", "text/plain").code());
        Response bad = request("PUT", path, login, "{not json");
        assertEquals(400, bad.code());
        assertEquals("bad_request", bad.error());
        Response big = request("PUT", path, login, "{\"value\":\"" + "a".repeat(ApiRequest.MAX_BODY) + "\"}");
        assertEquals(413, big.code());
        assertEquals(200, request("PUT", path, login, login.csrf(), "{\"value\":\"x\"}",
                "application/json; charset=utf-8").code());
    }

    @Test
    void accessLevelsPerRoute() throws IOException {
        String admin = "/dashboard/api/guilds/" + GUILD + "/settings";
        String own = "/dashboard/api/guilds/" + GUILD + "/me/streamer";

        assertEquals("ADMIN", request("GET", admin, login(ADMIN, false), null).json().get("level").getAsString());
        assertEquals("ADMIN", request("GET", admin, login(ROLE_ADMIN, false), null).json().get("level").getAsString());
        assertEquals("ADMIN", request("GET", own, login(ADMIN, false), null).json().get("level").getAsString());

        Response streamerOwn = request("GET", own, login(STREAMER, false), null);
        assertEquals(200, streamerOwn.code());
        assertEquals("STREAMER", streamerOwn.json().get("level").getAsString());
        assertEquals(String.valueOf(GUILD), streamerOwn.json().get("guildId").getAsString());
        Response streamerAdmin = request("GET", admin, login(STREAMER, false), null);
        assertEquals(403, streamerAdmin.code());
        assertEquals("forbidden", streamerAdmin.error());

        Response nobody = request("GET", own, login(NOBODY, false), null);
        assertEquals(403, nobody.code());
        assertEquals("no_access", nobody.error());
        assertEquals("You don't have access to this server's dashboard", nobody.json().get("error").getAsString());
        // Not in the guild at all, or the bot isn't in it: the same answer.
        assertEquals("no_access", request("GET", "/dashboard/api/guilds/" + OTHER_GUILD + "/me/streamer",
                login(ADMIN, false), null).error());
        assertEquals("no_access", request("GET", "/dashboard/api/guilds/" + NOT_IN_GUILD + "/me/streamer",
                login(ADMIN, false), null).error());
        assertEquals(404, request("GET", "/dashboard/api/guilds/abc/settings", login(ADMIN, false), null).code());
    }

    @Test
    void accessIsCheckedOnEveryRequest() throws IOException {
        Login login = login(ROLE_ADMIN, false);
        String admin = "/dashboard/api/guilds/" + GUILD + "/settings";
        assertEquals(200, request("GET", admin, login, null).code());
        store.modify(GUILD, info -> {
            info.adminRoles.clear();
            return null;
        });
        assertEquals(403, request("GET", admin, login, null).code(), "removing the admin role cuts access at once");
    }

    @Test
    void superAdminsReachEveryGuildTheBotIsIn() throws IOException {
        Login login = login(SUPER, true);
        Response other = request("GET", "/dashboard/api/guilds/" + OTHER_GUILD + "/settings", login, null);
        assertEquals(200, other.code());
        assertEquals("ADMIN", other.json().get("level").getAsString());
        Response notIn = request("GET", "/dashboard/api/guilds/" + NOT_IN_GUILD + "/settings", login, null);
        assertEquals(404, notIn.code());
        // The session flag counts even when the user isn't in the configured list (console recovery as user 0).
        assertEquals(200, request("GET", "/dashboard/api/guilds/" + GUILD + "/settings", login(0L, true), null).code());
    }

    @Test
    void superAdminRoutesNeedSuperAdminSession() throws IOException {
        Response normal = request("GET", "/dashboard/api/admin/system", login(ADMIN, false), null);
        assertEquals(403, normal.code());
        assertEquals("forbidden", normal.error());
        // Configured as a super admin, but this session wasn't logged in as one.
        assertEquals(403, request("GET", "/dashboard/api/admin/system", login(SUPER, false), null).code());
    }

    @Test
    void discordDown() throws IOException {
        discordUp.set(false);
        Login superAdmin = login(SUPER, true);
        assertEquals(200, request("GET", "/dashboard/api/admin/system", superAdmin, null).code());
        assertEquals(200, request("GET", "/dashboard/api/me", superAdmin, null).code());
        Response guild = request("GET", "/dashboard/api/guilds/" + GUILD + "/settings", superAdmin, null);
        assertEquals(503, guild.code());
        assertEquals("discord_unavailable", guild.error());
        assertTrue(guild.json().get("error").getAsString().contains("Discord is unavailable"));
        assertEquals(503, request("GET", "/dashboard/api/guilds/" + GUILD + "/me/streamer", login(STREAMER, false), null).code());
    }

    @Test
    void handlerResults() throws IOException {
        Login login = login(ADMIN, false);
        Response deleted = request("DELETE", "/dashboard/api/guilds/" + GUILD + "/streamers/5", login, null);
        assertEquals(204, deleted.code());
        assertEquals("no-store", deleted.header("Cache-Control"));
        assertEquals("5", request("GET", "/dashboard/api/guilds/" + GUILD + "/streamers/5", login, null)
                .json().get("twitchId").getAsString());
        assertEquals(404, request("GET", "/dashboard/api/guilds/" + GUILD + "/streamers/x", login, null).code());

        Response boom = request("GET", "/dashboard/api/boom", login, null);
        assertEquals(500, boom.code());
        assertEquals("internal", boom.error());
        assertFalse(boom.body.contains("broken"), "exception details aren't sent");

        Response invalid = request("POST", "/dashboard/api/invalid", login, "{}");
        assertEquals(400, invalid.code());
        assertEquals("invalid", invalid.error());
        assertEquals("channelId", invalid.json().get("field").getAsString());
    }

    @Test
    void logoutStillWorksAndUsesTheSameErrors() throws IOException {
        Login login = login(ADMIN, false);
        Response refused = request("POST", "/dashboard/api/logout", login, null, null, null);
        assertEquals(403, refused.code());
        assertEquals("csrf", refused.error());
        assertApiHeaders(refused);
        assertEquals(200, request("POST", "/dashboard/api/logout", login, null).code());
        assertEquals(401, request("GET", "/dashboard/api/me", login, null).code());
    }

    @Test
    void routesNeedGuildIdExactlyForGuildRequirements() {
        ApiRouter router = new ApiRouter(new Access(store, List.of()), (g, u) -> null, now::get);
        assertThrows(IllegalArgumentException.class, () -> router.get("/guilds/{guildId}/x", Requirement.SESSION, r -> null));
        assertThrows(IllegalArgumentException.class, () -> router.get("/x", Requirement.GUILD_ADMIN, r -> null));
    }

    // --- static files ---

    private static void assertPageHeaders(Response r) {
        assertEquals("no-referrer", r.header("Referrer-Policy"));
        assertEquals("DENY", r.header("X-Frame-Options"));
        assertEquals("nosniff", r.header("X-Content-Type-Options"));
        assertEquals(WebResponses.PAGE_CSP, r.header("Content-Security-Policy"));
    }

    @Test
    void staticFilesFromClasspath() throws IOException {
        Response index = request("GET", "/dashboard/", null, null);
        assertEquals(200, index.code());
        assertTrue(index.body().contains("test index"));
        assertTrue(index.header("Content-Type").startsWith("text/html"));
        assertEquals("no-cache", index.header("Cache-Control"));
        assertPageHeaders(index);

        Response js = request("GET", "/dashboard/js/app.js", null, null);
        assertEquals(200, js.code());
        assertTrue(js.header("Content-Type").startsWith("text/javascript"));
        assertPageHeaders(js);

        Response redirect = request("GET", "/dashboard", null, null);
        assertEquals(302, redirect.code());
        assertEquals("/dashboard/", redirect.header("Location"));

        // Query strings (the login lands on /dashboard/?guild=…) don't matter.
        assertEquals(200, request("GET", "/dashboard/?guild=" + GUILD, null, null).code());
        // The login endpoint isn't shadowed by the static files.
        assertEquals(400, request("GET", "/dashboard/login?token=nope", null, null).code());
    }

    @Test
    void staticFilesRefuseOddPaths() throws IOException {
        Response missing = request("GET", "/dashboard/missing.js", null, null);
        assertEquals(404, missing.code());
        assertPageHeaders(missing);
        assertEquals(404, request("GET", "/dashboard/.secret.txt", null, null).code());
        assertEquals(404, request("GET", "/dashboard/notes.unknownext", null, null).code());
        assertEquals(404, request("GET", "/dashboard/js/", null, null).code());
        assertEquals(405, request("POST", "/dashboard/js/app.js", null, null, "x", "text/plain").code());

        StaticFiles files = new StaticFiles(getClass().getClassLoader(), "web-test");
        assertEquals("web-test/index.html", files.resourceFor("/dashboard/"));
        assertEquals("web-test/js/app.js", files.resourceFor("/dashboard/js/app.js"));
        assertNull(files.resourceFor("/dashboard/../secret.txt"));
        assertNull(files.resourceFor("/dashboard/js/../../x.js"));
        assertNull(files.resourceFor("/dashboard/js//app.js"));
        assertNull(files.resourceFor("/dashboard/a\\b.js"));
        assertNull(files.resourceFor("/dashboard/.hidden/x.js"));
        assertNull(files.resourceFor("/dashboard/noext"));
        assertNull(files.resourceFor("/elsewhere/x.js"));
    }
}

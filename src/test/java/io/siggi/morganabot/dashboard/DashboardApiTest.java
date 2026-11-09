package io.siggi.morganabot.dashboard;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;
import io.siggi.http.session.Session;
import io.siggi.http.session.Sessions;
import io.siggi.morganabot.Access;
import io.siggi.morganabot.ServerSettings;
import io.siggi.morganabot.ServerSettings.SelfServiceMember;
import io.siggi.morganabot.ServerSettings.TwitchUser;
import io.siggi.morganabot.audit.Actor;
import io.siggi.morganabot.audit.AuditEntry;
import io.siggi.morganabot.audit.AuditLog;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;
import io.siggi.morganabot.diagnose.Check;
import io.siggi.morganabot.diagnose.DiagnoseResult;
import io.siggi.morganabot.problems.Problem;
import io.siggi.morganabot.problems.ServerProblems;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dashboard API end to end: real SiggiHTTP server, router, settings service, audit log and problem log, with a
 * fake Discord server and fake Twitch.
 */
class DashboardApiTest {
    private static final long GUILD = 100L;
    private static final long OTHER_GUILD = 200L;

    // Roles in GUILD, highest first: STAFF > VIP > ADMIN_ROLE > PING > BOT_ROLE > @everyone (= GUILD).
    private static final long STAFF = 50L;
    private static final long VIP = 51L;
    private static final long ADMIN_ROLE = 10L;
    private static final long PING = 60L;
    private static final long BOT_ROLE = 61L;
    private static final long OTHER_GUILD_ROLE = 2050L;

    private static final long LIVE = 800L;
    private static final long VIP_CHANNEL = 900L;
    private static final long NEWS = 901L;
    private static final long VOICE = 902L;
    private static final long OTHER_GUILD_CHANNEL = 2800L;

    private static final long ADMIN = 1L;
    private static final long ROLE_ADMIN = 2L;
    private static final long STREAMER = 3L;
    private static final long NOBODY = 4L;
    private static final long FRIEND = 6L;
    private static final long SUPER = 999L;

    @TempDir
    Path tempDir;

    private final Sessions sessions = Sessions.create(DashboardSession.SESSION_TTL);
    private final AtomicBoolean twitchRed = new AtomicBoolean(false);
    private final List<Integer> twitchBatches = new ArrayList<>();
    private final Map<String, TwitchUser> twitchUsers = new HashMap<>();
    private ServerInfoStore store;
    private AuditLog auditLog;
    private ServerProblems problems;
    private FakeGuild guild;
    private FakeGuild otherGuild;
    private ServerSocket socket;
    private String base;

    /** A fake Discord server. */
    private static final class FakeGuild implements GuildData {
        final long id;
        final String name;
        final List<RoleInfo> roles = new ArrayList<>();
        final List<ChannelInfo> channels = new ArrayList<>();
        final List<Long> otherChannels = new ArrayList<>();
        /** user → roles highest first (without @everyone); missing users aren't in the server. */
        final Map<Long, List<Long>> members = new LinkedHashMap<>();
        final Map<Long, Boolean> administrators = new HashMap<>();
        final List<String> diagnoseRuns = new ArrayList<>();

        FakeGuild(long id, String name) {
            this.id = id;
            this.name = name;
        }

        void role(long roleId, String roleName, boolean managed, boolean assignable) {
            roles.add(new RoleInfo(roleId, roleName, 0, 100 - roles.size(), roleId == id, managed, true, assignable,
                    assignable ? null : "It's above the bot's highest role."));
        }

        void member(long userId, boolean administrator, Long... roleIds) {
            members.put(userId, List.of(roleIds));
            administrators.put(userId, administrator);
        }

        @Override
        public long id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String iconUrl() {
            return null;
        }

        @Override
        public List<ChannelInfo> channels() {
            return channels;
        }

        @Override
        public ChannelInfo channel(long channelId) {
            for (ChannelInfo channel : channels) {
                if (channel.id == channelId) return channel;
            }
            return null;
        }

        @Override
        public boolean hasChannel(long channelId) {
            return channel(channelId) != null || otherChannels.contains(channelId);
        }

        @Override
        public List<RoleInfo> roles() {
            return roles;
        }

        @Override
        public RoleInfo role(long roleId) {
            for (RoleInfo role : roles) {
                if (role.id == roleId) return role;
            }
            return null;
        }

        @Override
        public List<MemberInfo> searchMembers(String query, int limit) {
            List<MemberInfo> result = new ArrayList<>();
            for (long userId : members.keySet()) {
                MemberInfo info = member(userId);
                if (info.username.contains(query.toLowerCase(Locale.ROOT)) && result.size() < limit) result.add(info);
            }
            return result;
        }

        @Override
        public MemberInfo member(long userId) {
            return members.containsKey(userId) ? new MemberInfo(userId, "user" + userId, "User " + userId, null, false) : null;
        }

        @Override
        public MemberInfo cachedMember(long userId) {
            return member(userId);
        }

        @Override
        public Access.MemberView memberView(long userId) {
            List<Long> roleIds = members.get(userId);
            if (roleIds == null) return null;
            List<Long> all = new ArrayList<>(roleIds);
            all.add(id);
            return new Access.MemberView(id, userId, administrators.getOrDefault(userId, false), all);
        }

        @Override
        public SelfServiceMember selfServiceMember(long userId) {
            List<Long> roleIds = members.get(userId);
            if (roleIds == null) return SelfServiceMember.absent(id, userId);
            return new SelfServiceMember(id, userId, "user" + userId, true, roleIds);
        }

        @Override
        public DiagnoseResult diagnose(String onlyTwitchId) {
            diagnoseRuns.add(String.valueOf(onlyTwitchId));
            return new DiagnoseResult(id, 1L, true,
                    List.of(Check.error("channel.send", "Can't post in #live", "Give the bot Send Messages")), List.of());
        }
    }

    private static final class FakeTwitch implements ServerSettings.Twitch {
        final Map<String, TwitchUser> byLogin = new HashMap<>();

        @Override
        public TwitchUser findUserByLogin(String login) {
            return byLogin.get(login.toLowerCase(Locale.ROOT));
        }

        @Override
        public void triggerUpdate() {
        }
    }

    @BeforeEach
    void start() throws IOException {
        store = new ServerInfoStore(tempDir.toFile(), new Gson());
        auditLog = new AuditLog(tempDir.toFile(), new Gson(), store, AuditLog.Names.NONE);
        problems = new ServerProblems(tempDir.toFile(), new Gson(), store);
        FakeTwitch twitch = new FakeTwitch();
        for (String[] user : new String[][]{{"1001", "alice"}, {"1002", "bob"}, {"1003", "carol"}}) {
            TwitchUser u = new TwitchUser(user[0], user[1], user[1].toUpperCase(Locale.ROOT));
            twitch.byLogin.put(user[1], u);
            twitchUsers.put(user[0], u);
        }

        guild = new FakeGuild(GUILD, "Main");
        guild.role(STAFF, "Staff", false, true);
        guild.role(VIP, "VIP", false, true);
        guild.role(ADMIN_ROLE, "Mods", false, true);
        guild.role(PING, "Ping", false, false);
        guild.role(BOT_ROLE, "SomeBot", true, false);
        guild.role(GUILD, "@everyone", false, false);
        guild.channels.add(new GuildData.ChannelInfo(LIVE, "live", GuildData.ChannelInfo.TEXT, null, true, false));
        guild.channels.add(new GuildData.ChannelInfo(VIP_CHANNEL, "vip-live", GuildData.ChannelInfo.TEXT, null, false, false));
        guild.channels.add(new GuildData.ChannelInfo(NEWS, "news", GuildData.ChannelInfo.ANNOUNCEMENT, null, true, true));
        guild.otherChannels.add(VOICE);
        guild.member(ADMIN, true);
        guild.member(ROLE_ADMIN, false, ADMIN_ROLE);
        guild.member(STREAMER, false, VIP);
        guild.member(NOBODY, false);
        guild.member(FRIEND, false);

        otherGuild = new FakeGuild(OTHER_GUILD, "Other");
        otherGuild.role(OTHER_GUILD_ROLE, "Elsewhere", false, true);
        otherGuild.role(OTHER_GUILD, "@everyone", false, false);
        otherGuild.channels.add(new GuildData.ChannelInfo(OTHER_GUILD_CHANNEL, "other", GuildData.ChannelInfo.TEXT, null, true, false));
        otherGuild.member(STREAMER, false);

        store.modify(GUILD, info -> {
            info.enabledTwitchTracking = true;
            info.channelToPostLiveNotifications = LIVE;
            info.adminRoles.add(ADMIN_ROLE);
            info.selfServiceRoles.add(new ServerInfo.SelfServiceRole(VIP, VIP_CHANNEL));
            info.selfServiceRoles.add(new ServerInfo.SelfServiceRole(STAFF, 0L));
            ServerInfo.Streamer streamer = new ServerInfo.Streamer(STREAMER, "1001");
            streamer.selfService = true;
            streamer.channelToPostLiveNotifications = VIP_CHANNEL;
            info.streamers.add(streamer);
            info.streamers.add(new ServerInfo.Streamer(0L, "1002"));
            return null;
        });

        Access access = new Access(store, List.of(SUPER));
        ServerSettings settings = new ServerSettings(store, twitch, auditLog);
        settings.setAccess(access);
        settings.setMembers((guildId, userId) -> guildId == GUILD ? guild.selfServiceMember(userId) : null);

        GuildData.Provider provider = new GuildData.Provider() {
            @Override
            public GuildData guild(long guildId) {
                return guildId == GUILD ? guild : guildId == OTHER_GUILD ? otherGuild : null;
            }

            @Override
            public List<GuildData> all() {
                return List.of(guild, otherGuild);
            }

            @Override
            public boolean discordAvailable() {
                return true;
            }

            @Override
            public String userName(long userId) {
                return "user" + userId;
            }
        };
        GuildResolver resolver = (guildId, userId) -> {
            GuildData g = provider.guild(guildId);
            if (g == null) return null;
            Access.MemberView view = g.memberView(userId);
            return new GuildResolver.GuildMember(null, null, view == null ? Access.MemberView.outsider(guildId, userId) : view);
        };
        ApiRouter api = new ApiRouter(access, resolver, System::currentTimeMillis);
        DashboardApi.TwitchUsers lookup = (Collection<String> ids) -> {
            twitchBatches.add(ids.size());
            Map<String, TwitchUser> result = new HashMap<>();
            for (String id : ids) {
                if (twitchUsers.containsKey(id)) result.put(id, twitchUsers.get(id));
            }
            return result;
        };
        new DashboardApi(store, settings, access, auditLog, problems, provider, lookup, twitchRed::get).register(api);

        HTTPServer server = new HTTPServerBuilder().setSessions(sessions).build();
        api.register(server);
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
        base = "http://127.0.0.1:" + socket.getLocalPort() + ApiRouter.BASE;
    }

    @AfterEach
    void stop() throws IOException {
        socket.close();
    }

    // --- HTTP helpers ---

    private record Login(String sessionId, String csrf) {
    }

    private Login login(long userId) {
        return login(userId, false);
    }

    private Login login(long userId, boolean superAdmin) {
        Session session = sessions.newSession();
        DashboardSession login = DashboardSession.login(session, userId, superAdmin, System.currentTimeMillis());
        return new Login(session.getSessionId(), login.csrfToken());
    }

    private record Response(int code, String body) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }

        String error() {
            return json().get("code").getAsString();
        }

        String field() {
            JsonElement field = json().get("field");
            return field == null || field.isJsonNull() ? null : field.getAsString();
        }
    }

    private Response get(Login login, String path) throws IOException {
        return request("GET", login, path, null);
    }

    private Response request(String method, Login login, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        if (method.equals("PATCH")) {
            // HttpURLConnection can't send PATCH; SiggiHTTP doesn't know X-HTTP-Method-Override, so send it raw.
            return raw(method, login, path, body);
        }
        c.setRequestMethod(method);
        c.setRequestProperty("Cookie", "SessID=" + login.sessionId());
        c.setRequestProperty(DashboardSession.CSRF_HEADER, login.csrf());
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        return new Response(code, in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    private Response raw(String method, Login login, String path, String body) throws IOException {
        URL url = new URL(base + path);
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        try (Socket s = new Socket(url.getHost(), url.getPort())) {
            String head = method + " " + url.getFile() + " HTTP/1.1\r\n"
                    + "Host: " + url.getHost() + "\r\n"
                    + "Cookie: SessID=" + login.sessionId() + "\r\n"
                    + DashboardSession.CSRF_HEADER + ": " + login.csrf() + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
            String response = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = Integer.parseInt(response.split(" ", 3)[1]);
            int split = response.indexOf("\r\n\r\n");
            String rawBody = split < 0 ? "" : response.substring(split + 4);
            if (response.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) rawBody = dechunk(rawBody);
            return new Response(code, rawBody);
        }
    }

    private static String dechunk(String body) {
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (pos < body.length()) {
            int lineEnd = body.indexOf("\r\n", pos);
            if (lineEnd < 0) break;
            int size = Integer.parseInt(body.substring(pos, lineEnd).trim(), 16);
            if (size == 0) break;
            sb.append(body, lineEnd + 2, lineEnd + 2 + size);
            pos = lineEnd + 2 + size + 2;
        }
        return sb.toString();
    }

    private ServerInfo.Streamer streamer(String twitchId) {
        for (ServerInfo.Streamer s : store.get(GUILD).streamers) {
            if (twitchId.equals(s.twitchId)) return s;
        }
        return null;
    }

    private static String q(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    // --- /me ---

    @Test
    void meListsServersWithAccessLevels() throws IOException {
        JsonObject me = get(login(STREAMER), "/me").json();
        assertEquals("3", me.get("userId").getAsString());
        assertFalse(me.get("superAdmin").getAsBoolean());
        assertFalse(me.get("csrfToken").getAsString().isEmpty());
        JsonArray guilds = me.getAsJsonArray("guilds");
        // A member of OTHER_GUILD too, but without access there.
        assertEquals(1, guilds.size());
        assertEquals("100", guilds.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("streamer", guilds.get(0).getAsJsonObject().get("access").getAsString());

        assertEquals("admin", get(login(ROLE_ADMIN), "/me").json().getAsJsonArray("guilds").get(0).getAsJsonObject().get("access").getAsString());
        assertEquals(0, get(login(NOBODY), "/me").json().getAsJsonArray("guilds").size());

        JsonObject superMe = get(login(SUPER, true), "/me").json();
        assertTrue(superMe.get("superAdmin").getAsBoolean());
        assertEquals(2, superMe.getAsJsonArray("guilds").size(), "super admins can open every server");
    }

    // --- a streamer's own entry ---

    @Test
    void streamerSeesOwnEntryAndPermissions() throws IOException {
        Response r = get(login(STREAMER), "/guilds/100/me/streamer");
        assertEquals(200, r.code(), r.body());
        JsonObject json = r.json();
        JsonObject streamer = json.getAsJsonObject("streamer");
        assertEquals("1001", streamer.get("twitchId").getAsString());
        assertEquals("alice", streamer.get("twitchLogin").getAsString());
        assertEquals("ALICE", streamer.get("twitchDisplayName").getAsString());
        assertEquals("900", streamer.getAsJsonObject("channel").get("id").getAsString());
        assertEquals("role", streamer.get("channelSource").getAsString());
        assertFalse(json.get("canCustomizeMessage").getAsBoolean());
        assertFalse(json.getAsJsonObject("allowedMentions").get("everyone").getAsBoolean());
        assertTrue(json.getAsJsonObject("selfService").get("eligible").getAsBoolean());
        assertEquals("51", json.getAsJsonObject("selfService").getAsJsonObject("role").get("id").getAsString());

        // Streamers can't use admin endpoints.
        assertEquals(403, get(login(STREAMER), "/guilds/100/settings").code());
        assertEquals(403, get(login(NOBODY), "/guilds/100/me/streamer").code());
    }

    @Test
    void ownTemplateFollowsMessageRules() throws IOException {
        Login streamer = login(STREAMER);
        Response refused = request("PUT", streamer, "/guilds/100/me/streamer/template", "{\"template\": \"hi\"}");
        assertEquals(400, refused.code());
        assertEquals("template", refused.field());

        store.modify(GUILD, info -> {
            info.messageCustomizationRules.add(new ServerInfo.MessageCustomizationRule(VIP, false, List.of(PING)));
            return null;
        });
        JsonObject json = get(streamer, "/guilds/100/me/streamer").json();
        assertTrue(json.get("canCustomizeMessage").getAsBoolean());
        assertEquals("60", json.getAsJsonObject("allowedMentions").getAsJsonArray("roles").get(0).getAsJsonObject().get("id").getAsString());

        Response badMention = request("PUT", streamer, "/guilds/100/me/streamer/template", "{\"template\": \"@everyone ${twitch_link}\"}");
        assertEquals(400, badMention.code());
        assertTrue(badMention.json().get("error").getAsString().contains("@everyone"));

        Response ok = request("PUT", streamer, "/guilds/100/me/streamer/template", "{\"template\": \"<@&60> live! ${twitch_link}\"}");
        assertEquals(200, ok.code(), ok.body());
        assertEquals("<@&60> live! ${twitch_link}", streamer("1001").liveNotificationTemplate);
        AuditEntry entry = auditLog.read(GUILD, false).get(0);
        assertEquals(AuditEntry.STREAMER_TEMPLATE, entry.type);
        assertEquals(Actor.Source.SELF_SERVICE, entry.actor.source);

        assertEquals(200, request("PUT", streamer, "/guilds/100/me/streamer/template", "{\"template\": null}").code());
        assertNull(streamer("1001").liveNotificationTemplate);
    }

    @Test
    void ownTwitchSetAndRemove() throws IOException {
        Login login = login(STREAMER);
        Response set = request("PUT", login, "/guilds/100/me/streamer/twitch", "{\"twitchName\": \"carol\"}");
        assertEquals(200, set.code(), set.body());
        assertEquals("1003", set.json().getAsJsonObject("streamer").get("twitchId").getAsString());
        assertNull(streamer("1001"));

        Response unknown = request("PUT", login, "/guilds/100/me/streamer/twitch", "{\"twitchName\": \"nobody_here\"}");
        assertEquals(400, unknown.code());

        assertEquals(200, request("DELETE", login, "/guilds/100/me/streamer", null).code());
        assertNull(streamer("1003"));
        // No entry any more, so no streamer access.
        assertEquals(403, get(login, "/guilds/100/me/streamer").code());
    }

    // --- admin roles and message rules ---

    @Test
    void adminRolesOnlyEditableByAdministrators() throws IOException {
        Login roleAdmin = login(ROLE_ADMIN);
        JsonObject json = get(roleAdmin, "/guilds/100/admin-roles").json();
        assertFalse(json.get("canEdit").getAsBoolean());
        assertEquals("Mods", json.getAsJsonArray("roles").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(403, request("PUT", roleAdmin, "/guilds/100/admin-roles", "{\"roles\": [\"10\", \"51\"]}").code());

        Login admin = login(ADMIN);
        assertTrue(get(admin, "/guilds/100/admin-roles").json().get("canEdit").getAsBoolean());
        Response foreign = request("PUT", admin, "/guilds/100/admin-roles", "{\"roles\": [\"" + OTHER_GUILD_ROLE + "\"]}");
        assertEquals(400, foreign.code());
        assertEquals("roles", foreign.field());
        assertEquals(400, request("PUT", admin, "/guilds/100/admin-roles", "{\"roles\": [\"100\"]}").code(), "@everyone");
        assertEquals(List.of(ADMIN_ROLE), store.get(GUILD).adminRoles);

        assertEquals(200, request("PUT", admin, "/guilds/100/admin-roles", "{\"roles\": [\"51\"]}").code());
        assertEquals(List.of(VIP), store.get(GUILD).adminRoles);
        // ROLE_ADMIN lost their admin role, effective on the next request.
        assertEquals(403, get(roleAdmin, "/guilds/100/settings").code());

        // Super admin sessions can edit even outside the server.
        assertEquals(200, request("PUT", login(SUPER, true), "/guilds/100/admin-roles", "{\"roles\": []}").code());
        assertTrue(store.get(GUILD).adminRoles.isEmpty());
        assertEquals(Actor.Source.SUPER_ADMIN, auditLog.read(GUILD, true).get(0).actor.source);
    }

    @Test
    void messageRulesCheckEveryRole() throws IOException {
        Login admin = login(ADMIN);
        Response foreign = request("PUT", admin, "/guilds/100/message-rules",
                "{\"rules\": [{\"roleId\": \"51\", \"allowEveryone\": true, \"allowedMentionRoles\": [\"" + OTHER_GUILD_ROLE + "\"]}]}");
        assertEquals(400, foreign.code());
        assertTrue(store.get(GUILD).messageCustomizationRules.isEmpty());

        Response ok = request("PUT", admin, "/guilds/100/message-rules",
                "{\"rules\": [{\"roleId\": \"51\", \"allowEveryone\": true, \"allowedMentionRoles\": [\"60\"]}]}");
        assertEquals(200, ok.code(), ok.body());
        JsonObject rule = ok.json().getAsJsonArray("rules").get(0).getAsJsonObject();
        assertEquals("VIP", rule.getAsJsonObject("role").get("name").getAsString());
        assertTrue(rule.get("allowEveryone").getAsBoolean());
        assertEquals("Ping", rule.getAsJsonArray("allowedMentionRoles").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(List.of(PING), store.get(GUILD).messageCustomizationRules.get(0).allowedMentionRoles);
    }

    // --- settings ---

    @Test
    void settingsReadAndPatch() throws IOException {
        Login admin = login(ADMIN);
        JsonObject json = get(admin, "/guilds/100/settings").json();
        assertFalse(json.has("enabledTwitchTracking"), "normal sessions never see the Twitch switch");
        assertTrue(json.get("twitchDisabledMessage").isJsonNull());
        assertEquals("800", json.getAsJsonObject("liveChannel").get("id").getAsString());
        assertEquals(1800, json.get("notificationCooldownSeconds").getAsInt());
        assertTrue(get(login(SUPER, true), "/guilds/100/settings").json().get("enabledTwitchTracking").getAsBoolean());

        Response patched = request("PATCH", admin, "/guilds/100/settings",
                "{\"liveChannelId\": \"901\", \"liveRoleId\": \"51\", \"deleteOnOffline\": true, "
                        + "\"notificationCooldownSeconds\": 60, \"liveMessageTemplate\": \"${streamer_name} is live\"}");
        assertEquals(200, patched.code(), patched.body());
        ServerInfo info = store.get(GUILD);
        assertEquals(NEWS, info.channelToPostLiveNotifications);
        assertEquals(VIP, info.roleForLiveUsers);
        assertTrue(info.deleteNotificationsOnOffline);
        assertEquals(60_000L, info.streamNotificationCooldown);
        assertEquals("${streamer_name} is live", info.liveNotificationTemplate);
        assertEquals(Actor.Source.DASHBOARD, auditLog.read(GUILD, false).get(0).actor.source);

        assertEquals(200, request("PATCH", admin, "/guilds/100/settings", "{\"liveMessageTemplate\": null, \"liveRoleId\": null}").code());
        assertNull(store.get(GUILD).liveNotificationTemplate);
        assertEquals(0L, store.get(GUILD).roleForLiveUsers);
    }

    @Test
    void settingsRejectForeignAndWrongChannelsWithoutChangingAnything() throws IOException {
        Login admin = login(ADMIN);
        Response foreign = request("PATCH", admin, "/guilds/100/settings",
                "{\"deleteOnOffline\": true, \"liveChannelId\": \"" + OTHER_GUILD_CHANNEL + "\"}");
        assertEquals(400, foreign.code());
        assertEquals("liveChannelId", foreign.field());
        assertFalse(store.get(GUILD).deleteNotificationsOnOffline, "nothing is changed when one value is bad");

        Response voice = request("PATCH", admin, "/guilds/100/settings", "{\"liveChannelId\": \"902\"}");
        assertEquals(400, voice.code());
        assertTrue(voice.json().get("error").getAsString().contains("text or announcement"));

        assertEquals("liveRoleId", request("PATCH", admin, "/guilds/100/settings", "{\"liveRoleId\": \"2050\"}").field());
        assertEquals(400, request("PATCH", admin, "/guilds/100/settings", "{\"liveRoleId\": \"61\"}").code(), "managed role");
        assertEquals(400, request("PATCH", admin, "/guilds/100/settings", "{\"notificationCooldownSeconds\": -1}").code());
        assertEquals(400, request("PATCH", admin, "/guilds/100/settings", "{\"nope\": 1}").code());
        assertEquals(400, request("PATCH", admin, "/guilds/100/settings", "{\"liveChannelId\": \"abc\"}").code());

        Response twitchSwitch = request("PATCH", admin, "/guilds/100/settings", "{\"enabledTwitchTracking\": false}");
        assertEquals(403, twitchSwitch.code());
        assertTrue(store.get(GUILD).enabledTwitchTracking);
        assertEquals(LIVE, store.get(GUILD).channelToPostLiveNotifications);
    }

    @Test
    void twitchOffGivesTwitchDisabled() throws IOException {
        store.modify(GUILD, info -> {
            info.enabledTwitchTracking = false;
            return null;
        });
        Login admin = login(ADMIN);
        assertFalse(get(admin, "/guilds/100/settings").json().get("twitchDisabledMessage").isJsonNull());
        Response r = request("PATCH", admin, "/guilds/100/settings", "{\"deleteOnOffline\": true}");
        assertEquals(409, r.code());
        assertEquals("twitch_disabled", r.error());
        // Admin roles aren't about Twitch.
        assertEquals(200, request("PUT", admin, "/guilds/100/admin-roles", "{\"roles\": [\"10\"]}").code());
    }

    // --- channels, roles, members ---

    @Test
    void channelsRolesAndMembers() throws IOException {
        Login admin = login(ADMIN);
        JsonArray channels = get(admin, "/guilds/100/channels").json().getAsJsonArray("channels");
        assertEquals(3, channels.size());
        assertEquals("announcement", channels.get(2).getAsJsonObject().get("type").getAsString());
        assertFalse(channels.get(1).getAsJsonObject().get("botCanPost").getAsBoolean());

        JsonArray roles = get(admin, "/guilds/100/roles").json().getAsJsonArray("roles");
        assertEquals("50", roles.get(0).getAsJsonObject().get("id").getAsString());
        JsonObject ping = roles.get(3).getAsJsonObject();
        assertFalse(ping.get("botCanAssign").getAsBoolean());
        assertNotNull(ping.get("botCantAssignReason").getAsString());

        JsonArray members = get(admin, "/guilds/100/members?q=" + q("user6")).json().getAsJsonArray("members");
        assertEquals(1, members.size());
        assertEquals("6", members.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(0, get(admin, "/guilds/100/members").json().getAsJsonArray("members").size());
    }

    // --- streamers ---

    @Test
    void streamerListLooksUpTwitchNamesInOneBatch() throws IOException {
        JsonObject json = get(login(ADMIN), "/guilds/100/streamers").json();
        assertEquals(List.of(2), twitchBatches);
        JsonArray list = json.getAsJsonArray("streamers");
        assertEquals(2, list.size());
        JsonObject alice = list.get(0).getAsJsonObject();
        assertEquals("alice", alice.get("twitchLogin").getAsString());
        assertEquals("ALICE", alice.get("twitchDisplayName").getAsString());
        assertEquals("https://www.twitch.tv/alice", alice.get("twitchUrl").getAsString());
        assertTrue(alice.get("selfService").getAsBoolean());
        assertEquals("3", alice.getAsJsonObject("discordUser").get("id").getAsString());
        JsonObject bob = list.get(1).getAsJsonObject();
        assertEquals("default", bob.get("channelSource").getAsString());
        assertEquals("800", bob.getAsJsonObject("channel").get("id").getAsString());
        assertTrue(bob.get("discordUser").isJsonNull());
    }

    @Test
    void addEditAndDeleteStreamers() throws IOException {
        Login admin = login(ADMIN);
        assertEquals("channelId", request("POST", admin, "/guilds/100/streamers",
                "{\"twitchName\": \"carol\", \"channelId\": \"" + OTHER_GUILD_CHANNEL + "\"}").field());
        assertEquals("discordUserId", request("POST", admin, "/guilds/100/streamers",
                "{\"twitchName\": \"carol\", \"discordUserId\": \"12345\"}").field(), "not a member");
        assertNull(streamer("1003"));

        Response added = request("POST", admin, "/guilds/100/streamers",
                "{\"twitchName\": \"carol\", \"discordUserId\": \"6\", \"channelId\": \"901\"}");
        assertEquals(201, added.code(), added.body());
        assertEquals("carol", added.json().get("twitchLogin").getAsString());
        assertEquals(FRIEND, streamer("1003").discordId);
        assertEquals(NEWS, streamer("1003").channelToPostLiveNotifications);
        assertEquals(ADMIN, streamer("1003").addedBy);

        assertEquals(400, request("POST", admin, "/guilds/100/streamers", "{\"twitchName\": \"carol\"}").code(), "already added");

        Response patched = request("PATCH", admin, "/guilds/100/streamers/1003",
                "{\"channelId\": null, \"discordUserId\": null, \"template\": \"<@6> ${twitch_link}\"}");
        assertEquals(200, patched.code(), patched.body());
        ServerInfo.Streamer carol = streamer("1003");
        assertEquals(0L, carol.channelToPostLiveNotifications);
        assertEquals(0L, carol.discordId);
        assertEquals("<@6> ${twitch_link}", carol.liveNotificationTemplate);
        assertEquals(ADMIN, carol.templateSetBy);
        assertEquals(400, request("PATCH", admin, "/guilds/100/streamers/1003", "{\"channelOverridePinned\": false}").code(),
                "admin-added streamers can't follow roles");

        assertEquals(404, request("PATCH", admin, "/guilds/100/streamers/4242", "{\"template\": null}").code());
        assertEquals(200, request("DELETE", admin, "/guilds/100/streamers/1003", null).code());
        assertNull(streamer("1003"));
        assertEquals(404, request("DELETE", admin, "/guilds/100/streamers/1003", null).code());
    }

    @Test
    void adminChannelChangePinsAndUnpinRecalculates() throws IOException {
        Login admin = login(ADMIN);
        Response pinned = request("PATCH", admin, "/guilds/100/streamers/1001", "{\"channelId\": \"800\"}");
        assertEquals(200, pinned.code(), pinned.body());
        assertTrue(streamer("1001").channelOverridePinned);
        assertEquals(LIVE, streamer("1001").channelToPostLiveNotifications);
        assertEquals("override", pinned.json().get("channelSource").getAsString());

        assertEquals(400, request("PATCH", admin, "/guilds/100/streamers/1001",
                "{\"channelId\": \"800\", \"channelOverridePinned\": false}").code());

        Response unpinned = request("PATCH", admin, "/guilds/100/streamers/1001", "{\"channelOverridePinned\": false}");
        assertEquals(200, unpinned.code(), unpinned.body());
        assertFalse(streamer("1001").channelOverridePinned);
        assertEquals(VIP_CHANNEL, streamer("1001").channelToPostLiveNotifications, "recalculated from the VIP role");
        assertEquals("role", unpinned.json().get("channelSource").getAsString());

        // Pinning without a channel keeps the current one.
        assertEquals(200, request("PATCH", admin, "/guilds/100/streamers/1001", "{\"channelOverridePinned\": true}").code());
        assertTrue(streamer("1001").channelOverridePinned);
        assertEquals(VIP_CHANNEL, streamer("1001").channelToPostLiveNotifications);
    }

    // --- self-service ---

    @Test
    void selfServiceSortedByRoleOrderAndValidated() throws IOException {
        Login admin = login(ADMIN);
        JsonObject json = get(admin, "/guilds/100/self-service").json();
        JsonArray roles = json.getAsJsonArray("roles");
        // Stored as VIP, STAFF; Discord's order puts STAFF first.
        assertEquals("50", roles.get(0).getAsJsonObject().getAsJsonObject("role").get("id").getAsString());
        assertTrue(roles.get(0).getAsJsonObject().get("channel").isJsonNull());
        assertEquals("900", roles.get(1).getAsJsonObject().getAsJsonObject("channel").get("id").getAsString());
        assertEquals(7, json.get("removeAfterDays").getAsInt());

        assertEquals(400, request("PUT", admin, "/guilds/100/self-service",
                "{\"roles\": [{\"roleId\": \"51\", \"channelId\": \"" + OTHER_GUILD_CHANNEL + "\"}], \"removeAfterDays\": 3}").code());
        assertEquals(400, request("PUT", admin, "/guilds/100/self-service",
                "{\"roles\": [{\"roleId\": \"2050\"}], \"removeAfterDays\": 3}").code());
        assertEquals(400, request("PUT", admin, "/guilds/100/self-service", "{\"roles\": []}").code());
        assertEquals(7, store.get(GUILD).selfServiceRemoveAfterDays);

        Response ok = request("PUT", admin, "/guilds/100/self-service",
                "{\"roles\": [{\"roleId\": \"51\", \"channelId\": \"901\"}], \"removeAfterDays\": 0}");
        assertEquals(200, ok.code(), ok.body());
        ServerInfo info = store.get(GUILD);
        assertEquals(0, info.selfServiceRemoveAfterDays);
        assertEquals(1, info.selfServiceRoles.size());
        assertEquals(NEWS, info.selfServiceRoles.get(0).channelId);
        // The self-added streamer follows the new channel of their role straight away.
        assertEquals(NEWS, streamer("1001").channelToPostLiveNotifications);
    }

    // --- audit ---

    @Test
    void auditIsPagedNewestFirstAndHidesSuperAdminEntries() throws IOException {
        for (int i = 1; i <= 5; i++) {
            AuditEntry entry = new AuditEntry(i % 2 == 0 ? AuditEntry.LIVE_CHANNEL : AuditEntry.LIVE_ROLE);
            entry.time = i * 1000L;
            entry.change("x", null, AuditEntry.Value.number(i));
            auditLog.record(GUILD, Actor.dashboard(ADMIN, "admin"), entry);
        }
        AuditEntry hidden = new AuditEntry("guildLeave");
        hidden.time = 6000L;
        hidden.visibility = AuditEntry.VISIBILITY_SUPER_ADMIN;
        auditLog.record(GUILD, Actor.superAdmin(SUPER, "boss"), hidden);

        Login admin = login(ADMIN);
        JsonObject page1 = get(admin, "/guilds/100/audit?limit=2").json();
        JsonArray entries = page1.getAsJsonArray("entries");
        assertEquals(2, entries.size());
        assertEquals("5000", entries.get(0).getAsJsonObject().get("time").getAsString());
        assertEquals("4000", page1.get("nextBefore").getAsString());
        JsonObject page2 = get(admin, "/guilds/100/audit?limit=2&before=4000").json();
        assertEquals("3000", page2.getAsJsonArray("entries").get(0).getAsJsonObject().get("time").getAsString());
        JsonObject last = get(admin, "/guilds/100/audit?limit=2&before=2000").json();
        assertEquals(1, last.getAsJsonArray("entries").size());
        assertTrue(last.get("nextBefore").isJsonNull());

        assertEquals(2, get(admin, "/guilds/100/audit?type=liveChannel").json().getAsJsonArray("entries").size());
        assertEquals("6000", get(login(SUPER, true), "/guilds/100/audit").json().getAsJsonArray("entries")
                .get(0).getAsJsonObject().get("time").getAsString(), "super admins see everything");
        assertEquals(403, get(login(STREAMER), "/guilds/100/audit").code());
    }

    @Test
    void auditPagesNeverSplitEntriesWithTheSameTime() throws IOException {
        for (int i = 0; i < 3; i++) {
            AuditEntry entry = new AuditEntry(AuditEntry.LIVE_ROLE);
            entry.time = 1000L;
            entry.change("x", null, AuditEntry.Value.number(i));
            auditLog.record(GUILD, Actor.system(), entry);
        }
        JsonObject page = get(login(ADMIN), "/guilds/100/audit?limit=2").json();
        assertEquals(3, page.getAsJsonArray("entries").size());
        assertTrue(page.get("nextBefore").isJsonNull());
    }

    // --- Diagnose and problems ---

    @Test
    void diagnoseRunsNowForOneOrAllStreamers() throws IOException {
        Login admin = login(ADMIN);
        Response all = request("POST", admin, "/guilds/100/diagnose", null);
        assertEquals(200, all.code(), all.body());
        assertEquals("ERROR", all.json().getAsJsonArray("server").get(0).getAsJsonObject().get("level").getAsString());
        assertEquals(200, request("POST", admin, "/guilds/100/diagnose?streamer=1001", null).code());
        assertEquals(404, request("POST", admin, "/guilds/100/diagnose?streamer=4242", null).code());
        assertEquals(List.of("null", "1001"), guild.diagnoseRuns);
    }

    @Test
    void problemsListDiagnoseAndTwitchFlag() throws IOException {
        Problem a = problems.record(GUILD, new Problem(Problem.SEND_LIVE_MESSAGE, Problem.Target.channel(LIVE, "live"), null, "Missing permission"));
        problems.record(GUILD, new Problem(Problem.ADD_LIVE_ROLE, Problem.Target.role(VIP, "VIP"), null, "Above the bot"));
        Login admin = login(ADMIN);
        JsonObject json = get(admin, "/guilds/100/problems").json();
        assertEquals(2, json.getAsJsonArray("problems").size());
        assertEquals(1, json.getAsJsonObject("diagnose").getAsJsonArray("server").size());
        assertFalse(json.get("twitchTrouble").getAsBoolean());
        assertTrue(json.get("twitchMessage").isJsonNull());

        twitchRed.set(true);
        json = get(admin, "/guilds/100/problems").json();
        assertTrue(json.get("twitchTrouble").getAsBoolean());
        assertEquals(DashboardApi.TWITCH_TROUBLE, json.get("twitchMessage").getAsString());

        assertEquals(200, request("DELETE", admin, "/guilds/100/problems/" + a.id, null).code());
        assertEquals(404, request("DELETE", admin, "/guilds/100/problems/" + a.id, null).code());
        assertEquals(1, problems.list(GUILD).size());
        assertEquals(1, request("DELETE", admin, "/guilds/100/problems", null).json().get("dismissed").getAsInt());
        assertTrue(problems.list(GUILD).isEmpty());
    }

    @Test
    void idsMayBeNumbersOrStrings() throws ApiError {
        assertEquals(0L, DashboardApi.id(null, "f"));
        assertEquals(0L, DashboardApi.id(JsonParser.parseString("null"), "f"));
        assertEquals(0L, DashboardApi.id(JsonParser.parseString("\"\""), "f"));
        assertEquals(123L, DashboardApi.id(JsonParser.parseString("\"123\""), "f"));
        assertEquals(123L, DashboardApi.id(JsonParser.parseString("123"), "f"));
        assertThrows(ApiError.class, () -> DashboardApi.id(JsonParser.parseString("\"-5\""), "f"));
        assertThrows(ApiError.class, () -> DashboardApi.id(JsonParser.parseString("true"), "f"));
        assertThrows(ApiError.class, () -> DashboardApi.id(JsonParser.parseString("[1]"), "f"));
        assertThrows(ApiError.class, () -> DashboardApi.id(JsonParser.parseString("1.5"), "f"));
    }
}

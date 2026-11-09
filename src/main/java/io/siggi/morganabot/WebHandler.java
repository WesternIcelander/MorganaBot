package io.siggi.morganabot;

import io.siggi.morganabot.config.Configuration;
import com.google.gson.JsonObject;
import io.siggi.morganabot.dashboard.AdminApi;
import io.siggi.morganabot.dashboard.ApiRouter;
import io.siggi.morganabot.dashboard.DashboardApi;
import io.siggi.morganabot.dashboard.JdaGuildData;
import io.siggi.morganabot.dashboard.DashboardSession;
import io.siggi.morganabot.dashboard.GuildData;
import io.siggi.morganabot.dashboard.GuildResolver;
import io.siggi.morganabot.dashboard.LoginEndpoints;
import io.siggi.morganabot.dashboard.StaticFiles;
import io.siggi.morganabot.health.Component;
import io.siggi.morganabot.health.Health;
import io.siggi.morganabot.health.State;
import io.siggi.morganabot.health.SystemHealth;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;
import io.siggi.http.session.Sessions;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class WebHandler {
    private final MorganaBot bot;
    private Configuration configuration;

    public WebHandler(MorganaBot bot) {
        this.bot = bot;
        this.configuration = bot.getConfiguration();
    }

    private boolean started = false;
    private volatile boolean stopped = false;
    private ServerSocket serverSocket;
    private Thread thread;
    private HTTPServer httpServer;
    private ApiRouter api;

    public HTTPServer getHttpServer() {
        return httpServer;
    }

    /**
     * The dashboard API ({@code /dashboard/api/…}); endpoints are added with {@code get/post/put/patch/delete}.
     */
    public ApiRouter getApi() {
        return api;
    }

    public void start() throws Exception {
        if (started) return;
        started = true;
        serverSocket = new ServerSocket(configuration.apiPort);
        // Dashboard sessions: in memory (a restart logs everyone out), 24 hours since last use.
        httpServer = new HTTPServerBuilder().setSessions(Sessions.create(DashboardSession.SESSION_TTL)).build();
        // SiggiHTTP picks the first matching registration, so the most specific paths go first:
        // login and logout, then the API router (/dashboard/api/…), then static files (/dashboard/…).
        new LoginEndpoints(bot.getLoginTokens()).register(httpServer);
        api = new ApiRouter(bot.getAccess(), GuildResolver.jda(bot::getJDA), System::currentTimeMillis);
        GuildData.Provider guilds = JdaGuildData.provider(bot::getJDA, bot::getDiagnose);
        new DashboardApi(bot.getServerInfoStore(), bot.getServerSettings(), bot.getAccess(), bot.getAuditLog(),
                bot.getServerProblems(), guilds, this::twitchUsers, this::twitchTrouble).register(api);
        new AdminApi(bot.getServerInfoStore(), bot.getServerSettings(), bot.getServerProblems(), guilds,
                bot::getSystemHealth, bot.getAuditLog()).register(api);
        api.register(httpServer);
        new StaticFiles(WebHandler.class.getClassLoader(), "web").register(httpServer);
        (thread = new Thread(() -> {
            try {
                while (true) {
                    Socket accept = serverSocket.accept();
                    httpServer.handle(accept);
                }
            } catch (Exception e) {
                if (!stopped) {
                    Health.unexpected("Web server stopped accepting connections", e);
                }
            }
        }, "Web server")).start();
    }

    /**
     * Twitch users by ID for the dashboard, looked up in batches of 100.
     */
    private Map<String, ServerSettings.TwitchUser> twitchUsers(Collection<String> ids) throws IOException {
        Map<String, ServerSettings.TwitchUser> users = new HashMap<>();
        TwitchStreamerWatcher watcher = bot.getTwitchStreamerWatcher();
        if (watcher == null || ids.isEmpty()) return users;
        for (Map.Entry<String, JsonObject> entry : watcher.getMultipleStreamerInfosByIds(ids).entrySet()) {
            JsonObject user = entry.getValue();
            users.put(entry.getKey(), new ServerSettings.TwitchUser(entry.getKey(),
                    user.get("login").getAsString(), user.get("display_name").getAsString()));
        }
        return users;
    }

    /**
     * Whether Twitch is failing for the whole bot (the Twitch API or EventSub card is red). Servers only see a yes/no.
     */
    private boolean twitchTrouble() {
        SystemHealth health = bot.getSystemHealth();
        if (health == null) return false;
        return health.status(Component.TWITCH_API).state() == State.RED
                || health.status(Component.EVENTSUB).state() == State.RED;
    }

    public void stop() {
        stopped = true;
        try {
            serverSocket.close();
        } catch (Exception ignored) {
        }
    }
}

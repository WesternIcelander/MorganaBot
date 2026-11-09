package io.siggi.morganabot;

import io.siggi.morganabot.config.Configuration;
import io.siggi.morganabot.dashboard.ApiRouter;
import io.siggi.morganabot.dashboard.DashboardSession;
import io.siggi.morganabot.dashboard.GuildResolver;
import io.siggi.morganabot.dashboard.LoginEndpoints;
import io.siggi.morganabot.dashboard.StaticFiles;
import io.siggi.morganabot.health.Health;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;
import io.siggi.http.session.Sessions;

import java.net.ServerSocket;
import java.net.Socket;

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

    public void stop() {
        stopped = true;
        try {
            serverSocket.close();
        } catch (Exception ignored) {
        }
    }
}

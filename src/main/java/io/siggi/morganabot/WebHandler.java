package io.siggi.morganabot;

import io.siggi.morganabot.config.Configuration;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;

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
    private ServerSocket serverSocket;
    private Thread thread;
    private HTTPServer httpServer;

    public HTTPServer getHttpServer() {
        return httpServer;
    }

    public void start() throws Exception {
        if (started) return;
        started = true;
        serverSocket = new ServerSocket(configuration.apiPort);
        httpServer = new HTTPServerBuilder().build();
        (thread = new Thread(() -> {
            try {
                while (true) {
                    Socket accept = serverSocket.accept();
                    httpServer.handle(accept);
                }
            } catch (Exception e) {
            }
        })).start();
    }

    public void stop() {
        try {
            serverSocket.close();
        } catch (Exception ignored) {
        }
    }
}

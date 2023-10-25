package io.siggi.morganabot.config;

public class Configuration extends Data {
    public String apiEndpoint;
    public int apiPort;

    public Discord discord;

    public static class Discord {
        public String token;
    }

    public Twitch twitch;

    public static class Twitch {
        public String clientId;
        public String clientSecret;
        public String notificationSecret;
    }
}

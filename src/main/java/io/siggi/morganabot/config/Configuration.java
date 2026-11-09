package io.siggi.morganabot.config;

import java.util.ArrayList;
import java.util.List;

public class Configuration extends Data {
    public String apiEndpoint;
    public int apiPort;

    /** Discord user IDs of the bot's super admins (system alerts, and later the super admin dashboard). Optional. */
    public List<Long> superAdmins = new ArrayList<>();

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

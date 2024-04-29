package io.siggi.morganabot;

import com.google.gson.Gson;
import io.siggi.morganabot.config.AuthCache;
import io.siggi.morganabot.config.Configuration;
import io.siggi.morganabot.config.Data;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.UserData;
import io.siggi.morganabot.util.Util;
import io.siggi.processapi.ProcessAPI;
import io.siggi.processapi.Signal;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.io.File;
import java.util.Locale;

public class MorganaBot {
    private static int termCount = 0;

    public static void main(String[] args) throws Exception {
        MorganaBot bot;
        (bot = new MorganaBot()).start();
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
            // ProcessAPI only works on macOS and Linux, not Windows!
            ProcessAPI.catchSignal(Signal.SIGTERM);
            ProcessAPI.catchSignal(Signal.SIGINT);
            ProcessAPI.addSignalListener((signal) -> {
                if (signal == Signal.SIGTERM || signal == Signal.SIGINT) {
                    if (termCount == 0) {
                        termCount += 1;
                        bot.stop();
                    } else if (termCount > 0) {
                        System.exit(1);
                    }
                }
            });
        }
    }

    private boolean started = false;
    private Gson gson;
    private JDA jda;
    private WebHandler webHandler;
    private TwitchStreamerWatcher twitchStreamerWatcher;
    private Configuration configuration;
    private AuthCache authCache;

    public Gson getGson() {
        return gson;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public AuthCache getAuthCache() {
        return authCache;
    }

    public ServerInfo getServerInfo(long serverId) {
        return Data.read(new File(Util.getDataRoot(), "servers/" + serverId + ".json"), ServerInfo.class, gson);
    }

    public UserData getUserData(long userId) {
        return Data.read(new File(Util.getDataRoot(), "users/" + userId + ".json"), UserData.class, gson);
    }

    private void start() throws Exception {
        if (started) return;
        started = true;
        gson = new Gson();
        configuration = Data.read(new File(Util.getDataRoot(), "config.json"), Configuration.class, gson);
        authCache = Data.read(new File(Util.getDataRoot(), "auth-cache.json"), AuthCache.class, gson);
        webHandler = new WebHandler(this);
        webHandler.start();
        JDABuilder builder = JDABuilder.createDefault(configuration.discord.token);
        jda = builder.build();
        jda.awaitReady();
        jda.updateCommands().addCommands(
                Commands.slash("timezone", "Set your time zone used by the /time command!")
                        .addOption(OptionType.STRING, "timezone", "What's your timezone? Format is Continent/City, select your nearest city!", true, true),
                Commands.slash("time", "Generate a time code that shows time in everyone's local timezone!")
                        .addOption(OptionType.STRING, "time", "What time?", true)
                        .addOption(OptionType.STRING, "date", "What date? Year/Month/Day. If you omit year/month, the closest matching future date will be used.", false),
                Commands.slash("letmein", "Add yourself to the whitelist on our Minecraft server!")
                        .addOption(OptionType.STRING, "username", "What's your username on Minecraft?", true)
                        .addOption(OptionType.BOOLEAN, "cracked", "If you didn't buy Minecraft, set this to True", false)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("setstreamerrole", "Set the role to give to those currently streaming")
                        .addOption(OptionType.ROLE, "role", "Which role is the live role?", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("setlivechannel", "Set the channel to post live notifications in")
                        .addOption(OptionType.CHANNEL, "channel", "Which channel do we post live notifications in?", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("addstreamer", "Add a streamer")
                        .addOption(OptionType.STRING, "twitch-name", "What's their name on Twitch?", true)
                        .addOption(OptionType.USER, "discord-name", "What's their name on Discord?", false)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("deletestreamer", "Delete a streamer")
                        .addOption(OptionType.STRING, "twitch-name", "What's their name on Twitch?", false)
                        .addOption(OptionType.USER, "discord-name", "What's their name on Discord?", false)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("streamerlist", "Get a list of streamers in this Discord!")
        ).queue();
        jda.addEventListener(new Listener(this));
        twitchStreamerWatcher = new TwitchStreamerWatcher(this);
        twitchStreamerWatcher.start();
    }

    private void stop() {
        if (webHandler != null) {
            try {
                webHandler.stop();
                webHandler = null;
            } catch (Exception ignored) {
            }
        }
        if (jda != null) {
            try {
                jda.shutdown();
                jda = null;
            } catch (Exception ignored) {
            }
        }
        if (twitchStreamerWatcher != null) {
            try {
                twitchStreamerWatcher.stop();
                twitchStreamerWatcher = null;
            } catch (Exception ignored) {
            }
        }
    }

    public JDA getJDA() {
        return jda;
    }

    public TwitchStreamerWatcher getTwitchStreamerWatcher() {
        return twitchStreamerWatcher;
    }

    public WebHandler getWebHandler() {
        return webHandler;
    }
}

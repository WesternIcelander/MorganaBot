package io.siggi.morganabot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.siggi.morganabot.audit.AuditLog;
import io.siggi.morganabot.config.AuthCache;
import io.siggi.morganabot.config.Configuration;
import io.siggi.morganabot.config.Data;
import io.siggi.morganabot.config.ServerInfoStore;
import io.siggi.morganabot.problems.ServerProblems;
import io.siggi.morganabot.config.UserData;
import io.siggi.morganabot.util.Util;
import io.siggi.processapi.ProcessAPI;
import io.siggi.processapi.Signal;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.io.File;
import java.io.IOException;
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
    private ServerInfoStore serverInfoStore;
    private ServerSettings serverSettings;
    private AuditLog auditLog;
    private ServerProblems serverProblems;

    public Gson getGson() {
        return gson;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public AuthCache getAuthCache() {
        return authCache;
    }

    public ServerInfoStore getServerInfoStore() {
        return serverInfoStore;
    }

    public ServerSettings getServerSettings() {
        return serverSettings;
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }

    public ServerProblems getServerProblems() {
        return serverProblems;
    }

    /**
     * Names for audit entries, from the JDA cache (users fall back to a REST lookup) and the Twitch API.
     */
    private final class DiscordNames implements AuditLog.Names {
        @Override
        public String channel(long guildId, long channelId) {
            Guild guild = guild(guildId);
            GuildChannel channel = guild == null ? null : guild.getGuildChannelById(channelId);
            return channel == null ? null : channel.getName();
        }

        @Override
        public String role(long guildId, long roleId) {
            Guild guild = guild(guildId);
            Role role = guild == null ? null : guild.getRoleById(roleId);
            return role == null ? null : role.getName();
        }

        @Override
        public String user(long guildId, long userId) {
            if (jda == null) return null;
            User user = jda.getUserById(userId);
            if (user == null) {
                Guild guild = guild(guildId);
                Member member = guild == null ? null : guild.getMemberById(userId);
                if (member != null) user = member.getUser();
            }
            if (user == null) {
                try {
                    user = jda.retrieveUserById(userId).complete();
                } catch (Exception e) {
                    return null;
                }
            }
            return user == null ? null : user.getName();
        }

        @Override
        public String twitchLogin(String twitchId) {
            if (twitchStreamerWatcher == null) return null;
            try {
                JsonObject user = twitchStreamerWatcher.getStreamerInfoById(twitchId);
                return user == null ? null : user.get("login").getAsString();
            } catch (Exception e) {
                return null;
            }
        }

        private Guild guild(long guildId) {
            return jda == null ? null : jda.getGuildById(guildId);
        }
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
        serverInfoStore = new ServerInfoStore(new File(Util.getDataRoot(), "servers"), gson);
        auditLog = new AuditLog(new File(Util.getDataRoot(), "servers"), gson, serverInfoStore, new DiscordNames());
        serverProblems = new ServerProblems(new File(Util.getDataRoot(), "servers"), gson, serverInfoStore);
        serverSettings = new ServerSettings(serverInfoStore, new ServerSettings.Twitch() {
            @Override
            public ServerSettings.TwitchUser findUserByLogin(String login) throws IOException {
                JsonObject user = twitchStreamerWatcher.getStreamerInfoByUsername(login);
                if (user == null) return null;
                return new ServerSettings.TwitchUser(
                        user.get("id").getAsString(),
                        user.get("login").getAsString(),
                        user.get("display_name").getAsString()
                );
            }

            @Override
            public void triggerUpdate() {
                twitchStreamerWatcher.triggerUpdate();
            }
        }, auditLog);
        webHandler = new WebHandler(this);
        webHandler.start();
        JDABuilder builder = JDABuilder.createDefault(configuration.discord.token);
        jda = builder.build();
        jda.awaitReady();
        jda.updateCommands().addCommands(
                Commands.slash("timezone", "Set your time zone used by the /time command!")
                        .addOption(OptionType.STRING, "timezone", "What's your timezone? Format is Continent/City, select your nearest city!", true, true)
                        .setIntegrationTypes(IntegrationType.GUILD_INSTALL, IntegrationType.USER_INSTALL)
                        .setContexts(InteractionContextType.GUILD, InteractionContextType.PRIVATE_CHANNEL),
                Commands.slash("time", "Generate a time code that shows time in everyone's local timezone!")
                        .addOption(OptionType.STRING, "time", "What time? (Optional date in year/month/day first, then time)", true)
                        .addOption(OptionType.STRING, "timezone", "Which timezone?", false, true)
                        .setIntegrationTypes(IntegrationType.GUILD_INSTALL, IntegrationType.USER_INSTALL)
                        .setContexts(InteractionContextType.GUILD, InteractionContextType.PRIVATE_CHANNEL),
                Commands.slash("setstreamerrole", "Set the role to give to those currently streaming")
                        .addOption(OptionType.ROLE, "role", "Which role is the live role?", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("setlivechannel", "Set the channel to post live notifications in")
                        .addOption(OptionType.CHANNEL, "channel", "Which channel do we post live notifications in?", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("addstreamer", "Add a streamer")
                        .addOption(OptionType.STRING, "twitch-name", "What's their name on Twitch?", true)
                        .addOption(OptionType.USER, "discord-name", "What's their name on Discord?", false)
                        .addOption(OptionType.CHANNEL, "post-in-channel", "Should we post in a channel other than the default? Leave blank for default", false)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("deletestreamer", "Delete a streamer")
                        .addOption(OptionType.STRING, "twitch-name", "What's their name on Twitch?", false)
                        .addOption(OptionType.USER, "discord-name", "What's their name on Discord?", false)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("setdeleteonoffline", "Set whether or not to delete notifications when a streamer goes offline")
                        .addOption(OptionType.BOOLEAN, "delete", "Should we delete notifications when a streamer goes offline?", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("setlivemessage", "Set the message to post when a streamer goes live")
                        .addOption(OptionType.STRING, "message", "What message should we post when a streamer goes live? (enter \"help\" to see available variables)", true)
                        .addOption(OptionType.USER, "discord-name", "Override for a specific user?", false)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("setchanneloverride", "Set a channel override for a streamer")
                        .addOption(OptionType.STRING, "twitch-name", "What's their name on Twitch?", false)
                        .addOption(OptionType.USER, "discord-name", "What's their name on Discord?", false)
                        .addOption(OptionType.CHANNEL, "channel", "What channel should we post in? Leave blank to remove the override", false)
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

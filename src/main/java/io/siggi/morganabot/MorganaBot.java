package io.siggi.morganabot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.siggi.morganabot.audit.AuditLog;
import io.siggi.morganabot.diagnose.Diagnose;
import io.siggi.morganabot.config.AuthCache;
import io.siggi.morganabot.config.Configuration;
import io.siggi.morganabot.config.Data;
import io.siggi.morganabot.config.ServerInfoStore;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.UserData;
import io.siggi.morganabot.health.Component;
import io.siggi.morganabot.health.ComponentStatus;
import io.siggi.morganabot.health.DiscordHealthListener;
import io.siggi.morganabot.health.Health;
import io.siggi.morganabot.health.Severity;
import io.siggi.morganabot.health.State;
import io.siggi.morganabot.health.SystemHealth;
import io.siggi.morganabot.health.SystemLog;
import io.siggi.morganabot.problems.ServerProblems;
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
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

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

    private static final long DISCORD_MIN_BACKOFF = 5_000L;
    private static final long DISCORD_MAX_BACKOFF = 5L * 60L * 1000L;

    private boolean started = false;
    private volatile boolean stopping = false;
    private long startTime;
    private Gson gson;
    private volatile JDA jda;
    private final CountDownLatch discordReady = new CountDownLatch(1);
    private Thread discordConnectThread;
    private DiscordHealthListener discordHealthListener;
    private SystemHealth systemHealth;
    private WebHandler webHandler;
    private TwitchStreamerWatcher twitchStreamerWatcher;
    private Configuration configuration;
    private AuthCache authCache;
    private ServerInfoStore serverInfoStore;
    private ServerSettings serverSettings;
    private AuditLog auditLog;
    private ServerProblems serverProblems;
    private Diagnose diagnose;

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

    /**
     * Who may use which parts of a server's dashboard; checked on every request.
     */
    public Access getAccess() {
        return serverSettings.getAccess();
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }

    public ServerProblems getServerProblems() {
        return serverProblems;
    }

    public SystemHealth getSystemHealth() {
        return systemHealth;
    }

    public Diagnose getDiagnose() {
        return diagnose;
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
        startTime = System.currentTimeMillis();
        gson = new Gson();
        systemHealth = new SystemHealth(new SystemLog(new File(Util.getDataRoot(), "system-log.jsonl"), gson));
        Health.install(systemHealth);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            System.err.println("Uncaught exception in thread " + thread.getName());
            Health.log(Component.BOT, Severity.ERROR, "Uncaught exception: " + Health.describe(error), error);
        });
        systemHealth.setAlerter(this::sendAlert);
        systemHealth.setGeneralInfo(this::generalInfo);
        systemHealth.start();
        configuration = Data.read(new File(Util.getDataRoot(), "config.json"), Configuration.class, gson);
        if (configuration.superAdmins == null) configuration.superAdmins = new ArrayList<>();
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
                TwitchStreamerWatcher watcher = twitchStreamerWatcher;
                if (watcher != null) watcher.triggerUpdate();
            }
        }, auditLog);
        serverSettings.setMembers(SelfServiceListener.members(this::getJDA));
        serverSettings.setAccess(new Access(serverInfoStore, configuration.superAdmins));
        diagnose = new Diagnose(this);
        // The web server and the Twitch watcher don't need Discord to start, and the system status must stay
        // reachable when Discord is down. The watcher's guild loops wait for Discord themselves.
        webHandler = new WebHandler(this);
        webHandler.start();
        twitchStreamerWatcher = new TwitchStreamerWatcher(this);
        twitchStreamerWatcher.start();
        discordHealthListener = new DiscordHealthListener(systemHealth, () -> stopping);
        Thread connector = new Thread(this::connectDiscord, "Discord connect");
        discordConnectThread = connector;
        connector.start();
    }

    /**
     * Connects to Discord, retrying temporary failures with backoff. An invalid token or disallowed intents can only
     * be fixed by editing the config or the developer portal and restarting, so those aren't retried.
     */
    private void connectDiscord() {
        long backoff = DISCORD_MIN_BACKOFF;
        String token = configuration.discord == null ? null : configuration.discord.token;
        if (token == null || token.isBlank()) {
            systemHealth.problem(Component.DISCORD, State.RED, "No Discord bot token in config.json (discord.token). Set it and restart.", null);
            return;
        }
        while (!stopping) {
            discordHealthListener.reset();
            JDA attempt = null;
            try {
                systemHealth.setState(Component.DISCORD, State.AMBER, "Connecting",
                        DiscordHealthListener.LONG_DISCONNECT, "Could not connect to Discord for more than 5 minutes");
                // Self-service follows members' roles as they change, which needs the members intent and every
                // member cached (JDA only fires role events for cached members). Fine for a bot in < 100 servers.
                attempt = JDABuilder.createDefault(token)
                        .enableIntents(GatewayIntent.GUILD_MEMBERS)
                        .setMemberCachePolicy(MemberCachePolicy.ALL)
                        .setChunkingFilter(ChunkingFilter.ALL)
                        .addEventListeners(discordHealthListener, new Listener(this), new SelfServiceListener(this))
                        .build();
                attempt.awaitReady();
                if (stopping) {
                    attempt.shutdown();
                    return;
                }
                jda = attempt;
                registerCommands(attempt);
                discordReady.countDown();
                return;
            } catch (InvalidTokenException e) {
                systemHealth.problem(Component.DISCORD, State.RED,
                        "Discord rejected the bot token. Fix discord.token in config.json and restart.", null);
                shutdownQuietly(attempt);
                return;
            } catch (InterruptedException e) {
                shutdownQuietly(attempt);
                return;
            } catch (Exception e) {
                shutdownQuietly(attempt);
                if (stopping) return;
                if (discordHealthListener.fatalCloseCode() != null) {
                    // Already shown as red with a "fix and restart" message.
                    return;
                }
                systemHealth.problem(Component.DISCORD, State.AMBER, "Could not connect to Discord: " + Health.describe(e),
                        e, DiscordHealthListener.LONG_DISCONNECT, "Could not connect to Discord for more than 5 minutes");
                systemHealth.detail(Component.DISCORD, "nextConnectAttempt", System.currentTimeMillis() + backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = Math.min(backoff * 2L, DISCORD_MAX_BACKOFF);
            }
        }
    }

    private static void shutdownQuietly(JDA attempt) {
        if (attempt == null) return;
        try {
            attempt.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    private void registerCommands(JDA jda) {
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
                        .addOption(OptionType.BOOLEAN, "role-based", "Self-added streamers: go back to the channel from their role (don't combine with channel)", false)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("settwitchchannel", "Add yourself as a streamer, or change your Twitch channel")
                        .addOption(OptionType.STRING, "twitch-name", "What's your name on Twitch?", true)
                        .setContexts(InteractionContextType.GUILD),
                Commands.slash("removetwitchchannel", "Remove yourself as a streamer")
                        .setContexts(InteractionContextType.GUILD),
                Commands.slash("addselfservicerole", "Let members with a role add themselves as streamers")
                        .addOption(OptionType.ROLE, "role", "Which role may add themselves?", true)
                        .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Where their live posts go. Leave blank for the default live channel", false)
                                .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS))
                        .setContexts(InteractionContextType.GUILD)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("removeselfservicerole", "Stop letting members with a role add themselves as streamers")
                        .addOption(OptionType.ROLE, "role", "Which role?", true)
                        .setContexts(InteractionContextType.GUILD)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("setselfserviceexpiry", "Remove self-added streamers after this many days without a self-service role")
                        .addOptions(new OptionData(OptionType.INTEGER, "days", "How many days? 0 means never", true)
                                .setRequiredRange(0, ServerSettings.MAX_REMOVE_AFTER_DAYS))
                        .setContexts(InteractionContextType.GUILD)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("addadminrole", "Give members with a role full access to this server's MorganaBot dashboard")
                        .addOption(OptionType.ROLE, "role", "Which role?", true)
                        .setContexts(InteractionContextType.GUILD)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("removeadminrole", "Stop giving members with a role full dashboard access")
                        .addOption(OptionType.ROLE, "role", "Which role?", true)
                        .setContexts(InteractionContextType.GUILD)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("addmessagerole", "Let streamers with a role write their own live message")
                        .addOption(OptionType.ROLE, "role", "Which role?", true)
                        .addOption(OptionType.BOOLEAN, "allow-everyone", "May they use @everyone and @here? (default: no)", false)
                        .addOption(OptionType.STRING, "mention-roles", "Roles they may mention, e.g. @Viewers @Fans (default: none)", false)
                        .setContexts(InteractionContextType.GUILD)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("removemessagerole", "Stop letting streamers with a role write their own live message")
                        .addOption(OptionType.ROLE, "role", "Which role?", true)
                        .setContexts(InteractionContextType.GUILD)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("streamerlist", "Get a list of streamers in this Discord!")
        ).queue(null, error -> Health.log(Component.DISCORD, Severity.ERROR,
                "Could not register slash commands: " + Health.describe(error), error));
    }

    /**
     * Waits until Discord is connected and ready (possibly forever, if it never connects).
     */
    public void awaitDiscordReady() throws InterruptedException {
        discordReady.await();
    }

    public boolean isDiscordReady() {
        return discordReady.getCount() == 0L;
    }

    /**
     * DMs every super admin about a component turning red or recovering. Always printed to stdout too, and only
     * printed while Discord itself is red.
     */
    private void sendAlert(Component component, ComponentStatus status, boolean recovered) {
        String text = "**MorganaBot system alert:** " + component.label() + (recovered ? " recovered" : " is RED")
                + ": " + status.message();
        System.out.println("[ALERT] " + text);
        JDA current = jda;
        if (current == null || systemHealth.status(Component.DISCORD).state() == State.RED) return;
        for (Long userId : configuration.superAdmins) {
            if (userId == null || userId == 0L) continue;
            current.openPrivateChannelById(userId)
                    .flatMap(channel -> channel.sendMessage(text))
                    .queue(null, error -> systemHealth.log(Component.DISCORD, Severity.WARNING,
                            "Could not DM a system alert to super admin " + userId + ": " + Health.describe(error), null));
        }
    }

    private Map<String, Object> generalInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        info.put("startedAt", startTime);
        info.put("uptimeMillis", now - startTime);
        info.put("version", BuildInfo.version());
        info.put("buildTime", BuildInfo.buildTime());
        JDA current = jda;
        if (current != null) {
            List<Guild> guilds = current.getGuilds();
            info.put("servers", guilds.size());
            Set<String> streamers = new HashSet<>();
            for (Guild guild : guilds) {
                try {
                    for (ServerInfo.Streamer streamer : serverInfoStore.get(guild.getIdLong()).streamers) {
                        if (streamer.twitchId != null) streamers.add(streamer.twitchId);
                    }
                } catch (Exception ignored) {
                    // Unreadable file; reported elsewhere.
                }
            }
            info.put("streamersTracked", streamers.size());
        }
        return info;
    }

    private void stop() {
        stopping = true;
        Thread connector = discordConnectThread;
        if (connector != null) connector.interrupt();
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
        if (systemHealth != null) systemHealth.stop();
    }

    /**
     * The Discord connection, or null while Discord is unavailable (not connected yet, or failed to start).
     */
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

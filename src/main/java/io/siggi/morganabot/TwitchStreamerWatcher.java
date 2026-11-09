package io.siggi.morganabot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.morganabot.config.Configuration;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;
import io.siggi.morganabot.health.Component;
import io.siggi.morganabot.health.Health;
import io.siggi.morganabot.health.Severity;
import io.siggi.morganabot.health.State;
import io.siggi.morganabot.health.SystemHealth;
import io.siggi.morganabot.problems.FailureReasons;
import io.siggi.morganabot.problems.Problem;
import io.siggi.morganabot.problems.ServerProblems;
import io.siggi.morganabot.twitch.StreamerQueue;
import io.siggi.morganabot.twitch.SubscriptionNotes;
import io.siggi.morganabot.twitch.TwitchErrors;
import io.siggi.morganabot.twitch.TwitchHttpException;
import io.siggi.morganabot.twitch.TwitchTokenManager;
import io.siggi.morganabot.util.EndpointCaller;
import io.siggi.morganabot.util.MapBuilder;
import io.siggi.morganabot.util.Util;
import io.siggi.http.HTTPRequest;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.siggi.morganabot.util.Util.urlEncode;

public class TwitchStreamerWatcher {
    /** How long a notification waits for the same streamer's earlier Discord actions to finish. */
    static final long IN_FLIGHT_WAIT = 30_000L;
    static final long SYNC_INTERVAL = 60L * 60L * 1000L;
    static final long MIN_SYNC_RETRY = 60L * 1000L;
    /** A subscription sync failing for this long turns the EventSub card red. */
    static final long SYNC_FAILING_ESCALATE = 3L * 60L * 60L * 1000L;

    public TwitchStreamerWatcher(MorganaBot bot) {
        this.bot = bot;
    }

    private boolean started = false;
    private volatile boolean stopped = false;
    private final MorganaBot bot;
    private Configuration configuration;
    private SystemHealth health;
    private TwitchTokenManager tokens;
    private EndpointCaller noTokenEndpointCaller;
    private EndpointCaller endpointCaller;
    private volatile Thread thread;
    private ExecutorService notificationExecutor;
    private final StreamerQueue streamerQueue = new StreamerQueue();
    /** The token the current thread's last Helix request was sent with, so a 401 invalidates the right one. */
    private final ThreadLocal<String> tokenUsed = new ThreadLocal<>();
    private final AtomicLong signatureFailures = new AtomicLong();
    /** Set while Twitch rejects the client ID/secret; keeps the card from turning green on other successes. */
    private volatile String credentialsProblem;
    private final SubscriptionNotes subscriptionNotes = new SubscriptionNotes();

    /**
     * EventSub trouble per streamer (disabled by Twitch, or couldn't be created), for Diagnose.
     */
    public SubscriptionNotes getSubscriptionNotes() {
        return subscriptionNotes;
    }

    public void start() {
        if (started) return;
        started = true;
        configuration = bot.getConfiguration();
        health = bot.getSystemHealth();
        tokens = new TwitchTokenManager(bot.getAuthCache(), this::fetchToken, new TokenListener(), System::currentTimeMillis);
        health.detail(Component.TWITCH_API, "tokenExpiry", tokens.getExpiry() == 0L ? null : tokens.getExpiry());
        noTokenEndpointCaller = new EndpointCaller(null, bot.getGson());
        endpointCaller = new EndpointCaller((connection) -> {
            String token;
            try {
                token = tokens.getToken();
            } catch (IOException e) {
                throw new TokenUnavailableException(e);
            }
            tokenUsed.set(token);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Client-Id", configuration.twitch.clientId);
        }, bot.getGson());
        AtomicInteger threadCount = new AtomicInteger();
        notificationExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Twitch notification " + threadCount.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        bot.getWebHandler().getHttpServer().responderRegistry.register("/twitch-notification", this::handleNotification, false, true);
        Thread t = new Thread(this::runLoop, "Twitch watcher");
        thread = t;
        t.start();
    }

    public void stop() {
        stopped = true;
        // Keep the thread reference: triggerUpdate() checks `stopped` instead.
        Thread t = thread;
        if (t != null) {
            try {
                t.interrupt();
            } catch (Exception ignored) {
            }
        }
        if (notificationExecutor != null) notificationExecutor.shutdown();
    }

    // --- Twitch app token ---

    private TwitchTokenManager.Token fetchToken() throws IOException {
        HttpURLConnection urlc = noTokenEndpointCaller.post(
                "https://id.twitch.tv/oauth2/token",
                new MapBuilder<String, String>()
                        .put("client_id", configuration.twitch.clientId)
                        .put("client_secret", configuration.twitch.clientSecret)
                        .put("grant_type", "client_credentials")
                        .build()
        );
        int code = urlc.getResponseCode();
        if (code / 100 != 2) {
            String message = TwitchErrors.messageFromBody(readError(urlc));
            throw new TwitchHttpException(code, message, TwitchErrors.describeTokenFailure(code, message));
        }
        JsonObject object = Util.parseJson(urlc).getAsJsonObject();
        String token = object.get("access_token").getAsString();
        long expiresIn = object.get("expires_in").getAsLong();
        return new TwitchTokenManager.Token(token, expiresIn * 1000L);
    }

    private final class TokenListener implements TwitchTokenManager.Listener {
        @Override
        public void refreshed(long expiry) {
            credentialsProblem = null;
            health.detail(Component.TWITCH_API, "tokenExpiry", expiry);
            health.setState(Component.TWITCH_API, State.GREEN, "App token refreshed");
        }

        @Override
        public void refreshFailed(IOException error, boolean keptOld, long oldExpiry) {
            String message;
            if (error instanceof TwitchHttpException e) {
                message = e.getMessage();
                if (TwitchErrors.credentialsRejected(e.getResponseCode())) credentialsProblem = message;
            } else {
                message = "Could not reach id.twitch.tv to get an app token: " + Health.describe(error);
            }
            Throwable trace = error instanceof TwitchHttpException ? null : error;
            if (keptOld) {
                health.problem(Component.TWITCH_API, State.AMBER, message + " Still using the current token"
                        + (oldExpiry == 0L ? "." : " (expires " + Instant.ofEpochMilli(oldExpiry) + ")."), trace);
            } else {
                health.problem(Component.TWITCH_API, State.RED, message, trace);
            }
        }
    }

    /** The app token couldn't be fetched; already reported by the token listener. */
    private static class TokenUnavailableException extends IOException {
        TokenUnavailableException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    // --- Helix calls ---

    @FunctionalInterface
    private interface Request {
        HttpURLConnection open() throws IOException;
    }

    /**
     * Makes a Helix request and records the outcome on the Twitch API card. A 401 invalidates the token and retries
     * once with a new one.
     *
     * @param what what's being done, for messages ("list EventSub subscriptions")
     * @return the connection, with a 2xx response
     * @throws TwitchHttpException for any other response
     */
    private HttpURLConnection call(String what, Request request) throws IOException {
        for (int attempt = 0; ; attempt++) {
            tokenUsed.remove();
            HttpURLConnection connection;
            int code;
            try {
                connection = request.open();
                code = connection.getResponseCode();
            } catch (TokenUnavailableException e) {
                throw e;
            } catch (IOException e) {
                health.problem(Component.TWITCH_API, State.AMBER,
                        "Twitch API: could not " + what + " (network error: " + Health.describe(e) + ")", null);
                throw e;
            }
            if (code / 100 == 2) {
                health.detail(Component.TWITCH_API, "lastSuccess", System.currentTimeMillis());
                if (credentialsProblem == null) health.setState(Component.TWITCH_API, State.GREEN, "OK");
                return connection;
            }
            String twitchMessage = TwitchErrors.messageFromBody(readError(connection));
            if (code == 401 && attempt == 0) {
                tokens.invalidate(tokenUsed.get());
                health.log(Component.TWITCH_API, Severity.WARNING, "Twitch rejected the app token; getting a new one", null);
                continue;
            }
            String rateLimitReset = connection.getHeaderField("Ratelimit-Reset");
            if (code == 429 && rateLimitReset != null) health.detail(Component.TWITCH_API, "rateLimitReset", rateLimitReset);
            String description = TwitchErrors.describeApi(what, code, twitchMessage, rateLimitReset);
            if (code != 409) {
                // 409 (subscription already exists) isn't a problem with the API.
                health.problem(Component.TWITCH_API, TwitchErrors.apiState(code), description, null);
            }
            throw new TwitchHttpException(code, twitchMessage, description);
        }
    }

    private static String readError(HttpURLConnection connection) {
        try (InputStream in = connection.getErrorStream()) {
            if (in == null) return null;
            return new String(Util.readFully(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // --- EventSub notifications ---

    private final Map<String, Long> recentNotifications = new HashMap<>();

    private void handleNotification(HTTPRequest request) throws Exception {
        long now = System.currentTimeMillis();
        String messageType = request.getHeader("Twitch-Eventsub-Message-Type");
        if (messageType == null) return;
        byte[] requestData = Util.readFully(request.inStream);
        if (messageType.equalsIgnoreCase("webhook_callback_verification")) {
            JsonObject object = JsonParser.parseString(new String(requestData, StandardCharsets.UTF_8)).getAsJsonObject();
            byte[] challengeResponse = object.get("challenge").getAsString().getBytes(StandardCharsets.UTF_8);
            request.response.setContentType("text/plain");
            request.response.contentLength(challengeResponse.length);
            request.response.write(challengeResponse);
            return;
        }
        if (!verifySignature(request, requestData)) {
            long failures = signatureFailures.incrementAndGet();
            health.detail(Component.EVENTSUB, "signatureFailures", failures);
            health.detail(Component.EVENTSUB, "lastSignatureFailure", now);
            health.log(Component.EVENTSUB, Severity.WARNING, "Rejected a notification with a bad signature "
                    + "(wrong twitch.notificationSecret, or someone probing the endpoint)", null);
            request.response.setHeader("403 Forbidden");
            request.response.contentLength(0);
            request.response.write("");
            return;
        }
        request.response.write("");
        health.detail(Component.EVENTSUB, "lastNotification", now);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String theFuckingTimestamp = request.getHeader("Twitch-Eventsub-Message-Timestamp");
        theFuckingTimestamp = theFuckingTimestamp.substring(0, 19) + "Z";
        long messageTimestamp = sdf.parse(theFuckingTimestamp).getTime();
        if (messageTimestamp < System.currentTimeMillis() - 600000L) {
            // If the message is older than 10 minutes drop it.
            return;
        }
        synchronized (recentNotifications) {
            recentNotifications.entrySet().removeIf((e) -> {
                Long time = e.getValue();
                return time == null || now - time > 1200000L;
            });
            String messageId = request.getHeader("Twitch-Eventsub-Message-Id");
            Long time = recentNotifications.get(messageId);
            if (time != null) {
                // This is a duplicate notification, do not process it.
                return;
            }
            recentNotifications.put(messageId, now);
        }
        String streamerId;
        String streamerName;
        String streamerLogin;
        boolean goingOnline;
        try {
            JsonObject notificationObject = JsonParser.parseString(new String(requestData, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject subscription = notificationObject.get("subscription").getAsJsonObject();
            String eventType = subscription.get("type").getAsString();
            if (messageType.equalsIgnoreCase("revocation")) {
                String status = subscription.has("status") ? subscription.get("status").getAsString() : "?";
                String broadcaster = subscription.getAsJsonObject("condition").get("broadcaster_user_id").getAsString();
                health.log(Component.EVENTSUB, Severity.WARNING, "Twitch revoked the " + eventType
                        + " subscription for broadcaster " + broadcaster + " (" + status + ")", null);
                subscriptionNotes.disabled(broadcaster, eventType, status, System.currentTimeMillis());
                triggerUpdate();
                return;
            }
            if (!messageType.equalsIgnoreCase("notification")) return;
            JsonObject event = notificationObject.get("event").getAsJsonObject();
            String streamType = event.has("type") && !event.get("type").isJsonNull() ? event.get("type").getAsString() : null;
            streamerId = event.get("broadcaster_user_id").getAsString();
            streamerName = event.get("broadcaster_user_name").getAsString();
            // The login is what twitch.tv URLs use; the display name can contain characters that aren't valid there.
            streamerLogin = event.has("broadcaster_user_login") && !event.get("broadcaster_user_login").isJsonNull()
                    ? event.get("broadcaster_user_login").getAsString() : streamerName.toLowerCase(java.util.Locale.ROOT);
            goingOnline = eventType.equals("stream.online");
            if (goingOnline && !"live".equals(streamType)) return;
        } catch (Exception e) {
            health.log(Component.EVENTSUB, Severity.ERROR, "Could not read an EventSub notification: " + Health.describe(e), e);
            return;
        }
        try {
            notificationExecutor.execute(() -> processNotification(streamerId, streamerName, streamerLogin, goingOnline, now));
        } catch (Exception e) {
            // Only when stopping.
            System.err.println("Dropped notification for " + streamerName + ": " + e);
        }
    }

    private boolean verifySignature(HTTPRequest request, byte[] requestData) {
        try {
            String signature = request.getHeader("Twitch-Eventsub-Message-Signature");
            SecretKeySpec secretKeySpec = new SecretKeySpec(configuration.twitch.notificationSecret.getBytes(), "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKeySpec);
            mac.update(request.getHeader("Twitch-Eventsub-Message-Id").getBytes(StandardCharsets.UTF_8));
            mac.update(request.getHeader("Twitch-Eventsub-Message-Timestamp").getBytes(StandardCharsets.UTF_8));
            String expectedSignature = "sha256=" + Util.bytesToHex(mac.doFinal(requestData));
            return expectedSignature.equals(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Handles one online/offline event. Runs on the notification executor, one at a time per streamer, after the
     * streamer's earlier sends and deletes have finished.
     */
    private void processNotification(String streamerId, String streamerName, String streamerLogin, boolean goingOnline, long now) {
        String kind = goingOnline ? "online" : "offline";
        try {
            boolean waited = streamerQueue.run(streamerId, IN_FLIGHT_WAIT,
                    () -> processNotificationLocked(streamerId, streamerName, streamerLogin, goingOnline, now));
            if (!waited) {
                health.log(Component.DISCORD, Severity.WARNING, "Timed out waiting for an earlier Discord action for "
                        + streamerName + " before handling the " + kind + " notification", null);
            }
        } catch (Exception e) {
            health.log(Component.EVENTSUB, Severity.ERROR, "Could not handle the " + kind + " notification for "
                    + streamerName + ": " + Health.describe(e), e);
        }
    }

    private void processNotificationLocked(String streamerId, String streamerName, String streamerLogin, boolean goingOnline, long now) throws IOException {
        JDA jda = bot.getJDA();
        if (jda == null) {
            health.log(Component.DISCORD, Severity.WARNING, "Dropped a Twitch " + (goingOnline ? "online" : "offline")
                    + " notification for " + streamerName + ": Discord unavailable", null);
            return;
        }
        List<Guild> guilds = jda.getGuilds();
        JsonObject channelInfo = null;
        if (goingOnline) {
            channelInfo = getChannelInformation(streamerId);
        }
        ServerInfoStore store = bot.getServerInfoStore();
        for (Guild guild : guilds) {
            // Update the streaming state under the guild's lock, and take a snapshot to act on afterwards.
            // The cooldown check happens here, inside the streamer's queue, so two notifications can't both pass it.
            List<Boolean> postMessages = new ArrayList<>();
            ServerInfo serverInfo;
            try {
                serverInfo = store.modify(guild.getIdLong(), info -> {
                    for (ServerInfo.Streamer streamer : info.streamers) {
                        if (skip(streamer, streamerId, goingOnline)) continue;
                        if (streamerId.equals(streamer.twitchId)) {
                            boolean postMessage = true;
                            if (goingOnline) {
                                if (streamer.startedStreaming == 0L) {
                                    if (now - streamer.stoppedStreaming < info.streamNotificationCooldown) {
                                        postMessage = false;
                                    }
                                }
                                streamer.startedStreaming = now;
                            } else {
                                if (streamer.startedStreaming != 0L) {
                                    streamer.startedStreaming = 0L;
                                    streamer.stoppedStreaming = now;
                                }
                            }
                            postMessages.add(postMessage);
                        }
                    }
                    return postMessages.isEmpty() ? null : store.copy(info);
                });
            } catch (Exception e) {
                // One guild's unreadable file mustn't stop notifications for the others.
                health.log(Component.STORAGE, Severity.ERROR, "Could not update the server file for guild "
                        + guild.getIdLong() + ": " + Health.describe(e), e);
                continue;
            }
            if (serverInfo == null) continue;
            int i = 0;
            for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                if (skip(streamer, streamerId, goingOnline)) continue;
                if (streamerId.equals(streamer.twitchId)) {
                    handleLiveNotification(jda, guild, serverInfo, streamer, streamerId, streamerName, streamerLogin, channelInfo, goingOnline, postMessages.get(i++));
                }
            }
        }
    }

    /**
     * Self-added streamers who lost their self-service role are paused: their go-live is ignored (no post, no live
     * role, not recorded as a problem). Offline is still handled, so a post from before the pause is cleaned up.
     */
    static boolean skip(ServerInfo.Streamer streamer, String streamerId, boolean goingOnline) {
        return goingOnline && streamerId.equals(streamer.twitchId) && streamer.isSelfServicePaused();
    }

    private void handleLiveNotification(JDA jda, Guild guild, ServerInfo serverInfo, ServerInfo.Streamer streamer, String streamerId, String streamerName, String streamerLogin, JsonObject channelInfo, boolean goingOnline, boolean postMessage) {
        long guildId = guild.getIdLong();
        Problem.Target streamerTarget = Problem.Target.streamer(streamerId, streamerName);

        // The live role only applies to streamers linked to a Discord user.
        boolean useLiveRole = serverInfo.roleForLiveUsers != 0L && streamer.discordId != 0L;
        Role liveRole = useLiveRole ? guild.getRoleById(serverInfo.roleForLiveUsers) : null;

        long channelIdToPostIn = serverInfo.channelToPostLiveNotifications;
        if (streamer.channelToPostLiveNotifications != 0L) {
            channelIdToPostIn = streamer.channelToPostLiveNotifications;
        }
        // Text and announcement channels both work.
        StandardGuildMessageChannel channel = null;
        String channelProblem = null;
        String channelName = null;
        if (channelIdToPostIn == 0L) {
            channelProblem = "No live channel is set";
        } else {
            GuildChannel channelToPostIn = guild.getGuildChannelById(channelIdToPostIn);
            if (channelToPostIn == null) {
                channelProblem = "Unknown channel (deleted?)";
            } else {
                channelName = channelToPostIn.getName();
                if (channelToPostIn instanceof StandardGuildMessageChannel messageChannel) {
                    channel = messageChannel;
                } else {
                    channelProblem = "#" + channelName + " is not a text or announcement channel";
                }
            }
        }
        Problem.Target channelTarget = channelIdToPostIn == 0L ? null : Problem.Target.channel(channelIdToPostIn, channelName);

        if (useLiveRole) {
            String action = goingOnline ? Problem.ADD_LIVE_ROLE : Problem.REMOVE_LIVE_ROLE;
            if (liveRole == null) {
                recordProblem(guildId, new Problem(action, Problem.Target.role(serverInfo.roleForLiveUsers, null), streamerTarget,
                        "The live role was deleted"));
            } else {
                Problem.Target roleTarget = Problem.Target.role(liveRole.getIdLong(), liveRole.getName());
                Consumer<Throwable> onFailure = error -> recordProblem(guildId, new Problem(action, roleTarget, streamerTarget,
                        FailureReasons.describe(error, action, null, liveRole.getName())));
                try {
                    UserSnowflake user = UserSnowflake.fromId(streamer.discordId);
                    (goingOnline ? guild.addRoleToMember(user, liveRole) : guild.removeRoleFromMember(user, liveRole))
                            .queue(null, onFailure);
                } catch (Exception e) {
                    onFailure.accept(e);
                }
            }
        }

        if (goingOnline) {
            boolean silent = false;
            if (!postMessage && serverInfo.deleteNotificationsOnOffline) {
                silent = true;
                postMessage = true;
            }
            if (serverInfo.deleteNotificationsOnOffline && streamer.lastLiveNotificationId != 0L) {
                // Clean up a post left behind if Twitch didn't send us the offline notification.
                deleteLiveMessage(guildId, channel, channelTarget, channelName, channelProblem, streamerId, streamerTarget, streamer.lastLiveNotificationId);
            }
            if (postMessage) {
                if (channel == null) {
                    recordProblem(guildId, new Problem(Problem.SEND_LIVE_MESSAGE, channelTarget, streamerTarget, "Not posted: " + channelProblem));
                    return;
                }
                LiveMessage.Filled liveMessage = LiveMessage.build(LiveMessage.templateFor(serverInfo, streamer),
                        channelInfo.get("game_name").getAsString(), channelInfo.get("title").getAsString(),
                        streamerName, streamerLogin, streamer.discordId);
                String finalChannelName = channelName;
                // A later notification for this streamer (e.g. offline) waits until this send and the save of its
                // message ID are done, so it can delete the new post.
                CompletableFuture<Void> done = streamerQueue.track(streamerId);
                Consumer<Throwable> onFailure = error -> {
                    try {
                        recordProblem(guildId, new Problem(Problem.SEND_LIVE_MESSAGE, channelTarget, streamerTarget,
                                FailureReasons.describe(error, Problem.SEND_LIVE_MESSAGE, finalChannelName, null)));
                    } finally {
                        done.complete(null);
                    }
                };
                try {
                    sendLiveMessage(channel, liveMessage).setSuppressedNotifications(silent).queue(message -> {
                        // Save against the current data, not the snapshot taken before sending, so changes made
                        // in the meantime aren't reverted. If the streamer was removed meanwhile, do nothing.
                        try {
                            bot.getServerInfoStore().modify(guildId, info -> {
                                for (ServerInfo.Streamer current : info.streamers) {
                                    if (streamerId.equals(current.twitchId)) {
                                        current.lastLiveNotificationId = message.getIdLong();
                                        break;
                                    }
                                }
                                return null;
                            });
                        } catch (Exception e) {
                            health.log(Component.STORAGE, Severity.ERROR, "Could not save the live message ID for "
                                    + streamerName + " in guild " + guildId + ": " + Health.describe(e), e);
                        } finally {
                            done.complete(null);
                        }
                    }, onFailure);
                } catch (Exception e) {
                    onFailure.accept(e);
                }
            }
        } else {
            if (serverInfo.deleteNotificationsOnOffline && streamer.lastLiveNotificationId != 0L) {
                deleteLiveMessage(guildId, channel, channelTarget, channelName, channelProblem, streamerId, streamerTarget, streamer.lastLiveNotificationId);
            }
        }
    }

    /**
     * Deletes a previous live post. Once it's gone (deleted now, or already missing), the stored ID is cleared so
     * the next go-live doesn't try to delete it again.
     */
    /**
     * The live post, pinging exactly the mentions written in the template text (nothing from Twitch).
     */
    static MessageCreateAction sendLiveMessage(StandardGuildMessageChannel channel, LiveMessage.Filled liveMessage) {
        MessageCreateAction action = channel.sendMessage(liveMessage.text());
        return action.setAllowedMentions(allowedMentionTypes(liveMessage))
                .mentionRoles(liveMessage.mentions().roles().stream().map(String::valueOf).toArray(String[]::new))
                .mentionUsers(liveMessage.users().stream().map(String::valueOf).toArray(String[]::new));
    }

    /**
     * The mention types that may ping: {@code @everyone}/{@code @here} only if the template has them. Role and user
     * mentions are allowed one by one instead.
     */
    static EnumSet<Message.MentionType> allowedMentionTypes(LiveMessage.Filled liveMessage) {
        EnumSet<Message.MentionType> types = EnumSet.noneOf(Message.MentionType.class);
        if (liveMessage.mentions().everyone()) types.add(Message.MentionType.EVERYONE);
        if (liveMessage.mentions().here()) types.add(Message.MentionType.HERE);
        return types;
    }

    private void deleteLiveMessage(long guildId, StandardGuildMessageChannel channel, Problem.Target channelTarget, String channelName,
                                   String channelProblem, String streamerId, Problem.Target streamerTarget, long messageId) {
        String action = Problem.DELETE_LIVE_MESSAGE;
        if (channel == null) {
            recordProblem(guildId, new Problem(action, channelTarget, streamerTarget, "Not deleted: " + channelProblem));
            return;
        }
        CompletableFuture<Void> done = streamerQueue.track(streamerId);
        Runnable clearId = () -> {
            try {
                bot.getServerInfoStore().modify(guildId, info -> {
                    for (ServerInfo.Streamer current : info.streamers) {
                        if (streamerId.equals(current.twitchId) && current.lastLiveNotificationId == messageId) {
                            current.lastLiveNotificationId = 0L;
                            break;
                        }
                    }
                    return null;
                });
            } catch (Exception e) {
                health.log(Component.STORAGE, Severity.ERROR, "Could not clear the deleted live message ID in guild "
                        + guildId + ": " + Health.describe(e), e);
            }
        };
        Consumer<Throwable> onFailure = error -> {
            try {
                Throwable cause = FailureReasons.unwrap(error);
                if (cause instanceof ErrorResponseException e && e.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                    clearId.run();
                }
                recordProblem(guildId, new Problem(action, channelTarget, streamerTarget,
                        FailureReasons.describe(error, action, channelName, null)));
            } finally {
                done.complete(null);
            }
        };
        try {
            channel.deleteMessageById(messageId).queue(ignored -> {
                try {
                    clearId.run();
                } finally {
                    done.complete(null);
                }
            }, onFailure);
        } catch (Exception e) {
            onFailure.accept(e);
        }
    }

    private void recordProblem(long guildId, Problem problem) {
        ServerProblems problems = bot.getServerProblems();
        if (problems == null) {
            System.err.println("Problem in guild " + guildId + ": " + problem);
            return;
        }
        problems.record(guildId, problem);
    }


    // --- subscription sync ---

    private void runLoop() {
        long retryDelay = MIN_SYNC_RETRY;
        while (!stopped) {
            try {
                // The guild loops need Discord's guild list.
                bot.awaitDiscordReady();
            } catch (InterruptedException e) {
                continue;
            }
            long sleep;
            try {
                // Catch up on role changes made while the bot was offline, and remove self-added streamers whose
                // role has been gone too long. The sync below then drops their subscriptions.
                SelfServiceListener.maintain(bot);
            } catch (Exception e) {
                Health.unexpected("Self-service check failed", e);
            }
            try {
                ensureSubscriptionToEvents();
                retryDelay = MIN_SYNC_RETRY;
                sleep = SYNC_INTERVAL;
                health.detail(Component.EVENTSUB, "nextSyncRetry", null);
            } catch (Exception e) {
                String reason = e instanceof IOException ? e.getMessage() : Health.describe(e);
                health.problem(Component.EVENTSUB, State.AMBER, "Subscription sync failed: " + reason,
                        e instanceof IOException ? null : e,
                        SYNC_FAILING_ESCALATE, "Subscription sync has been failing for more than 3 hours");
                health.detail(Component.EVENTSUB, "nextSyncRetry", System.currentTimeMillis() + retryDelay);
                sleep = retryDelay;
                retryDelay = Math.min(retryDelay * 2L, SYNC_INTERVAL);
            }
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException ignored) {
            }
        }
    }

    public List<JsonObject> getEventSubSubscriptions() throws IOException {
        List<JsonObject> subs = new ArrayList<>();
        String next = null;
        boolean first = true;
        while (true) {
            String after = next;
            HttpURLConnection connection = call("list EventSub subscriptions", () -> endpointCaller.get(
                    "https://api.twitch.tv/helix/eventsub/subscriptions",
                    new MapBuilder<String, String>()
                            .put("first", "100")
                            .put("after", after)
                            .build()
            ));
            JsonObject object = Util.parseJson(connection).getAsJsonObject();
            if (first) {
                first = false;
                if (object.has("total_cost")) health.detail(Component.EVENTSUB, "totalCost", object.get("total_cost").getAsLong());
                if (object.has("max_total_cost")) health.detail(Component.EVENTSUB, "maxTotalCost", object.get("max_total_cost").getAsLong());
            }
            JsonArray data = object.get("data").getAsJsonArray();
            for (JsonElement element : data) {
                subs.add(element.getAsJsonObject());
            }
            JsonElement pagination = object.get("pagination");
            if (pagination == null || !pagination.isJsonObject()) break;
            JsonElement cursorElement = pagination.getAsJsonObject().get("cursor");
            if (cursorElement == null || cursorElement.isJsonNull()) break;
            next = cursorElement.getAsString();
        }
        return subs;
    }

    /**
     * Re-runs the subscription sync now. Does nothing before {@link #start()} or after {@link #stop()}.
     */
    public void triggerUpdate() {
        if (stopped) return;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    private void ensureSubscriptionToEvents() throws IOException {
        JDA jda = bot.getJDA();
        if (jda == null) throw new IOException("Discord unavailable");
        Map<String, StreamerInfo> streamerInfos = new HashMap<>();
        List<Guild> guilds = jda.getGuilds();
        for (Guild guild : guilds) {
            ServerInfo serverInfo;
            try {
                serverInfo = bot.getServerInfoStore().get(guild.getIdLong());
            } catch (Exception e) {
                // Unreadable server file: skip it rather than stopping the whole sync loop.
                health.log(Component.STORAGE, Severity.ERROR, "Could not read the server file for guild "
                        + guild.getIdLong() + ": " + Health.describe(e), e);
                continue;
            }
            for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                StreamerInfo streamerInfo = streamerInfos.get(streamer.twitchId);
                if (streamerInfo == null) {
                    streamerInfos.put(streamer.twitchId, streamerInfo = new StreamerInfo());
                }
            }
        }
        List<String> subscriptionsToDelete = new LinkedList<>();
        List<Map<String, Object>> disabled = new ArrayList<>();
        int active = 0;
        List<JsonObject> eventSubSubscriptions = getEventSubSubscriptions();
        for (JsonObject sub : eventSubSubscriptions) {
            String id = null;
            try {
                id = sub.get("id").getAsString();
                String status = sub.get("status").getAsString();
                String type = sub.get("type").getAsString();
                String streamerId = sub.get("condition").getAsJsonObject().get("broadcaster_user_id").getAsString();
                if (!status.equals("enabled")) {
                    // Twitch disabled it (failed verification, too many failed deliveries, revoked, user removed...).
                    // Record why before deleting it; it's re-created below if the streamer is still tracked.
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", id);
                    info.put("type", type);
                    info.put("broadcasterId", streamerId);
                    info.put("status", status);
                    disabled.add(info);
                    subscriptionNotes.disabled(streamerId, type, status, System.currentTimeMillis());
                    health.log(Component.EVENTSUB, Severity.WARNING, "Twitch disabled the " + type
                            + " subscription for broadcaster " + streamerId + " (" + status + "); deleting it", null);
                    subscriptionsToDelete.add(id);
                    continue;
                }
                StreamerInfo streamerInfo = streamerInfos.get(streamerId);
                if (streamerInfo == null) {
                    subscriptionsToDelete.add(id);
                    continue;
                }
                if (type.equals("stream.online")) {
                    streamerInfo.onlineEventSubId = id;
                    active++;
                } else if (type.equals("stream.offline")) {
                    streamerInfo.offlineEventSubId = id;
                    active++;
                }
            } catch (Exception e) {
                if (id != null) {
                    subscriptionsToDelete.add(id);
                }
            }
        }
        int failures = 0;
        Map<String, List<SubscriptionNotes.Note>> createFailures = new HashMap<>();
        for (String id : subscriptionsToDelete) {
            try {
                deleteSubscription(id);
                System.out.println("Deleted subscription " + id);
            } catch (IOException e) {
                failures++;
                health.log(Component.EVENTSUB, Severity.WARNING, "Could not delete subscription " + id + ": " + e.getMessage(), null);
            }
        }
        for (Map.Entry<String, StreamerInfo> entry : streamerInfos.entrySet()) {
            String streamerId = entry.getKey();
            StreamerInfo info = entry.getValue();
            for (String type : new String[]{"stream.online", "stream.offline"}) {
                if ((type.equals("stream.online") ? info.onlineEventSubId : info.offlineEventSubId) != null) continue;
                try {
                    addSubscription(streamerId, type, "1");
                    active++;
                    System.out.println("Added subscription for " + streamerId + "/" + type.substring("stream.".length()));
                } catch (TwitchHttpException e) {
                    if (e.getResponseCode() == 409) {
                        active++; // already exists
                        continue;
                    }
                    failures++;
                    health.log(Component.EVENTSUB, Severity.ERROR, "Could not create the " + type
                            + " subscription for broadcaster " + streamerId + ": " + e.getMessage(), null);
                    createFailures.computeIfAbsent(streamerId, k -> new ArrayList<>()).add(new SubscriptionNotes.Note(
                            SubscriptionNotes.Kind.CREATE_FAILED, type, e.getMessage(), System.currentTimeMillis()));
                } catch (IOException e) {
                    failures++;
                    health.log(Component.EVENTSUB, Severity.ERROR, "Could not create the " + type
                            + " subscription for broadcaster " + streamerId + ": " + Health.describe(e), null);
                    createFailures.computeIfAbsent(streamerId, k -> new ArrayList<>()).add(new SubscriptionNotes.Note(
                            SubscriptionNotes.Kind.CREATE_FAILED, type, Health.describe(e), System.currentTimeMillis()));
                }
            }
        }
        subscriptionNotes.syncFinished(createFailures);
        health.detail(Component.EVENTSUB, "subscriptionCount", active);
        health.detail(Component.EVENTSUB, "disabledSubscriptions", disabled);
        health.detail(Component.EVENTSUB, "lastSync", System.currentTimeMillis());
        health.setState(Component.EVENTSUB, eventSubState(failures, disabled.size()), eventSubMessage(active, failures, disabled.size()));
    }

    static State eventSubState(int failures, int disabled) {
        return failures > 0 || disabled > 0 ? State.AMBER : State.GREEN;
    }

    static String eventSubMessage(int active, int failures, int disabled) {
        if (failures > 0) {
            return failures + " subscription change" + (failures == 1 ? "" : "s") + " failed in the last sync";
        }
        if (disabled > 0) {
            return disabled + " subscription" + (disabled == 1 ? " was" : "s were") + " disabled by Twitch and replaced";
        }
        return active + " subscription" + (active == 1 ? "" : "s") + " active";
    }

    public Map<String, JsonObject> getMultipleStreamerInfosByIds(Collection<String> ids) throws IOException {
        List<String> list;
        if (ids instanceof ArrayList) {
            list = (List<String>) ids;
        } else {
            list = new ArrayList<>(ids);
        }
        Map<String, JsonObject> streamers = new HashMap<>();
        int pageCount = (list.size() + 99) / 100;
        for (int i = 0; i < pageCount; i++) {
            int page = i;
            JsonObject object = Util.parseJson(call("look up Twitch users", () -> getMultipleStreamerInfosByIds(list, page))).getAsJsonObject();
            for (JsonElement element : object.get("data").getAsJsonArray()) {
                JsonObject streamerInfo = element.getAsJsonObject();
                String id = streamerInfo.get("id").getAsString();
                streamers.put(id, streamerInfo);
            }
        }
        return streamers;
    }

    private HttpURLConnection getMultipleStreamerInfosByIds(List<String> ids, int page) throws IOException {
        StringBuilder sb = new StringBuilder();
        int end = Math.min(ids.size(), (page + 1) * 100);
        for (int i = page * 100; i < end; i++) {
            if (sb.length() != 0) sb.append("&");
            sb.append("id=");
            sb.append(urlEncode(ids.get(i)));
        }
        String users = sb.toString();
        return endpointCaller.connect("https://api.twitch.tv/helix/users?" + users);
    }

    public JsonObject getStreamerInfoByUsername(String username) throws IOException {
        JsonObject object = Util.parseJson(call("look up a Twitch user", () -> endpointCaller.get(
                "https://api.twitch.tv/helix/users",
                new MapBuilder<String, String>()
                        .put("login", username.toLowerCase(Locale.ROOT))
                        .build()
        ))).getAsJsonObject();
        try {
            return object.get("data").getAsJsonArray().get(0).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    public JsonObject getStreamerInfoById(String id) throws IOException {
        JsonObject object = Util.parseJson(call("look up a Twitch user", () -> endpointCaller.get(
                "https://api.twitch.tv/helix/users",
                new MapBuilder<String, String>()
                        .put("id", id)
                        .build()
        ))).getAsJsonObject();
        try {
            return object.get("data").getAsJsonArray().get(0).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    public JsonObject getChannelInformation(String id) throws IOException {
        JsonObject channelInformation = Util.parseJson(call("get channel information", () -> endpointCaller.get(
                "https://api.twitch.tv/helix/channels",
                new MapBuilder<String, String>()
                        .put("broadcaster_id", id)
                        .build()
        ))).getAsJsonObject();
        return channelInformation.get("data").getAsJsonArray().get(0).getAsJsonObject();
    }

    private int addSubscription(String broadcasterId, String type, String version) throws IOException {
        JsonObject condition = new JsonObject();
        condition.addProperty("broadcaster_user_id", broadcasterId);
        JsonObject transport = new JsonObject();
        transport.addProperty("method", "webhook");
        transport.addProperty("callback", configuration.apiEndpoint + "/twitch-notification");
        transport.addProperty("secret", configuration.twitch.notificationSecret);
        JsonObject create = new JsonObject();
        create.add("condition", condition);
        create.add("transport", transport);
        create.addProperty("type", type);
        create.addProperty("version", version);
        return call("create an EventSub subscription",
                () -> endpointCaller.post("https://api.twitch.tv/helix/eventsub/subscriptions", create)).getResponseCode();
    }

    private int deleteSubscription(String id) throws IOException {
        return call("delete an EventSub subscription", () -> {
            HttpURLConnection connection = endpointCaller.get("https://api.twitch.tv/helix/eventsub/subscriptions",
                    new MapBuilder<String, String>()
                            .put("id", id)
                            .build()
            );
            connection.setRequestMethod("DELETE");
            return connection;
        }).getResponseCode();
    }

    private static class StreamerInfo {
        String onlineEventSubId;
        String offlineEventSubId;
    }
}

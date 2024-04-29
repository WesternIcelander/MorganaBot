package io.siggi.morganabot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.morganabot.config.AuthCache;
import io.siggi.morganabot.config.Configuration;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.util.EndpointCaller;
import io.siggi.morganabot.util.MapBuilder;
import io.siggi.morganabot.util.Util;
import io.siggi.http.HTTPRequest;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.siggi.morganabot.util.Util.urlEncode;

public class TwitchStreamerWatcher {

    public TwitchStreamerWatcher(MorganaBot bot) {
        this.bot = bot;
    }

    private boolean started = false;
    private boolean stopped = false;
    private final MorganaBot bot;
    private Configuration configuration;
    private AuthCache authCache;
    private EndpointCaller noTokenEndpointCaller;
    private EndpointCaller endpointCaller;
    private Thread thread;

    public void start() {
        if (started) return;
        started = true;
        configuration = bot.getConfiguration();
        authCache = bot.getAuthCache();
        noTokenEndpointCaller = new EndpointCaller(null, bot.getGson());
        endpointCaller = new EndpointCaller((connection) -> {
            connection.setRequestProperty("Authorization", "Bearer " + getAuthToken());
            connection.setRequestProperty("Client-Id", configuration.twitch.clientId);
        }, bot.getGson());
        bot.getWebHandler().getHttpServer().responderRegistry.register("/twitch-notification", this::handleNotification, false, true);
        (thread = new Thread(this::runLoop)).start();
    }

    public void stop() {
        stopped = true;
        if (thread != null) {
            try {
                thread.interrupt();
            } catch (Exception ignored) {
            }
            thread = null;
        }
    }

    private String getAuthToken() throws IOException {
        String token = authCache.twitchAppAccessToken;
        if (token == null) {
            if (Thread.currentThread() != thread) {
                throw new IOException("Auth token not available");
            }
            HttpURLConnection urlc = noTokenEndpointCaller.post(
                    "https://id.twitch.tv/oauth2/token",
                    new MapBuilder<String, String>()
                            .put("client_id", configuration.twitch.clientId)
                            .put("client_secret", configuration.twitch.clientSecret)
                            .put("grant_type", "client_credentials")
                            .build()
            );
            throwIfNon200(urlc.getResponseCode());
            InputStream inputStream = urlc.getInputStream();
            JsonObject object = JsonParser.parseReader(new InputStreamReader(inputStream)).getAsJsonObject();
            token = object.get("access_token").getAsString();
            long expiresIn = object.get("expires_in").getAsLong();
            if (token != null) {
                authCache.twitchAppAccessToken = token;
                authCache.twitchAppAccessTokenExpiry = System.currentTimeMillis() + (expiresIn * 1000L);
                authCache.save();
            }
        }
        return token;
    }

    private void deleteAuthToken() {
        authCache.twitchAppAccessToken = null;
        authCache.save();
    }

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
        try {
            String signature = request.getHeader("Twitch-Eventsub-Message-Signature");
            SecretKeySpec secretKeySpec = new SecretKeySpec(configuration.twitch.notificationSecret.getBytes(), "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKeySpec);
            mac.update(request.getHeader("Twitch-Eventsub-Message-Id").getBytes(StandardCharsets.UTF_8));
            mac.update(request.getHeader("Twitch-Eventsub-Message-Timestamp").getBytes(StandardCharsets.UTF_8));
            String expectedSignature = "sha256=" + Util.bytesToHex(mac.doFinal(requestData));
            if (!expectedSignature.equals(signature)) {
                throw new Exception("Bad Signature");
            }
        } catch (Exception e) {
            request.response.setHeader("403 Forbidden");
            request.response.contentLength(0);
            request.response.write("");
            return;
        }
        request.response.write("");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
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
        try {
            JsonObject notificationObject = JsonParser.parseString(new String(requestData, StandardCharsets.UTF_8)).getAsJsonObject();
            String eventType = notificationObject.get("subscription").getAsJsonObject().get("type").getAsString();
            String streamType = null;
            try {
                streamType = notificationObject.get("event").getAsJsonObject().get("type").getAsString();
            } catch (Exception ignored) {
            }
            String streamerId = notificationObject.get("event").getAsJsonObject().get("broadcaster_user_id").getAsString();
            String streamerName = notificationObject.get("event").getAsJsonObject().get("broadcaster_user_name").getAsString();
            boolean goingOnline = eventType.equals("stream.online");
            if (goingOnline && !streamType.equals("live")) return;
            JDA jda = bot.getJDA();
            List<Guild> guilds = jda.getGuilds();
            JsonObject channelInfo = null;
            if (goingOnline) {
                channelInfo = getChannelInformation(streamerId);
            }
            for (Guild guild : guilds) {
                ServerInfo serverInfo = bot.getServerInfo(guild.getIdLong());
                for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                    if (streamerId.equals(streamer.twitchId)) {
                        boolean postMessage = true;
                        if (goingOnline) {
                            if (streamer.startedStreaming == 0L) {
                                if (now - streamer.stoppedStreaming < serverInfo.streamNotificationCooldown) {
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
                        serverInfo.save();
                        handleLiveNotification(jda, guild, serverInfo, streamer, streamerId, streamerName, channelInfo, goingOnline, postMessage);
                    }
                }
            }
        } catch (Exception e) {
            // TODO: Alert someone that the Twitch watcher is broken
        }
    }

    private void handleLiveNotification(JDA jda, Guild guild, ServerInfo serverInfo, ServerInfo.Streamer streamer, String streamerId, String streamerName, JsonObject channelInfo, boolean goingOnline, boolean postMessage) {
        Role liveRole = serverInfo.roleForLiveUsers == 0L ? null : guild.getRoleById(serverInfo.roleForLiveUsers);
        if (goingOnline) {
            if (serverInfo.roleForLiveUsers != 0L) {
                if (liveRole != null) {
                    guild.addRoleToMember(UserSnowflake.fromId(streamer.discordId), liveRole).queue();
                }
            }
            if (postMessage && serverInfo.channelToPostLiveNotifications != 0L) {
                GuildChannel channelToPostIn = guild.getGuildChannelById(serverInfo.channelToPostLiveNotifications);
                if (channelToPostIn instanceof TextChannel) {
                    TextChannel textChannel = (TextChannel) channelToPostIn;
                    String liveMessage = streamer.liveNotificationTemplate;
                    if (liveMessage == null) liveMessage = serverInfo.liveNotificationTemplate;
                    if (liveMessage == null) liveMessage = "${streamer_name} has gone live on Twitch, playing ${game_name}! Check 'em out!\n${twitch_link}";
                    String gameName = Util.markdownEscape(channelInfo.get("game_name").getAsString());
                    String streamName = Util.markdownEscape(channelInfo.get("title").getAsString());
                    String streamerNameEscaped = Util.markdownEscape(streamerName);
                    String discordMention = streamer.discordId == 0L ? "?" : ("<@" + streamer.discordId + ">");
                    liveMessage = liveMessage
                            .replace("${game_name}", gameName)
                            .replace("${stream_name}", streamName)
                            .replace("${streamer_name}", streamerNameEscaped)
                            .replace("${twitch_link}", "https://www.twitch.tv/" + streamerName)
                            .replace("${discord_name}", discordMention);
                    textChannel.sendMessage(liveMessage).queue();
                }
            }
        } else {
            if (serverInfo.roleForLiveUsers != 0L) {
                if (liveRole != null) {
                    guild.removeRoleFromMember(UserSnowflake.fromId(streamer.discordId), liveRole).queue();
                }
            }
        }
    }

    private void runLoop() {
        while (!stopped) {
            try {
                ensureSubscriptionToEvents();
                try {
                    Thread.sleep(3600000L);
                } catch (InterruptedException e) {
                }
            } catch (Non200ResponseCodeException e) {
                // This will occur if our access token has expired or was revoked
                deleteAuthToken();
                try {
                    getAuthToken();
                } catch (IOException ioe) {
                    // TODO: Alert someone that the Twitch watcher is broken
                    try {
                        Thread.sleep(3600000L);
                    } catch (InterruptedException ignored) {
                    }
                }
            } catch (IOException e) {
                // some other problem occurred
                // TODO: Alert someone that the Twitch watcher is broken
                e.printStackTrace();
            }
        }
    }

    public List<JsonObject> getEventSubSubscriptions() throws IOException {
        List<JsonObject> subs = new ArrayList<>();
        String next = null;
        while (true) {
            HttpURLConnection connection = endpointCaller.get("https://api.twitch.tv/helix/eventsub/subscriptions",
                    new MapBuilder<String, String>()
                            .put("first", "100")
                            .put("after", next)
                            .build()
            );
            throwIfNon200(connection.getResponseCode());
            JsonObject object = parseJson(connection).getAsJsonObject();
            JsonArray data = object.get("data").getAsJsonArray();
            for (JsonElement element : data) {
                subs.add(element.getAsJsonObject());
            }
            JsonElement pagination = object.get("pagination");
            if (pagination == null) break;
            JsonElement cursorElement = pagination.getAsJsonObject().get("cursor");
            if (cursorElement == null) break;
            next = cursorElement.getAsString();
        }
        return subs;
    }

    public void triggerUpdate() {
        thread.interrupt();
    }

    private void ensureSubscriptionToEvents() throws IOException {
        Map<String, StreamerInfo> streamerInfos = new HashMap<>();
        List<Guild> guilds = bot.getJDA().getGuilds();
        for (Guild guild : guilds) {
            ServerInfo serverInfo = bot.getServerInfo(guild.getIdLong());
            for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                StreamerInfo streamerInfo = streamerInfos.get(streamer.twitchId);
                if (streamerInfo == null) {
                    streamerInfos.put(streamer.twitchId, streamerInfo = new StreamerInfo());
                }
            }
        }
        List<String> subscriptionsToDelete = new LinkedList<>();
        List<JsonObject> eventSubSubscriptions = getEventSubSubscriptions();
        for (JsonObject sub : eventSubSubscriptions) {
            String id = null;
            try {
                id = sub.get("id").getAsString();
                boolean enabled = sub.get("status").getAsString().equals("enabled");
                if (!enabled) {
                    subscriptionsToDelete.add(id);
                    continue;
                }
                String streamerId = sub.get("condition").getAsJsonObject().get("broadcaster_user_id").getAsString();
                StreamerInfo streamerInfo = streamerInfos.get(streamerId);
                if (streamerInfo == null) {
                    subscriptionsToDelete.add(id);
                    continue;
                }
                String type = sub.get("type").getAsString();
                if (type.equals("stream.online")) {
                    streamerInfo.onlineEventSubId = id;
                } else if (type.equals("stream.offline")) {
                    streamerInfo.offlineEventSubId = id;
                }
            } catch (Exception e) {
                if (id != null) {
                    subscriptionsToDelete.add(id);
                }
            }
        }
        for (String id : subscriptionsToDelete) {
            deleteSubscription(id);
            System.out.println("Deleted subscription " + id);
        }
        for (Map.Entry<String, StreamerInfo> entry : streamerInfos.entrySet()) {
            String streamerId = entry.getKey();
            StreamerInfo info = entry.getValue();
            if (info.onlineEventSubId == null) {
                addSubscription(streamerId, "stream.online", "1");
                System.out.println("Added subscription for " + streamerId + "/online");
            }
            if (info.offlineEventSubId == null) {
                addSubscription(streamerId, "stream.offline", "1");
                System.out.println("Added subscription for " + streamerId + "/offline");
            }
        }
    }

    public Map<String,JsonObject> getMultipleStreamerInfosByIds(Collection<String> ids) throws IOException {
        List<String> list;
        if (ids instanceof ArrayList) {
            list = (List<String>) ids;
        } else {
            list = new ArrayList<>(ids);
        }
        Map<String,JsonObject> streamers = new HashMap<>();
        int pageCount = (list.size() + 99) / 100;
        for (int i = 0; i < pageCount; i++) {
            JsonObject object = parseJson(getMultipleStreamerInfosByIds(list, i)).getAsJsonObject();
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
        JsonObject object = parseJson(endpointCaller.get(
                "https://api.twitch.tv/helix/users",
                new MapBuilder<String, String>()
                        .put("login", username.toLowerCase(Locale.ROOT))
                        .build()
        )).getAsJsonObject();
        try {
            return object.get("data").getAsJsonArray().get(0).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    public JsonObject getStreamerInfoById(String id) throws IOException {
        JsonObject object = parseJson(endpointCaller.get(
                "https://api.twitch.tv/helix/users",
                new MapBuilder<String, String>()
                        .put("id", id)
                        .build()
        )).getAsJsonObject();
        try {
            return object.get("data").getAsJsonArray().get(0).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    public JsonObject getChannelInformation(String id) throws IOException {
        JsonObject channelInformation = parseJson(endpointCaller.get(
                "https://api.twitch.tv/helix/channels",
                new MapBuilder<String, String>()
                        .put("broadcaster_id", id)
                        .build()
        )).getAsJsonObject();
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
        return throwIfNon200(endpointCaller.post("https://api.twitch.tv/helix/eventsub/subscriptions", create).getResponseCode());
    }

    private int deleteSubscription(String id) throws IOException {
        HttpURLConnection connection = endpointCaller.get("https://api.twitch.tv/helix/eventsub/subscriptions",
                new MapBuilder<String, String>()
                        .put("id", id)
                        .build()
        );
        connection.setRequestMethod("DELETE");
        return throwIfNon200(connection.getResponseCode());
    }

    private JsonElement parseJson(HttpURLConnection connection) throws IOException {
        throwIfNon200(connection.getResponseCode());
        return Util.parseJson(connection);
    }

    private int throwIfNon200(int responseCode) throws Non200ResponseCodeException {
        if (responseCode / 100 != 2) {
            throw new Non200ResponseCodeException(responseCode);
        }
        return responseCode;
    }

    private static class StreamerInfo {
        String onlineEventSubId;
        String offlineEventSubId;
    }

    private static class Non200ResponseCodeException extends IOException {
        final int responseCode;

        Non200ResponseCodeException(int responseCode) {
            super("HTTP Response " + responseCode);
            this.responseCode = responseCode;
        }
    }
}

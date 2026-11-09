package io.siggi.morganabot;

import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * All validation and changes of per-server settings. Commands (and later the dashboard) only parse their input and
 * call this; nothing else should change server settings directly.
 * <p>
 * Every method that fails for a reason the user can fix throws {@link SettingsException}, whose message is meant to
 * be shown to the user as is. Twitch lookups happen before the server lock is taken, since they call the Twitch API.
 */
public class ServerSettings {

    /**
     * What the settings service needs from Twitch. In production this is backed by {@link TwitchStreamerWatcher}.
     */
    public interface Twitch {
        /**
         * Looks up a Twitch user by login name.
         *
         * @return the user, or null if no such user exists
         */
        TwitchUser findUserByLogin(String login) throws IOException;

        /**
         * Re-syncs Twitch subscriptions with the streamers of all servers.
         */
        void triggerUpdate();
    }

    public record TwitchUser(String id, String login, String displayName) {
    }

    /**
     * Refers to an existing streamer entry by Twitch name, linked Discord user, or both.
     *
     * @param twitchName    Twitch login name, or null if not given
     * @param discordUserId linked Discord user ID, or 0 if not given
     */
    public record StreamerRef(String twitchName, long discordUserId) {
        public boolean isEmpty() {
            return twitchName == null && discordUserId == 0L;
        }
    }

    public static class SettingsException extends Exception {
        public SettingsException(String message) {
            super(message);
        }
    }

    // Twitch login names: 1-25 letters, digits or underscores (Twitch's own limits are 4-25, but older accounts can be
    // shorter, so only the character set and maximum are enforced).
    private static final Pattern TWITCH_LOGIN = Pattern.compile("[A-Za-z0-9_]{1,25}");

    static final String NOT_ENABLED = "This feature is not enabled for this Discord server. Contact <@260595748465278976> if you believe this is a mistake.";

    private final ServerInfoStore store;
    private final Twitch twitch;

    public ServerSettings(ServerInfoStore store, Twitch twitch) {
        this.store = store;
        this.twitch = twitch;
    }

    /**
     * Returns a copy of the guild's settings, or throws if Twitch tracking is off for the guild.
     */
    public ServerInfo requireTwitchEnabled(long guildId) throws SettingsException {
        ServerInfo info = store.get(guildId);
        if (!info.enabledTwitchTracking) {
            throw new SettingsException(NOT_ENABLED);
        }
        return info;
    }

    /**
     * Adds a streamer.
     *
     * @param twitchName    the Twitch login name
     * @param discordUserId the linked Discord user, or 0 for none
     * @param channelId     the channel override, or 0 for the server default
     * @return the Twitch user that was added
     */
    public TwitchUser addStreamer(long guildId, String twitchName, long discordUserId, long channelId) throws SettingsException, IOException {
        requireTwitchEnabled(guildId);
        validateId(discordUserId, "Discord user");
        validateId(channelId, "channel");
        TwitchUser user = lookUpTwitchUser(twitchName);
        modify(guildId, serverInfo -> {
            for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                if (user.id().equals(streamer.twitchId)) {
                    throw new SettingsException("The specified Twitch user is already added as a streamer!");
                } else if (discordUserId != 0L && discordUserId == streamer.discordId) {
                    throw new SettingsException("The specified Discord user is already added as a streamer!");
                }
            }
            ServerInfo.Streamer streamer = new ServerInfo.Streamer(discordUserId, user.id());
            streamer.channelToPostLiveNotifications = channelId;
            serverInfo.streamers.add(streamer);
            return null;
        });
        twitch.triggerUpdate();
        return user;
    }

    /**
     * Removes a streamer.
     *
     * @return a copy of the removed entry
     */
    public ServerInfo.Streamer deleteStreamer(long guildId, StreamerRef ref) throws SettingsException, IOException {
        requireTwitchEnabled(guildId);
        String twitchId = resolveTwitchId(ref);
        ServerInfo.Streamer removed = modify(guildId, serverInfo -> {
            ServerInfo.Streamer streamer = findStreamer(serverInfo, ref, twitchId);
            serverInfo.streamers.remove(streamer);
            return copyOf(streamer);
        });
        twitch.triggerUpdate();
        return removed;
    }

    /**
     * Sets or clears (channelId 0) a streamer's channel override.
     *
     * @return a copy of the changed entry
     */
    public ServerInfo.Streamer setChannelOverride(long guildId, StreamerRef ref, long channelId) throws SettingsException, IOException {
        requireTwitchEnabled(guildId);
        validateId(channelId, "channel");
        String twitchId = resolveTwitchId(ref);
        return modify(guildId, serverInfo -> {
            ServerInfo.Streamer streamer = findStreamer(serverInfo, ref, twitchId);
            streamer.channelToPostLiveNotifications = channelId;
            return copyOf(streamer);
        });
    }

    /**
     * Links a streamer to a Discord user, or unlinks them (discordUserId 0).
     *
     * @return a copy of the changed entry
     */
    public ServerInfo.Streamer setStreamerDiscordUser(long guildId, StreamerRef ref, long discordUserId) throws SettingsException, IOException {
        requireTwitchEnabled(guildId);
        validateId(discordUserId, "Discord user");
        String twitchId = resolveTwitchId(ref);
        return modify(guildId, serverInfo -> {
            ServerInfo.Streamer streamer = findStreamer(serverInfo, ref, twitchId);
            if (discordUserId != 0L) {
                for (ServerInfo.Streamer other : serverInfo.streamers) {
                    if (other != streamer && other.discordId == discordUserId) {
                        throw new SettingsException("The specified Discord user is already linked to another streamer!");
                    }
                }
            }
            streamer.discordId = discordUserId;
            return copyOf(streamer);
        });
    }

    /**
     * Sets or clears (0) the role given to streamers while they're live.
     */
    public void setLiveRole(long guildId, long roleId) throws SettingsException {
        requireTwitchEnabled(guildId);
        validateId(roleId, "role");
        modify(guildId, serverInfo -> {
            serverInfo.roleForLiveUsers = roleId;
            return null;
        });
    }

    /**
     * Sets or clears (0) the server's default live notification channel.
     */
    public void setLiveChannel(long guildId, long channelId) throws SettingsException {
        requireTwitchEnabled(guildId);
        validateId(channelId, "channel");
        modify(guildId, serverInfo -> {
            serverInfo.channelToPostLiveNotifications = channelId;
            return null;
        });
    }

    public void setDeleteOnOffline(long guildId, boolean delete) throws SettingsException {
        requireTwitchEnabled(guildId);
        modify(guildId, serverInfo -> {
            serverInfo.deleteNotificationsOnOffline = delete;
            return null;
        });
    }

    /**
     * Sets the minimum time between two live notifications for the same streamer.
     */
    public void setNotificationCooldown(long guildId, long cooldownMillis) throws SettingsException {
        requireTwitchEnabled(guildId);
        if (cooldownMillis < 0L) {
            throw new SettingsException("The notification cooldown can't be negative.");
        }
        modify(guildId, serverInfo -> {
            serverInfo.streamNotificationCooldown = cooldownMillis;
            return null;
        });
    }

    /**
     * Sets the server-wide live message template, or resets it to the built-in default (null).
     */
    public void setServerTemplate(long guildId, String template) throws SettingsException {
        requireTwitchEnabled(guildId);
        String value = normalizeTemplate(template);
        modify(guildId, serverInfo -> {
            serverInfo.liveNotificationTemplate = value;
            return null;
        });
    }

    /**
     * Sets a streamer's own live message template, or clears it (null) so the server-wide one is used.
     *
     * @return a copy of the changed entry
     */
    public ServerInfo.Streamer setStreamerTemplate(long guildId, StreamerRef ref, String template) throws SettingsException, IOException {
        requireTwitchEnabled(guildId);
        String value = normalizeTemplate(template);
        String twitchId = resolveTwitchId(ref);
        return modify(guildId, serverInfo -> {
            ServerInfo.Streamer streamer = findStreamer(serverInfo, ref, twitchId);
            streamer.liveNotificationTemplate = value;
            return copyOf(streamer);
        });
    }

    // --- helpers ---

    private static String normalizeTemplate(String template) throws SettingsException {
        if (template == null) return null;
        if (template.isBlank()) {
            throw new SettingsException("The live message can't be empty.");
        }
        return template;
    }

    private static void validateId(long id, String what) throws SettingsException {
        if (id < 0L) {
            throw new SettingsException("Invalid " + what + ".");
        }
    }

    private TwitchUser lookUpTwitchUser(String twitchName) throws SettingsException, IOException {
        if (twitchName == null || twitchName.isBlank()) {
            throw new SettingsException("You need to specify their Twitch name!");
        }
        String login = twitchName.trim();
        if (!TWITCH_LOGIN.matcher(login).matches()) {
            throw new SettingsException("\"" + login + "\" isn't a valid Twitch name.");
        }
        TwitchUser user = twitch.findUserByLogin(login);
        if (user == null) {
            throw new SettingsException("There's no Twitch user named \"" + login + "\".");
        }
        return user;
    }

    /**
     * Turns the Twitch name in {@code ref} into a Twitch ID, or returns null if no Twitch name was given.
     */
    private String resolveTwitchId(StreamerRef ref) throws SettingsException, IOException {
        if (ref == null || ref.isEmpty()) {
            throw new SettingsException("You need to specify either their Twitch name or Discord name!");
        }
        validateId(ref.discordUserId(), "Discord user");
        if (ref.twitchName() == null) return null;
        return lookUpTwitchUser(ref.twitchName()).id();
    }

    /**
     * Finds the streamer {@code ref} refers to. A Twitch ID matches only that Twitch account and a Discord user only
     * that linked user; an unset Discord ID (0) never matches. If both are given, they must be the same entry.
     */
    static ServerInfo.Streamer findStreamer(ServerInfo serverInfo, StreamerRef ref, String twitchId) throws SettingsException {
        ServerInfo.Streamer byTwitch = null;
        ServerInfo.Streamer byDiscord = null;
        for (ServerInfo.Streamer streamer : serverInfo.streamers) {
            if (twitchId != null && byTwitch == null && twitchId.equals(streamer.twitchId)) {
                byTwitch = streamer;
            }
            if (ref.discordUserId() != 0L && byDiscord == null && streamer.discordId == ref.discordUserId()) {
                byDiscord = streamer;
            }
        }
        boolean byTwitchGiven = ref.twitchName() != null;
        boolean byDiscordGiven = ref.discordUserId() != 0L;
        if ((byTwitchGiven && byTwitch == null) || (byDiscordGiven && byDiscord == null)) {
            throw new SettingsException("No such streamer was found!");
        }
        if (byTwitchGiven && byDiscordGiven && byTwitch != byDiscord) {
            throw new SettingsException("That Twitch name and Discord user belong to different streamers!");
        }
        return byTwitchGiven ? byTwitch : byDiscord;
    }

    private ServerInfo.Streamer copyOf(ServerInfo.Streamer streamer) {
        ServerInfo holder = new ServerInfo();
        holder.streamers.add(streamer);
        return store.copy(holder).streamers.get(0);
    }

    @FunctionalInterface
    private interface Change<T> {
        T apply(ServerInfo serverInfo) throws SettingsException;
    }

    /**
     * Runs {@code change} under the guild's lock. Twitch tracking is checked again inside the lock, so a server that
     * was switched off in the meantime isn't changed. A thrown {@link SettingsException} leaves the file unchanged.
     */
    private <T> T modify(long guildId, Change<T> change) throws SettingsException {
        try {
            return store.modify(guildId, serverInfo -> {
                try {
                    if (!serverInfo.enabledTwitchTracking) {
                        throw new SettingsException(NOT_ENABLED);
                    }
                    return change.apply(serverInfo);
                } catch (SettingsException e) {
                    throw new WrappedSettingsException(e);
                }
            });
        } catch (WrappedSettingsException e) {
            throw (SettingsException) e.getCause();
        }
    }

    private static final class WrappedSettingsException extends RuntimeException {
        WrappedSettingsException(SettingsException cause) {
            super(cause);
        }
    }
}

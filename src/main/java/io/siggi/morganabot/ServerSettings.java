package io.siggi.morganabot;

import io.siggi.morganabot.audit.Actor;
import io.siggi.morganabot.audit.AuditEntry;
import io.siggi.morganabot.audit.AuditEntry.Value;
import io.siggi.morganabot.audit.AuditLog;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * All validation and changes of per-server settings. Commands (and later the dashboard) only parse their input and
 * call this; nothing else should change server settings directly.
 * <p>
 * Every change is recorded in the server's {@link AuditLog}, attributed to the {@link Actor} passed in (who made it, and
 * from where). Setting something to the value it already has records nothing.
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
    private final AuditLog auditLog;

    public ServerSettings(ServerInfoStore store, Twitch twitch, AuditLog auditLog) {
        this.store = store;
        this.twitch = twitch;
        this.auditLog = auditLog;
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
    public TwitchUser addStreamer(Actor actor, long guildId, String twitchName, long discordUserId, long channelId) throws SettingsException, IOException {
        requireTwitchEnabled(guildId);
        validateId(discordUserId, "Discord user");
        validateId(channelId, "channel");
        TwitchUser user = lookUpTwitchUser(twitchName);
        modify(actor, guildId, AuditEntry.STREAMER_ADD, (serverInfo, audit) -> {
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
            audit.streamer = Value.twitch(user.id(), user.login());
            audit.change("twitch", null, Value.twitch(user.id(), user.login()));
            auditStreamerFields(audit, null, streamer);
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
    public ServerInfo.Streamer deleteStreamer(Actor actor, long guildId, StreamerRef ref) throws SettingsException, IOException {
        requireTwitchEnabled(guildId);
        TwitchUser user = resolveTwitchUser(ref);
        ServerInfo.Streamer removed = modify(actor, guildId, AuditEntry.STREAMER_REMOVE, (serverInfo, audit) -> {
            ServerInfo.Streamer streamer = findStreamer(serverInfo, ref, user);
            serverInfo.streamers.remove(streamer);
            audit.streamer = streamerValue(streamer, user);
            audit.change("twitch", streamerValue(streamer, user), null);
            auditStreamerFields(audit, streamer, null);
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
    public ServerInfo.Streamer setChannelOverride(Actor actor, long guildId, StreamerRef ref, long channelId) throws SettingsException, IOException {
        requireTwitchEnabled(guildId);
        validateId(channelId, "channel");
        TwitchUser user = resolveTwitchUser(ref);
        return modify(actor, guildId, AuditEntry.STREAMER_CHANNEL_OVERRIDE, (serverInfo, audit) -> {
            ServerInfo.Streamer streamer = findStreamer(serverInfo, ref, user);
            audit.streamer = streamerValue(streamer, user);
            audit.change("channel", Value.channel(streamer.channelToPostLiveNotifications), Value.channel(channelId));
            streamer.channelToPostLiveNotifications = channelId;
            return copyOf(streamer);
        });
    }

    /**
     * Links a streamer to a Discord user, or unlinks them (discordUserId 0).
     *
     * @return a copy of the changed entry
     */
    public ServerInfo.Streamer setStreamerDiscordUser(Actor actor, long guildId, StreamerRef ref, long discordUserId) throws SettingsException, IOException {
        requireTwitchEnabled(guildId);
        validateId(discordUserId, "Discord user");
        TwitchUser user = resolveTwitchUser(ref);
        return modify(actor, guildId, AuditEntry.STREAMER_DISCORD_USER, (serverInfo, audit) -> {
            ServerInfo.Streamer streamer = findStreamer(serverInfo, ref, user);
            if (discordUserId != 0L) {
                for (ServerInfo.Streamer other : serverInfo.streamers) {
                    if (other != streamer && other.discordId == discordUserId) {
                        throw new SettingsException("The specified Discord user is already linked to another streamer!");
                    }
                }
            }
            audit.streamer = streamerValue(streamer, user);
            audit.change("discordUser", Value.user(streamer.discordId), Value.user(discordUserId));
            streamer.discordId = discordUserId;
            return copyOf(streamer);
        });
    }

    /**
     * Sets or clears (0) the role given to streamers while they're live.
     */
    public void setLiveRole(Actor actor, long guildId, long roleId) throws SettingsException {
        requireTwitchEnabled(guildId);
        validateId(roleId, "role");
        modify(actor, guildId, AuditEntry.LIVE_ROLE, (serverInfo, audit) -> {
            audit.change("role", Value.role(serverInfo.roleForLiveUsers), Value.role(roleId));
            serverInfo.roleForLiveUsers = roleId;
            return null;
        });
    }

    /**
     * Sets or clears (0) the server's default live notification channel.
     */
    public void setLiveChannel(Actor actor, long guildId, long channelId) throws SettingsException {
        requireTwitchEnabled(guildId);
        validateId(channelId, "channel");
        modify(actor, guildId, AuditEntry.LIVE_CHANNEL, (serverInfo, audit) -> {
            audit.change("channel", Value.channel(serverInfo.channelToPostLiveNotifications), Value.channel(channelId));
            serverInfo.channelToPostLiveNotifications = channelId;
            return null;
        });
    }

    public void setDeleteOnOffline(Actor actor, long guildId, boolean delete) throws SettingsException {
        requireTwitchEnabled(guildId);
        modify(actor, guildId, AuditEntry.DELETE_ON_OFFLINE, (serverInfo, audit) -> {
            audit.change("deleteOnOffline", Value.bool(serverInfo.deleteNotificationsOnOffline), Value.bool(delete));
            serverInfo.deleteNotificationsOnOffline = delete;
            return null;
        });
    }

    /**
     * Sets the minimum time between two live notifications for the same streamer.
     */
    public void setNotificationCooldown(Actor actor, long guildId, long cooldownMillis) throws SettingsException {
        requireTwitchEnabled(guildId);
        if (cooldownMillis < 0L) {
            throw new SettingsException("The notification cooldown can't be negative.");
        }
        modify(actor, guildId, AuditEntry.NOTIFICATION_COOLDOWN, (serverInfo, audit) -> {
            audit.change("cooldownMillis", Value.number(serverInfo.streamNotificationCooldown), Value.number(cooldownMillis));
            serverInfo.streamNotificationCooldown = cooldownMillis;
            return null;
        });
    }

    /**
     * Sets the server-wide live message template, or resets it to the built-in default (null).
     */
    public void setServerTemplate(Actor actor, long guildId, String template) throws SettingsException {
        requireTwitchEnabled(guildId);
        String value = normalizeTemplate(template);
        modify(actor, guildId, AuditEntry.SERVER_TEMPLATE, (serverInfo, audit) -> {
            audit.change("template", Value.text(serverInfo.liveNotificationTemplate), Value.text(value));
            serverInfo.liveNotificationTemplate = value;
            return null;
        });
    }

    /**
     * Sets a streamer's own live message template, or clears it (null) so the server-wide one is used.
     *
     * @return a copy of the changed entry
     */
    public ServerInfo.Streamer setStreamerTemplate(Actor actor, long guildId, StreamerRef ref, String template) throws SettingsException, IOException {
        requireTwitchEnabled(guildId);
        String value = normalizeTemplate(template);
        TwitchUser user = resolveTwitchUser(ref);
        return modify(actor, guildId, AuditEntry.STREAMER_TEMPLATE, (serverInfo, audit) -> {
            ServerInfo.Streamer streamer = findStreamer(serverInfo, ref, user);
            audit.streamer = streamerValue(streamer, user);
            audit.change("template", Value.text(streamer.liveNotificationTemplate), Value.text(value));
            streamer.liveNotificationTemplate = value;
            return copyOf(streamer);
        });
    }

    /**
     * Records the settings of a streamer entry as old → new: {@code before} null for an added streamer, {@code after}
     * null for a removed one.
     */
    private static void auditStreamerFields(AuditEntry audit, ServerInfo.Streamer before, ServerInfo.Streamer after) {
        audit.change("discordUser",
                before == null ? null : Value.user(before.discordId),
                after == null ? null : Value.user(after.discordId));
        audit.change("channel",
                before == null ? null : Value.channel(before.channelToPostLiveNotifications),
                after == null ? null : Value.channel(after.channelToPostLiveNotifications));
        audit.change("template",
                before == null ? null : Value.text(before.liveNotificationTemplate),
                after == null ? null : Value.text(after.liveNotificationTemplate));
    }

    /**
     * The Twitch account of {@code streamer}, with its login if the command gave the Twitch name. Otherwise the login
     * is looked up when the entry is recorded.
     */
    private static Value streamerValue(ServerInfo.Streamer streamer, TwitchUser user) {
        String login = user != null && user.id().equals(streamer.twitchId) ? user.login() : null;
        return Value.twitch(streamer.twitchId, login);
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
     * Looks up the Twitch user named in {@code ref}, or returns null if no Twitch name was given.
     */
    private TwitchUser resolveTwitchUser(StreamerRef ref) throws SettingsException, IOException {
        if (ref == null || ref.isEmpty()) {
            throw new SettingsException("You need to specify either their Twitch name or Discord name!");
        }
        validateId(ref.discordUserId(), "Discord user");
        if (ref.twitchName() == null) return null;
        return lookUpTwitchUser(ref.twitchName());
    }

    private static ServerInfo.Streamer findStreamer(ServerInfo serverInfo, StreamerRef ref, TwitchUser user) throws SettingsException {
        return findStreamer(serverInfo, ref, user == null ? null : user.id());
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
        /**
         * Changes {@code serverInfo} and records what changed in {@code audit}.
         */
        T apply(ServerInfo serverInfo, AuditEntry audit) throws SettingsException;
    }

    /**
     * Runs {@code change} under the guild's lock. Twitch tracking is checked again inside the lock, so a server that
     * was switched off in the meantime isn't changed. A thrown {@link SettingsException} leaves the file unchanged
     * and records nothing. Otherwise, if anything actually changed, an audit entry of type {@code type} is recorded
     * for {@code actor} once the change is saved.
     */
    private <T> T modify(Actor actor, long guildId, String type, Change<T> change) throws SettingsException {
        if (actor == null) throw new IllegalArgumentException("actor is required");
        AuditEntry audit = new AuditEntry(type);
        T result;
        try {
            result = store.modify(guildId, serverInfo -> {
                try {
                    if (!serverInfo.enabledTwitchTracking) {
                        throw new SettingsException(NOT_ENABLED);
                    }
                    audit.time = System.currentTimeMillis();
                    return change.apply(serverInfo, audit);
                } catch (SettingsException e) {
                    throw new WrappedSettingsException(e);
                }
            });
        } catch (WrappedSettingsException e) {
            throw (SettingsException) e.getCause();
        }
        if (audit.hasChanges()) {
            auditLog.record(guildId, actor, audit);
        }
        return result;
    }

    private static final class WrappedSettingsException extends RuntimeException {
        WrappedSettingsException(SettingsException cause) {
            super(cause);
        }
    }
}

package io.siggi.morganabot;

import io.siggi.morganabot.audit.Actor;
import io.siggi.morganabot.audit.AuditEntry;
import io.siggi.morganabot.audit.AuditEntry.Value;
import io.siggi.morganabot.audit.AuditLog;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
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
     * Refers to an existing streamer entry by Twitch name, Twitch ID, linked Discord user, or a combination.
     *
     * @param twitchName    Twitch login name, or null if not given (looked up on Twitch)
     * @param discordUserId linked Discord user ID, or 0 if not given
     * @param twitchId      Twitch user ID, or null if not given (matched as is, no Twitch lookup; the dashboard uses
     *                      this). Ignored if {@code twitchName} is given.
     */
    public record StreamerRef(String twitchName, long discordUserId, String twitchId) {
        public StreamerRef(String twitchName, long discordUserId) {
            this(twitchName, discordUserId, null);
        }

        /**
         * The entry with this Twitch user ID.
         */
        public static StreamerRef byTwitchId(String twitchId) {
            return new StreamerRef(null, 0L, twitchId);
        }

        public boolean isEmpty() {
            return twitchName == null && twitchId == null && discordUserId == 0L;
        }
    }

    /**
     * A guild member as self-service sees them.
     *
     * @param name              their Discord username, for audit entries
     * @param present           whether they're in the server; false means they left
     * @param rolesHighestFirst their role IDs, highest in Discord's role order first (as JDA's
     *                          {@code Member.getRoles()} returns them)
     */
    public record SelfServiceMember(long guildId, long userId, String name, boolean present, List<Long> rolesHighestFirst) {
        public SelfServiceMember {
            rolesHighestFirst = rolesHighestFirst == null ? List.of() : List.copyOf(rolesHighestFirst);
        }

        public static SelfServiceMember absent(long guildId, long userId) {
            return new SelfServiceMember(guildId, userId, null, false, List.of());
        }
    }

    /**
     * Looks up members for self-service checks. In production it's the JDA member cache (every member is cached).
     */
    public interface Members {
        /**
         * The member, {@link SelfServiceMember#absent absent} if they're not in the server, or null if that can't be
         * told right now (e.g. Discord is unavailable).
         */
        SelfServiceMember member(long guildId, long userId);

        Members NONE = (guildId, userId) -> null;
    }

    /**
     * The servers the bot is in, for removing it from one. In production it's JDA.
     */
    public interface Guilds {
        /** The server's name, or null if the bot isn't in it (or Discord is unavailable). */
        String name(long guildId);

        /** Leaves the server and waits until Discord confirms it. */
        void leave(long guildId) throws Exception;

        /** A Discord user's name (for the log), or null if unknown. */
        String userName(long userId);

        Guilds NONE = new Guilds() {
            @Override
            public String name(long guildId) {
                return null;
            }

            @Override
            public void leave(long guildId) throws Exception {
                throw new IllegalStateException("Discord is unavailable");
            }

            @Override
            public String userName(long userId) {
                return null;
            }
        };
    }

    /**
     * Which self-service role applies to a member, and the channel it gives.
     *
     * @param roleId    the member's highest self-service role, or 0 if they have none (not eligible)
     * @param channelId that role's channel override, or 0 for the server's default live channel
     */
    public record SelfServiceChannel(long roleId, long channelId) {
        public static final SelfServiceChannel NONE = new SelfServiceChannel(0L, 0L);

        public boolean eligible() {
            return roleId != 0L;
        }
    }

    /**
     * What {@link #selfSetTwitch} did.
     *
     * @param added     a new entry was created (otherwise the member's existing entry was changed)
     * @param roleId    the self-service role that decided the channel
     * @param channelId the channel their posts go to, or 0 for the server default
     */
    public record SelfSetResult(TwitchUser user, boolean added, long roleId, long channelId) {
    }

    public static class SettingsException extends Exception {
        public SettingsException(String message) {
            super(message);
        }
    }

    // Twitch login names: 1-25 letters, digits or underscores (Twitch's own limits are 4-25, but older accounts can be
    // shorter, so only the character set and maximum are enforced).
    private static final Pattern TWITCH_LOGIN = Pattern.compile("[A-Za-z0-9_]{1,25}");

    /**
     * The "Twitch is off for this server" message. It names the first super admin from config as the contact, and
     * leaves the contact out if there are none.
     */
    static String notEnabledMessage(Long contact) {
        if (contact == null || contact == 0L) {
            return "This feature is not enabled for this Discord server.";
        }
        return "This feature is not enabled for this Discord server. Contact <@" + contact + "> if you believe this is a mistake.";
    }

    static final String NO_SELF_SERVICE_ROLE = "You don't have a role that lets you add yourself as a streamer in this server. Ask an admin to add you.";

    /**
     * How often a member may run {@link #selfSetTwitch} (each call looks up the Twitch account).
     */
    static final long SELF_SET_INTERVAL = 60_000L;
    static final long DAY = 24L * 60L * 60L * 1000L;
    /**
     * Upper limit for {@code selfServiceRemoveAfterDays} (about ten years), so the millisecond maths can't overflow.
     */
    public static final int MAX_REMOVE_AFTER_DAYS = 3650;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final ServerInfoStore store;
    private final Twitch twitch;
    private final AuditLog auditLog;
    private volatile Members members = Members.NONE;
    private volatile Access access;
    private volatile Guilds guilds = Guilds.NONE;
    private PrintStream log = System.out;
    private LongSupplier clock = System::currentTimeMillis;
    /**
     * Last {@link #selfSetTwitch} per "guildId:userId", for the rate limit.
     */
    private final Map<String, Long> lastSelfSet = new ConcurrentHashMap<>();

    public ServerSettings(ServerInfoStore store, Twitch twitch, AuditLog auditLog) {
        this.store = store;
        this.twitch = twitch;
        this.auditLog = auditLog;
        this.access = new Access(store, List.of());
    }

    /**
     * Sets the access rules (with the bot's super admins). Without this, nobody is a super admin.
     */
    public void setAccess(Access access) {
        this.access = access == null ? new Access(store, List.of()) : access;
    }

    public Access getAccess() {
        return access;
    }

    /**
     * Sets where members are looked up for self-service checks (the JDA member cache in production).
     */
    public void setMembers(Members members) {
        this.members = members == null ? Members.NONE : members;
    }

    /**
     * Sets how servers are left and named (JDA in production). Without this, {@link #leaveGuild} always fails.
     */
    public void setGuilds(Guilds guilds) {
        this.guilds = guilds == null ? Guilds.NONE : guilds;
    }

    /**
     * Where {@link #leaveGuild} and {@link #unblock} write their stdout log line.
     */
    void setLog(PrintStream log) {
        this.log = log;
    }

    /**
     * The "not enabled" message with this bot's contact (the first super admin).
     */
    public String notEnabledMessage() {
        return notEnabledMessage(access.firstSuperAdmin());
    }

    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Returns a copy of the guild's settings, or throws if Twitch tracking is off for the guild.
     */
    public ServerInfo requireTwitchEnabled(long guildId) throws SettingsException {
        ServerInfo info = store.get(guildId);
        if (!info.enabledTwitchTracking) {
            throw new SettingsException(notEnabledMessage());
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
            streamer.addedBy = actorUserId(actor);
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
            if (streamer.selfService) {
                // An admin's choice wins over the member's roles, even "server default".
                audit.change("pinned", Value.bool(streamer.channelOverridePinned), Value.bool(true));
                streamer.channelOverridePinned = true;
            }
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
     * Turns Twitch announcements on or off for a server ({@code enabledTwitchTracking}). Only super admins may do
     * this; the caller checks that. Works whether Twitch is currently on or off.
     *
     * @return whether the value changed
     */
    public boolean setTwitchTracking(Actor actor, long guildId, boolean enabled) throws SettingsException {
        boolean changed = modifyAny(actor, guildId, AuditEntry.TWITCH_TRACKING, false, (serverInfo, audit) -> {
            boolean old = serverInfo.enabledTwitchTracking;
            audit.change("enabled", Value.bool(old), Value.bool(enabled));
            serverInfo.enabledTwitchTracking = enabled;
            return old != enabled;
        });
        // The watcher only subscribes for servers with Twitch on, so add or remove subscriptions straight away.
        if (changed) twitch.triggerUpdate();
        return changed;
    }

    // --- removing and blocking servers (super admins only; the caller checks that) ---

    /** The longest reason accepted for removing or blocking a server. */
    public static final int MAX_BLOCK_REASON = 500;

    /**
     * What happened in {@link #leaveGuild}.
     *
     * @param guildName the server's name when the bot left
     * @param blocked   whether it's now blocked from adding the bot back
     */
    public record LeaveResult(String guildName, boolean blocked) {
    }

    /**
     * A blocked server, from its settings file.
     */
    public record BlockedServer(long guildId, String name, long blockedAt, long blockedBy, String blockedByName,
                                String reason) {
    }

    /**
     * Removes the bot from a server, optionally blocking it from adding the bot back. The block is saved first (so a
     * failed or interrupted leave still ends with the bot leaving on its next join or startup), then the bot leaves,
     * then the Twitch subscriptions are synced. The action is written to stdout.
     *
     * @param reason optional note, at most {@link #MAX_BLOCK_REASON} characters
     * @param byUser the super admin doing it
     * @throws SettingsException if the bot isn't in the server, the reason is too long, or leaving failed (the block,
     *                           if asked for, is kept)
     */
    public LeaveResult leaveGuild(long guildId, String reason, boolean block, long byUser) throws SettingsException {
        String note = normalizeReason(reason);
        Guilds g = guilds;
        String name = g.name(guildId);
        if (name == null) {
            throw new SettingsException("The bot isn't in that server (or Discord is unavailable).");
        }
        String byName = g.userName(byUser);
        if (block) {
            long now = clock.getAsLong();
            store.modify(guildId, serverInfo -> {
                serverInfo.blocked = true;
                serverInfo.blockedAt = now;
                serverInfo.blockedBy = byUser;
                serverInfo.blockedByName = byName;
                serverInfo.blockedReason = note;
                serverInfo.blockedName = name;
                return null;
            });
        }
        try {
            g.leave(guildId);
        } catch (Exception e) {
            log.println("[Super admin] " + who(byUser, byName) + " tried to remove the bot from " + where(guildId, name)
                    + (block ? " (blocked)" : "") + reasonText(note) + ", but leaving failed: " + e);
            if (block) twitch.triggerUpdate();
            throw new SettingsException("Couldn't leave the server: " + (e.getMessage() == null ? e.toString() : e.getMessage())
                    + (block ? " The block is saved, so the bot leaves when it next starts or is added again." : ""));
        }
        twitch.triggerUpdate();
        log.println("[Super admin] " + who(byUser, byName) + " removed the bot from " + where(guildId, name)
                + (block ? " and blocked it from adding the bot back" : "") + reasonText(note));
        return new LeaveResult(name, block);
    }

    /**
     * Lifts a server's block. It doesn't add the bot back; the server has to invite it again.
     *
     * @return the block that was lifted, or null if the server wasn't blocked
     */
    public BlockedServer unblock(long guildId, long byUser) {
        BlockedServer lifted = store.modify(guildId, serverInfo -> {
            if (!serverInfo.blocked) return null;
            BlockedServer was = blockedServer(guildId, serverInfo);
            serverInfo.clearBlock();
            return was;
        });
        if (lifted != null) {
            log.println("[Super admin] " + who(byUser, guilds.userName(byUser)) + " unblocked " + where(guildId, lifted.name()));
        }
        return lifted;
    }

    /**
     * Every blocked server, newest block first. Scans all server files; unreadable ones are skipped.
     */
    public List<BlockedServer> blockedServers() {
        List<BlockedServer> list = new ArrayList<>();
        for (long guildId : store.guildIds()) {
            ServerInfo info;
            try {
                info = store.get(guildId);
            } catch (RuntimeException e) {
                continue;
            }
            if (info.blocked) list.add(blockedServer(guildId, info));
        }
        list.sort((a, b) -> Long.compare(b.blockedAt(), a.blockedAt()));
        return list;
    }

    /**
     * Whether the server is blocked. An unreadable file counts as not blocked (it's reported elsewhere), so a broken
     * hand edit can't make the bot leave a server.
     */
    public boolean isBlocked(long guildId) {
        try {
            return store.get(guildId).blocked;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static BlockedServer blockedServer(long guildId, ServerInfo info) {
        return new BlockedServer(guildId, info.blockedName, info.blockedAt, info.blockedBy, info.blockedByName,
                info.blockedReason);
    }

    private static String normalizeReason(String reason) throws SettingsException {
        if (reason == null || reason.isBlank()) return null;
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_BLOCK_REASON) {
            throw new SettingsException("The reason is too long (at most " + MAX_BLOCK_REASON + " characters).");
        }
        return trimmed;
    }

    private static String who(long userId, String name) {
        return (name == null ? "user" : name) + " (" + userId + ")";
    }

    private static String where(long guildId, String name) {
        return (name == null ? "server" : "server \"" + name + "\"") + " (" + guildId + ")";
    }

    private static String reasonText(String reason) {
        return reason == null ? "" : ". Reason: " + reason;
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
            if (!Objects.equals(streamer.liveNotificationTemplate, value)) {
                streamer.liveNotificationTemplate = value;
                streamer.templateSetBy = value == null ? 0L : actorUserId(actor);
            }
            return copyOf(streamer);
        });
    }

    /**
     * A streamer saving their own live message (dashboard). Admins (and super admins) can write anything. Anyone else
     * needs a streamer entry and a message customization rule, and every {@code @everyone}, {@code @here}, role
     * mention and user mention in the new text must be allowed by their current rules or already be in their
     * previously saved message (their own template, or else the server-wide one their posts use now). User mentions
     * are never allowed by rules, except of themself. The text is never changed: a mention that isn't allowed rejects
     * the save.
     *
     * @param template the new text, or null to go back to the server-wide message
     * @return a copy of their changed entry
     */
    public ServerInfo.Streamer setOwnTemplate(Actor actor, Access.MemberView member, String template) throws SettingsException {
        long guildId = member.guildId();
        requireTwitchEnabled(guildId);
        String value = normalizeTemplate(template);
        boolean superAdmin = access.isSuperAdmin(member.userId());
        return modify(actor, guildId, AuditEntry.STREAMER_TEMPLATE, (serverInfo, audit) -> {
            ServerInfo.Streamer own = null;
            for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                if (streamer.discordId != 0L && streamer.discordId == member.userId()) {
                    own = streamer;
                    break;
                }
            }
            if (own == null) {
                throw new SettingsException("You don't have a streamer entry in this server.");
            }
            if (!Access.isAdmin(serverInfo, member, superAdmin)) {
                if (!Access.hasRule(serverInfo, member.roles())) {
                    throw new SettingsException("You don't have a role that lets you write your own live message. Ask an admin.");
                }
                if (value != null) {
                    String previous = own.liveNotificationTemplate != null ? own.liveNotificationTemplate : serverInfo.liveNotificationTemplate;
                    List<String> refused = disallowedMentions(Mentions.parse(value), Mentions.parse(previous),
                            Access.allowedMentions(serverInfo, member.roles()), member.userId());
                    if (!refused.isEmpty()) {
                        throw new SettingsException("Your message contains mentions you aren't allowed to use: "
                                + String.join(", ", refused) + ". Remove them and save again.");
                    }
                }
            }
            audit.streamer = Value.twitch(own.twitchId, null);
            audit.change("template", Value.text(own.liveNotificationTemplate), Value.text(value));
            if (!Objects.equals(own.liveNotificationTemplate, value)) {
                own.liveNotificationTemplate = value;
                own.templateSetBy = value == null ? 0L : member.userId();
            }
            return copyOf(own);
        });
    }

    /**
     * The mentions in {@code wanted} that are neither allowed nor already in {@code previous}, written as they appear
     * in a message ({@code @everyone}, {@code @here}, {@code <@&id>}, {@code <@id>}). Mentions are compared by
     * identity, not by count.
     *
     * @param self the member saving; mentioning themself is always fine
     */
    static List<String> disallowedMentions(Mentions wanted, Mentions previous, Access.MentionPermissions allowed, long self) {
        List<String> refused = new ArrayList<>();
        if (wanted.everyone() && !allowed.allowsEveryone() && !previous.everyone()) refused.add("@everyone");
        if (wanted.here() && !allowed.allowsEveryone() && !previous.here()) refused.add("@here");
        for (long roleId : wanted.roles()) {
            if (!allowed.allowsRole(roleId) && !previous.roles().contains(roleId)) refused.add("<@&" + roleId + ">");
        }
        for (long userId : wanted.users()) {
            if (userId != self && !allowed.allowsUsers() && !previous.users().contains(userId)) refused.add("<@" + userId + ">");
        }
        return refused;
    }

    // --- access ---

    /**
     * Makes a role an admin role (full dashboard access). Only Discord Administrators and super admins may.
     *
     * @param editor who's making the change, as access checks see them
     * @return whether it wasn't an admin role yet
     */
    public boolean addAdminRole(Actor actor, Access.MemberView editor, long roleId) throws SettingsException {
        requireAdminRoleEditor(editor);
        long guildId = editor.guildId();
        validateAdminRole(guildId, roleId);
        return modifyAny(actor, guildId, AuditEntry.ADMIN_ROLES, false, (serverInfo, audit) -> {
            audit.subject = Value.role(roleId);
            List<Long> roles = serverInfo.adminRoles();
            roles.removeIf(Objects::isNull);
            if (roles.contains(roleId)) return false;
            roles.add(roleId);
            audit.change("adminRole", null, Value.role(roleId));
            return true;
        });
    }

    /**
     * Removes an admin role. Only Discord Administrators and super admins may.
     *
     * @return whether it was an admin role
     */
    public boolean removeAdminRole(Actor actor, Access.MemberView editor, long roleId) throws SettingsException {
        requireAdminRoleEditor(editor);
        if (roleId <= 0L) throw new SettingsException("Invalid role.");
        return modifyAny(actor, editor.guildId(), AuditEntry.ADMIN_ROLES, false, (serverInfo, audit) -> {
            audit.subject = Value.role(roleId);
            if (!serverInfo.adminRoles().remove(Long.valueOf(roleId))) return false;
            audit.change("adminRole", Value.role(roleId), null);
            return true;
        });
    }

    /**
     * Replaces the whole admin role list (for the dashboard). Only Discord Administrators and super admins may.
     */
    public void setAdminRoles(Actor actor, Access.MemberView editor, List<Long> roleIds) throws SettingsException {
        requireAdminRoleEditor(editor);
        long guildId = editor.guildId();
        Set<Long> wanted = new LinkedHashSet<>();
        for (Long roleId : roleIds) {
            if (roleId == null) throw new SettingsException("Invalid role.");
            validateAdminRole(guildId, roleId);
            wanted.add(roleId);
        }
        List<AuditEntry> entries = new ArrayList<>();
        modifyAny(actor, guildId, AuditEntry.ADMIN_ROLES, false, (serverInfo, audit) -> {
            entries.clear();
            Set<Long> old = new LinkedHashSet<>(serverInfo.adminRoles());
            old.remove(null);
            Set<Long> all = new LinkedHashSet<>(old);
            all.addAll(wanted);
            for (long roleId : all) {
                AuditEntry entry = new AuditEntry(AuditEntry.ADMIN_ROLES);
                entry.time = audit.time;
                entry.subject = Value.role(roleId);
                entry.change("adminRole", old.contains(roleId) ? Value.role(roleId) : null, wanted.contains(roleId) ? Value.role(roleId) : null);
                if (entry.hasChanges()) entries.add(entry);
            }
            serverInfo.adminRoles = new ArrayList<>(wanted);
            return null;
        });
        for (AuditEntry entry : entries) {
            auditLog.record(guildId, actor, entry);
        }
    }

    private void requireAdminRoleEditor(Access.MemberView editor) throws SettingsException {
        if (editor == null || !access.canEditAdminRoles(editor)) {
            throw new SettingsException("Only members with Discord's Administrator permission can change the admin roles.");
        }
    }

    private static void validateAdminRole(long guildId, long roleId) throws SettingsException {
        if (roleId <= 0L) throw new SettingsException("Invalid role.");
        if (roleId == guildId) throw new SettingsException("The @everyone role can't be an admin role.");
    }

    // --- message customization rules ---

    /**
     * Adds a message customization rule for a role, or replaces the one it already has.
     *
     * @param mentionRoles the roles its members may mention
     */
    public void addMessageRule(Actor actor, long guildId, long roleId, boolean allowEveryone, List<Long> mentionRoles) throws SettingsException {
        requireTwitchEnabled(guildId);
        ServerInfo.MessageCustomizationRule rule = validateRule(guildId, new ServerInfo.MessageCustomizationRule(roleId, allowEveryone, mentionRoles));
        modify(actor, guildId, AuditEntry.MESSAGE_RULES, (serverInfo, audit) -> {
            List<ServerInfo.MessageCustomizationRule> rules = serverInfo.messageCustomizationRules();
            rules.removeIf(Objects::isNull);
            ServerInfo.MessageCustomizationRule existing = null;
            for (ServerInfo.MessageCustomizationRule r : rules) {
                if (r.roleId == roleId) {
                    existing = r;
                    break;
                }
            }
            auditRule(audit, roleId, existing, rule);
            if (existing == null) {
                rules.add(rule);
            } else {
                existing.allowEveryone = rule.allowEveryone;
                existing.allowedMentionRoles = new ArrayList<>(rule.allowedMentionRoles);
            }
            return null;
        });
    }

    /**
     * Removes a role's message customization rule. Templates its members already saved are kept and still post as
     * written.
     *
     * @return whether the role had a rule
     */
    public boolean removeMessageRule(Actor actor, long guildId, long roleId) throws SettingsException {
        requireTwitchEnabled(guildId);
        if (roleId <= 0L) throw new SettingsException("Invalid role.");
        return modify(actor, guildId, AuditEntry.MESSAGE_RULES, (serverInfo, audit) -> {
            for (ServerInfo.MessageCustomizationRule rule : List.copyOf(serverInfo.messageCustomizationRules())) {
                if (rule != null && rule.roleId == roleId) {
                    serverInfo.messageCustomizationRules().remove(rule);
                    auditRule(audit, roleId, rule, null);
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Replaces all message customization rules (for the dashboard). A role listed twice keeps its first rule.
     */
    public void setMessageRules(Actor actor, long guildId, List<ServerInfo.MessageCustomizationRule> newRules) throws SettingsException {
        requireTwitchEnabled(guildId);
        Map<Long, ServerInfo.MessageCustomizationRule> wanted = new LinkedHashMap<>();
        for (ServerInfo.MessageCustomizationRule rule : newRules) {
            ServerInfo.MessageCustomizationRule valid = validateRule(guildId, rule);
            wanted.putIfAbsent(valid.roleId, valid);
        }
        List<AuditEntry> entries = new ArrayList<>();
        modify(actor, guildId, AuditEntry.MESSAGE_RULES, (serverInfo, audit) -> {
            entries.clear();
            Map<Long, ServerInfo.MessageCustomizationRule> old = new LinkedHashMap<>();
            for (ServerInfo.MessageCustomizationRule rule : serverInfo.messageCustomizationRules()) {
                if (rule != null) old.putIfAbsent(rule.roleId, rule);
            }
            Set<Long> all = new LinkedHashSet<>(old.keySet());
            all.addAll(wanted.keySet());
            for (long roleId : all) {
                AuditEntry entry = new AuditEntry(AuditEntry.MESSAGE_RULES);
                entry.time = audit.time;
                auditRule(entry, roleId, old.get(roleId), wanted.get(roleId));
                if (entry.hasChanges()) entries.add(entry);
            }
            serverInfo.messageCustomizationRules = new ArrayList<>(wanted.values());
            return null;
        });
        for (AuditEntry entry : entries) {
            auditLog.record(guildId, actor, entry);
        }
    }

    private static ServerInfo.MessageCustomizationRule validateRule(long guildId, ServerInfo.MessageCustomizationRule rule) throws SettingsException {
        if (rule == null || rule.roleId <= 0L) throw new SettingsException("Invalid role.");
        Set<Long> mentionRoles = new LinkedHashSet<>();
        for (Long mentionRole : rule.allowedMentionRoles()) {
            if (mentionRole == null || mentionRole <= 0L) throw new SettingsException("Invalid role to mention.");
            if (mentionRole == guildId) {
                throw new SettingsException("Use allow-everyone to let members mention @everyone and @here.");
            }
            mentionRoles.add(mentionRole);
        }
        return new ServerInfo.MessageCustomizationRule(rule.roleId, rule.allowEveryone, new ArrayList<>(mentionRoles));
    }

    /**
     * Records a rule's changes as old → new: {@code before} null for a new rule, {@code after} null for a removed one.
     */
    private static void auditRule(AuditEntry audit, long roleId, ServerInfo.MessageCustomizationRule before, ServerInfo.MessageCustomizationRule after) {
        audit.subject = Value.role(roleId);
        audit.change("rule", before == null ? null : Value.role(roleId), after == null ? null : Value.role(roleId));
        audit.change("allowEveryone",
                before == null ? null : Value.bool(before.allowEveryone),
                after == null ? null : Value.bool(after.allowEveryone));
        Set<Long> oldRoles = before == null ? Set.of() : new LinkedHashSet<>(before.allowedMentionRoles());
        Set<Long> newRoles = after == null ? Set.of() : new LinkedHashSet<>(after.allowedMentionRoles());
        for (Long id : oldRoles) {
            if (id != null && !newRoles.contains(id)) audit.change("mentionRole", Value.role(id), null);
        }
        for (Long id : newRoles) {
            if (id != null && !oldRoles.contains(id)) audit.change("mentionRole", null, Value.role(id));
        }
    }

    // --- self-service ---

    /**
     * The member's highest self-service role (by Discord's role order) and its channel. The highest role wins even if
     * its channel is empty (server default); lower roles' channels are then ignored.
     */
    public static SelfServiceChannel resolveSelfServiceChannel(ServerInfo info, List<Long> rolesHighestFirst) {
        Map<Long, Long> channels = new HashMap<>();
        for (ServerInfo.SelfServiceRole role : info.selfServiceRoles()) {
            if (role != null && role.roleId != 0L) channels.putIfAbsent(role.roleId, role.channelId);
        }
        if (channels.isEmpty()) return SelfServiceChannel.NONE;
        for (Long roleId : rolesHighestFirst) {
            if (roleId != null && channels.containsKey(roleId)) {
                return new SelfServiceChannel(roleId, channels.get(roleId));
            }
        }
        return SelfServiceChannel.NONE;
    }

    /**
     * {@link #resolveSelfServiceChannel(ServerInfo, List)} with the guild's current settings. A member who isn't in
     * the server gets {@link SelfServiceChannel#NONE}.
     */
    public SelfServiceChannel resolveSelfServiceChannel(SelfServiceMember member) {
        if (!member.present()) return SelfServiceChannel.NONE;
        return resolveSelfServiceChannel(store.get(member.guildId()), member.rolesHighestFirst());
    }

    /**
     * {@code /settwitchchannel}: the member adds themself as a streamer, or replaces the Twitch account of their
     * existing entry (whoever added it). Their channel is set from their highest self-service role and no longer
     * pinned; their own message template is kept. Limited to one call per member per minute.
     */
    public SelfSetResult selfSetTwitch(Actor actor, SelfServiceMember member, String twitchName) throws SettingsException, IOException {
        long guildId = member.guildId();
        ServerInfo current = requireTwitchEnabled(guildId);
        if (!member.present() || !resolveSelfServiceChannel(current, member.rolesHighestFirst()).eligible()) {
            throw new SettingsException(NO_SELF_SERVICE_ROLE);
        }
        String login = validateLogin(twitchName);
        long now = clock.getAsLong();
        String key = guildId + ":" + member.userId();
        Long last = lastSelfSet.get(key);
        if (last != null && now - last < SELF_SET_INTERVAL) {
            long seconds = Math.max(1L, (SELF_SET_INTERVAL - (now - last) + 999L) / 1000L);
            throw new SettingsException("You can only do this once a minute. Try again in " + seconds + " second" + (seconds == 1L ? "" : "s") + ".");
        }
        lastSelfSet.put(key, now);
        TwitchUser user = lookUpTwitchUser(login);
        SelfSetResult result = modify(actor, guildId, AuditEntry.SELF_SERVICE_SET, (serverInfo, audit) -> {
            SelfServiceChannel resolved = resolveSelfServiceChannel(serverInfo, member.rolesHighestFirst());
            if (!resolved.eligible()) {
                throw new SettingsException(NO_SELF_SERVICE_ROLE);
            }
            ServerInfo.Streamer own = null;
            ServerInfo.Streamer sameTwitch = null;
            for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                if (own == null && streamer.discordId == member.userId()) own = streamer;
                if (sameTwitch == null && user.id().equals(streamer.twitchId)) sameTwitch = streamer;
            }
            if (sameTwitch != null && sameTwitch != own) {
                throw new SettingsException("That Twitch account is already added in this server by someone else. If it's yours, ask an admin.");
            }
            audit.streamer = Value.twitch(user.id(), user.login());
            boolean added = own == null;
            if (added) {
                own = new ServerInfo.Streamer(member.userId(), user.id());
                own.selfService = true;
                own.addedBy = member.userId();
                own.channelToPostLiveNotifications = resolved.channelId();
                serverInfo.streamers.add(own);
                audit.change("twitch", null, Value.twitch(user.id(), user.login()));
                auditStreamerFields(audit, null, own);
            } else {
                if (!user.id().equals(own.twitchId)) {
                    audit.change("twitch", Value.twitch(own.twitchId, null), Value.twitch(user.id(), user.login()));
                    own.twitchId = user.id();
                    // The streaming state belonged to the old account.
                    own.startedStreaming = 0L;
                    own.stoppedStreaming = 0L;
                }
                audit.change("channel", Value.channel(own.channelToPostLiveNotifications), Value.channel(resolved.channelId()));
                own.channelToPostLiveNotifications = resolved.channelId();
                audit.change("pinned", Value.bool(own.channelOverridePinned), Value.bool(false));
                own.channelOverridePinned = false;
                audit.change("paused", Value.bool(own.selfServiceLostAt != 0L), Value.bool(false));
                own.selfServiceLostAt = 0L;
            }
            return new SelfSetResult(user, added, resolved.roleId(), resolved.channelId());
        });
        twitch.triggerUpdate();
        return result;
    }

    /**
     * {@code /removetwitchchannel}: the member removes their own entry, whether or not they still have a
     * self-service role, and whoever added it.
     *
     * @return a copy of the removed entry
     */
    public ServerInfo.Streamer selfRemove(Actor actor, long guildId, long userId) throws SettingsException {
        requireTwitchEnabled(guildId);
        if (userId <= 0L) throw new SettingsException("Invalid Discord user.");
        ServerInfo.Streamer removed = modify(actor, guildId, AuditEntry.SELF_SERVICE_REMOVE, (serverInfo, audit) -> {
            ServerInfo.Streamer own = null;
            for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                if (streamer.discordId == userId) {
                    own = streamer;
                    break;
                }
            }
            if (own == null) {
                throw new SettingsException("You don't have a Twitch channel set in this server.");
            }
            serverInfo.streamers.remove(own);
            audit.streamer = Value.twitch(own.twitchId, null);
            audit.change("twitch", Value.twitch(own.twitchId, null), null);
            auditStreamerFields(audit, own, null);
            return copyOf(own);
        });
        twitch.triggerUpdate();
        return removed;
    }

    /**
     * Brings one member's self-added entry in line with their current roles: pauses it if they lost their last
     * self-service role or left, resumes it if they got one back, and moves an unpinned entry to the channel of
     * their highest role. Idempotent: if nothing needs to change, nothing is written or audited.
     *
     * @return the number of entries changed (0 or 1)
     */
    public int reconcileSelfService(SelfServiceMember member) {
        Map<Long, SelfServiceMember> known = new HashMap<>();
        known.put(member.userId(), member);
        return reconcile(member.guildId(), known);
    }

    /**
     * {@link #reconcileSelfService(SelfServiceMember)} for every self-added streamer in the guild, with members
     * looked up through {@link #setMembers Members}. Members that can't be looked up right now are left alone.
     *
     * @return the number of entries changed
     */
    public int reconcileSelfService(long guildId) {
        ServerInfo info = store.get(guildId);
        if (!info.enabledTwitchTracking) return 0;
        Map<Long, SelfServiceMember> known = new HashMap<>();
        for (ServerInfo.Streamer streamer : info.streamers) {
            if (!streamer.selfService || streamer.discordId == 0L || known.containsKey(streamer.discordId)) continue;
            SelfServiceMember member = members.member(guildId, streamer.discordId);
            if (member != null) known.put(streamer.discordId, member);
        }
        if (known.isEmpty()) return 0;
        return reconcile(guildId, known);
    }

    private int reconcile(long guildId, Map<Long, SelfServiceMember> known) {
        long now = clock.getAsLong();
        List<AuditEntry> entries = store.modify(guildId, serverInfo -> {
            List<AuditEntry> changed = new ArrayList<>();
            // Changes are refused while Twitch is off; the hourly check catches up once it's back on.
            if (!serverInfo.enabledTwitchTracking) return changed;
            for (ServerInfo.Streamer streamer : serverInfo.streamers) {
                if (!streamer.selfService || streamer.discordId == 0L) continue;
                SelfServiceMember member = known.get(streamer.discordId);
                if (member == null) continue;
                SelfServiceChannel resolved = member.present()
                        ? resolveSelfServiceChannel(serverInfo, member.rolesHighestFirst()) : SelfServiceChannel.NONE;
                AuditEntry audit;
                if (!resolved.eligible()) {
                    if (streamer.selfServiceLostAt != 0L) continue;
                    audit = new AuditEntry(AuditEntry.SELF_SERVICE_PAUSE);
                    audit.change("paused", Value.bool(false), Value.bool(true));
                    audit.note = member.present() ? "No self-service role any more" : "Left the server";
                    streamer.selfServiceLostAt = now;
                } else {
                    boolean resume = streamer.selfServiceLostAt != 0L;
                    boolean move = !streamer.channelOverridePinned && streamer.channelToPostLiveNotifications != resolved.channelId();
                    if (!resume && !move) continue;
                    audit = new AuditEntry(resume ? AuditEntry.SELF_SERVICE_RESUME : AuditEntry.STREAMER_CHANNEL_OVERRIDE);
                    if (resume) {
                        audit.change("paused", Value.bool(true), Value.bool(false));
                        streamer.selfServiceLostAt = 0L;
                    }
                    if (move) {
                        audit.change("channel", Value.channel(streamer.channelToPostLiveNotifications), Value.channel(resolved.channelId()));
                        audit.subject = Value.role(resolved.roleId());
                        audit.note = "Channel follows their highest self-service role";
                        streamer.channelToPostLiveNotifications = resolved.channelId();
                    }
                }
                audit.time = now;
                audit.streamer = Value.twitch(streamer.twitchId, null);
                changed.add(audit);
            }
            return changed;
        });
        for (AuditEntry entry : entries) {
            auditLog.record(guildId, Actor.system(), entry);
        }
        return entries.size();
    }

    /**
     * Removes self-added entries that have been without a self-service role for at least
     * {@code selfServiceRemoveAfterDays} days (never, if that's 0). Doesn't trigger a Twitch sync; the caller does
     * that if anything was removed.
     *
     * @return copies of the removed entries
     */
    public List<ServerInfo.Streamer> expireSelfService(long guildId) {
        long now = clock.getAsLong();
        record Removed(ServerInfo.Streamer streamer, AuditEntry audit) {
        }
        List<Removed> removed = store.modify(guildId, serverInfo -> {
            List<Removed> list = new ArrayList<>();
            int days = serverInfo.selfServiceRemoveAfterDays;
            if (!serverInfo.enabledTwitchTracking || days <= 0) return list;
            long after = Math.min(days, MAX_REMOVE_AFTER_DAYS) * DAY;
            for (ServerInfo.Streamer streamer : List.copyOf(serverInfo.streamers)) {
                if (!streamer.isSelfServicePaused() || now - streamer.selfServiceLostAt < after) continue;
                serverInfo.streamers.remove(streamer);
                AuditEntry audit = new AuditEntry(AuditEntry.STREAMER_REMOVE);
                audit.time = now;
                audit.streamer = Value.twitch(streamer.twitchId, null);
                audit.change("twitch", Value.twitch(streamer.twitchId, null), null);
                auditStreamerFields(audit, streamer, null);
                audit.note = "Self-added streamer without a self-service role since "
                        + DATE.format(Instant.ofEpochMilli(streamer.selfServiceLostAt)) + " (removed after " + days + " day" + (days == 1 ? "" : "s") + ")";
                list.add(new Removed(copyOf(streamer), audit));
            }
            return list;
        });
        List<ServerInfo.Streamer> result = new ArrayList<>();
        for (Removed r : removed) {
            auditLog.record(guildId, Actor.system(), r.audit());
            result.add(r.streamer());
        }
        return result;
    }

    /**
     * Adds a self-service role, or changes the channel of one that's already in the list. Afterwards every
     * self-added streamer is checked against the new list.
     *
     * @param channelId the channel for members whose highest self-service role this is, or 0 for the server default
     */
    public void addSelfServiceRole(Actor actor, long guildId, long roleId, long channelId) throws SettingsException {
        requireTwitchEnabled(guildId);
        if (roleId <= 0L) throw new SettingsException("Invalid role.");
        validateId(channelId, "channel");
        if (roleId == guildId) throw new SettingsException("The @everyone role can't be a self-service role.");
        modify(actor, guildId, AuditEntry.SELF_SERVICE_ROLES, (serverInfo, audit) -> {
            audit.subject = Value.role(roleId);
            List<ServerInfo.SelfServiceRole> roles = serverInfo.selfServiceRoles();
            roles.removeIf(Objects::isNull);
            ServerInfo.SelfServiceRole existing = null;
            for (ServerInfo.SelfServiceRole role : roles) {
                if (role.roleId == roleId) {
                    existing = role;
                    break;
                }
            }
            if (existing == null) {
                roles.add(new ServerInfo.SelfServiceRole(roleId, channelId));
                audit.change("channel", null, selfServiceChannelValue(channelId));
            } else {
                audit.change("channel", selfServiceChannelValue(existing.channelId), selfServiceChannelValue(channelId));
                existing.channelId = channelId;
            }
            return null;
        });
        reconcileSelfService(guildId);
    }

    /**
     * Removes a role from the self-service list. Members who only had that role are paused.
     *
     * @return whether the role was in the list
     */
    public boolean removeSelfServiceRole(Actor actor, long guildId, long roleId) throws SettingsException {
        requireTwitchEnabled(guildId);
        if (roleId <= 0L) throw new SettingsException("Invalid role.");
        boolean removed = modify(actor, guildId, AuditEntry.SELF_SERVICE_ROLES, (serverInfo, audit) -> {
            audit.subject = Value.role(roleId);
            for (ServerInfo.SelfServiceRole role : List.copyOf(serverInfo.selfServiceRoles())) {
                if (role != null && role.roleId == roleId) {
                    serverInfo.selfServiceRoles().remove(role);
                    audit.change("channel", selfServiceChannelValue(role.channelId), null);
                    return true;
                }
            }
            return false;
        });
        if (removed) reconcileSelfService(guildId);
        return removed;
    }

    /**
     * Replaces the whole self-service role list (for the dashboard). Duplicate roles keep their first channel.
     */
    public void setSelfServiceRoles(Actor actor, long guildId, List<ServerInfo.SelfServiceRole> newRoles) throws SettingsException {
        requireTwitchEnabled(guildId);
        Map<Long, Long> wanted = new LinkedHashMap<>();
        for (ServerInfo.SelfServiceRole role : newRoles) {
            if (role == null || role.roleId <= 0L || role.roleId == guildId) throw new SettingsException("Invalid role.");
            validateId(role.channelId, "channel");
            wanted.putIfAbsent(role.roleId, role.channelId);
        }
        // One audit entry per role that changed.
        List<AuditEntry> entries = new ArrayList<>();
        modify(actor, guildId, AuditEntry.SELF_SERVICE_ROLES, (serverInfo, audit) -> {
            entries.clear();
            Map<Long, Long> old = new LinkedHashMap<>();
            for (ServerInfo.SelfServiceRole role : serverInfo.selfServiceRoles()) {
                if (role != null) old.putIfAbsent(role.roleId, role.channelId);
            }
            Set<Long> all = new LinkedHashSet<>(old.keySet());
            all.addAll(wanted.keySet());
            for (long roleId : all) {
                AuditEntry entry = new AuditEntry(AuditEntry.SELF_SERVICE_ROLES);
                entry.time = audit.time;
                entry.subject = Value.role(roleId);
                entry.change("channel", old.containsKey(roleId) ? selfServiceChannelValue(old.get(roleId)) : null,
                        wanted.containsKey(roleId) ? selfServiceChannelValue(wanted.get(roleId)) : null);
                if (entry.hasChanges()) entries.add(entry);
            }
            serverInfo.selfServiceRoles = new ArrayList<>();
            for (Map.Entry<Long, Long> role : wanted.entrySet()) {
                serverInfo.selfServiceRoles.add(new ServerInfo.SelfServiceRole(role.getKey(), role.getValue()));
            }
            return null;
        });
        for (AuditEntry entry : entries) {
            auditLog.record(guildId, actor, entry);
        }
        if (!entries.isEmpty()) reconcileSelfService(guildId);
    }

    /**
     * Sets how many days a self-added streamer may be without a self-service role before their entry is removed
     * (0 = never).
     */
    public void setSelfServiceRemoveAfterDays(Actor actor, long guildId, int days) throws SettingsException {
        requireTwitchEnabled(guildId);
        if (days < 0 || days > MAX_REMOVE_AFTER_DAYS) {
            throw new SettingsException("The number of days must be between 0 (never) and " + MAX_REMOVE_AFTER_DAYS + ".");
        }
        modify(actor, guildId, AuditEntry.SELF_SERVICE_EXPIRY, (serverInfo, audit) -> {
            audit.change("removeAfterDays", Value.number(serverInfo.selfServiceRemoveAfterDays), Value.number(days));
            serverInfo.selfServiceRemoveAfterDays = days;
            return null;
        });
    }

    /**
     * Unpins a self-added streamer's channel, so it follows their roles again, and recalculates it from their
     * current roles straight away. If they have no self-service role right now, the channel is left as is and
     * follows their role once they get one back.
     *
     * @return a copy of the changed entry
     */
    public ServerInfo.Streamer unpinChannel(Actor actor, long guildId, StreamerRef ref) throws SettingsException, IOException {
        requireTwitchEnabled(guildId);
        TwitchUser user = resolveTwitchUser(ref);
        ServerInfo.Streamer found = findStreamer(store.get(guildId), ref, user);
        if (!found.selfService) {
            throw new SettingsException("Only self-added streamers can follow their roles. This streamer was added by an admin.");
        }
        SelfServiceMember member = found.discordId == 0L ? null : members.member(guildId, found.discordId);
        if (member == null) {
            throw new SettingsException("Couldn't look up this streamer's roles right now. Try again in a moment.");
        }
        return modify(actor, guildId, AuditEntry.STREAMER_CHANNEL_PIN, (serverInfo, audit) -> {
            ServerInfo.Streamer streamer = findStreamer(serverInfo, ref, user);
            if (!streamer.selfService || streamer.discordId != member.userId()) {
                throw new SettingsException("This streamer changed in the meantime. Try again.");
            }
            audit.streamer = streamerValue(streamer, user);
            audit.change("pinned", Value.bool(streamer.channelOverridePinned), Value.bool(false));
            streamer.channelOverridePinned = false;
            SelfServiceChannel resolved = member.present()
                    ? resolveSelfServiceChannel(serverInfo, member.rolesHighestFirst()) : SelfServiceChannel.NONE;
            if (resolved.eligible()) {
                audit.change("channel", Value.channel(streamer.channelToPostLiveNotifications), Value.channel(resolved.channelId()));
                audit.subject = Value.role(resolved.roleId());
                streamer.channelToPostLiveNotifications = resolved.channelId();
                if (streamer.selfServiceLostAt != 0L) {
                    audit.change("paused", Value.bool(true), Value.bool(false));
                    streamer.selfServiceLostAt = 0L;
                }
            }
            return copyOf(streamer);
        });
    }

    private static Value selfServiceChannelValue(long channelId) {
        return channelId == 0L ? Value.text("server default") : Value.channel(channelId);
    }

    private static long actorUserId(Actor actor) {
        if (actor == null || actor.userId == null) return 0L;
        try {
            return Long.parseLong(actor.userId);
        } catch (NumberFormatException e) {
            return 0L;
        }
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
        int length = LiveMessage.exampleLength(template);
        if (length > LiveMessage.MAX_LENGTH) {
            throw new SettingsException("The live message is too long: with a long stream title it would be " + length
                    + " characters, and Discord allows " + LiveMessage.MAX_LENGTH + ". Shorten it by "
                    + (length - LiveMessage.MAX_LENGTH) + " characters.");
        }
        return template;
    }

    private static void validateId(long id, String what) throws SettingsException {
        if (id < 0L) {
            throw new SettingsException("Invalid " + what + ".");
        }
    }

    private static String validateLogin(String twitchName) throws SettingsException {
        if (twitchName == null || twitchName.isBlank()) {
            throw new SettingsException("You need to specify their Twitch name!");
        }
        String login = twitchName.trim();
        if (!TWITCH_LOGIN.matcher(login).matches()) {
            throw new SettingsException("\"" + login + "\" isn't a valid Twitch name.");
        }
        return login;
    }

    private TwitchUser lookUpTwitchUser(String twitchName) throws SettingsException, IOException {
        String login = validateLogin(twitchName);
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
        return findStreamer(serverInfo, ref, user != null ? user.id() : ref.twitchName() == null ? ref.twitchId() : null);
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
        boolean byTwitchGiven = ref.twitchName() != null || ref.twitchId() != null;
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
        return modifyAny(actor, guildId, type, true, change);
    }

    /**
     * {@link #modify}, optionally without the Twitch tracking check (for settings that aren't about Twitch, like the
     * admin roles).
     */
    private <T> T modifyAny(Actor actor, long guildId, String type, boolean requireTwitch, Change<T> change) throws SettingsException {
        if (actor == null) throw new IllegalArgumentException("actor is required");
        AuditEntry audit = new AuditEntry(type);
        T result;
        try {
            result = store.modify(guildId, serverInfo -> {
                try {
                    if (requireTwitch && !serverInfo.enabledTwitchTracking) {
                        throw new SettingsException(notEnabledMessage());
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

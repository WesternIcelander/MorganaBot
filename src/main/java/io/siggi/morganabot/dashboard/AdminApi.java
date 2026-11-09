package io.siggi.morganabot.dashboard;

import io.siggi.morganabot.ServerSettings;
import io.siggi.morganabot.audit.AuditEntry;
import io.siggi.morganabot.audit.AuditLog;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;
import io.siggi.morganabot.dashboard.ApiRouter.Requirement;
import io.siggi.morganabot.health.Component;
import io.siggi.morganabot.health.ComponentStatus;
import io.siggi.morganabot.health.Severity;
import io.siggi.morganabot.health.SystemHealth;
import io.siggi.morganabot.health.SystemLog;
import io.siggi.morganabot.health.SystemLogEntry;
import io.siggi.morganabot.problems.ServerProblems;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The super admin part of the dashboard API ({@code /dashboard/api/admin/…}). Every route is
 * {@link Requirement#SUPER_ADMIN}, so normal sessions get 403 before a handler runs. None of them needs Discord: when
 * it's down, the server list is empty and says so, and the System page works as usual.
 * <ul>
 *     <li>{@code GET /admin/guilds}: every server the bot is in, with the switcher's summary fields</li>
 *     <li>{@code GET /admin/system}: status cards, uptime, version and counts</li>
 *     <li>{@code GET /admin/system/log?component=&severity=&before=&limit=}: the system log, most recently seen first,
 *     paged by last-seen time</li>
 *     <li>{@code DELETE /admin/system/log/{id}}: dismisses one entry</li>
 *     <li>{@code POST /admin/guilds/{id}/leave} ({@code {confirmName, reason, block}}): removes the bot from a server,
 *     blocking it from adding the bot back unless {@code block} is false. Needs Discord.</li>
 *     <li>{@code GET /admin/blocked}: every blocked server, newest block first</li>
 *     <li>{@code DELETE /admin/blocked/{id}}: unblocks a server (doesn't add the bot back)</li>
 *     <li>{@code GET /admin/audit?guild=&type=&before=&limit=}: every server's audit entries merged (including
 *     super-admin-only ones and servers the bot has left), newest first, paged by time</li>
 * </ul>
 * Turning Twitch on or off for a server is {@code PATCH /guilds/{guildId}/settings} ({@link DashboardApi}).
 */
public final class AdminApi {
    public static final int LOG_PAGE = 100;
    public static final int LOG_MAX_PAGE = 500;
    public static final int AUDIT_PAGE = 100;
    public static final int AUDIT_MAX_PAGE = 500;

    /** How a card detail is shown: its label and what kind of value it is. */
    private record DetailInfo(String label, String kind) {
    }

    private static final String TIME = "time";
    private static final String NUMBER = "number";
    private static final String TEXT = "text";

    /** Known card details (set by the watcher and the Discord listener), in display order. Others are shown as text. */
    private static final Map<String, DetailInfo> DETAILS = new LinkedHashMap<>();

    static {
        DETAILS.put("gatewayPingMs", new DetailInfo("Gateway ping (ms)", NUMBER));
        DETAILS.put("lastDisconnect", new DetailInfo("Last disconnect", TIME));
        DETAILS.put("nextConnectAttempt", new DetailInfo("Next connection attempt", TIME));
        DETAILS.put("tokenExpiry", new DetailInfo("App token expires", TIME));
        DETAILS.put("lastSuccess", new DetailInfo("Last successful call", TIME));
        DETAILS.put("rateLimitReset", new DetailInfo("Rate limit resets", TIME));
        DETAILS.put("subscriptionCount", new DetailInfo("Subscriptions", NUMBER));
        DETAILS.put("disabledSubscriptions", new DetailInfo("Disabled subscriptions", NUMBER));
        DETAILS.put("totalCost", new DetailInfo("Subscription cost", NUMBER));
        DETAILS.put("maxTotalCost", new DetailInfo("Subscription cost limit", NUMBER));
        DETAILS.put("lastSync", new DetailInfo("Last subscription sync", TIME));
        DETAILS.put("nextSyncRetry", new DetailInfo("Next sync retry", TIME));
        DETAILS.put("lastNotification", new DetailInfo("Last notification received", TIME));
        DETAILS.put("signatureFailures", new DetailInfo("Signature check failures", NUMBER));
        DETAILS.put("lastSignatureFailure", new DetailInfo("Last signature failure", TIME));
    }

    private final ServerInfoStore store;
    private final ServerSettings settings;
    private final ServerProblems problems;
    private final GuildData.Provider guilds;
    private final Supplier<SystemHealth> health;
    private final AuditLog auditLog;

    public AdminApi(ServerInfoStore store, ServerSettings settings, ServerProblems problems, GuildData.Provider guilds,
                    Supplier<SystemHealth> health, AuditLog auditLog) {
        this.auditLog = auditLog;
        this.store = store;
        this.settings = settings;
        this.problems = problems;
        this.guilds = guilds;
        this.health = health;
    }

    public void register(ApiRouter api) {
        api.get("/admin/guilds", Requirement.SUPER_ADMIN, this::guilds);
        api.get("/admin/system", Requirement.SUPER_ADMIN, this::system);
        api.get("/admin/system/log", Requirement.SUPER_ADMIN, this::log);
        api.delete("/admin/system/log/{id}", Requirement.SUPER_ADMIN, this::dismissLog);
        api.post("/admin/guilds/{id}/leave", Requirement.SUPER_ADMIN, this::leave);
        api.get("/admin/blocked", Requirement.SUPER_ADMIN, this::blocked);
        api.delete("/admin/blocked/{id}", Requirement.SUPER_ADMIN, this::unblock);
        api.get("/admin/audit", Requirement.SUPER_ADMIN, this::audit);
    }

    // --- bot-wide audit log ---

    /**
     * {@code GET /admin/audit}: {@code {entries: [{guildId, guildName, entry}], nextBefore, servers: [{id, name,
     * inServer}]}}. {@code servers} lists every server with an audit file (for the filter), whatever the filters are.
     * Names come from Discord, then the name saved with a block, then the newest server name in its entries.
     */
    Object audit(ApiRequest r) throws ApiError {
        long guildFilter = longQuery(r, "guild", 0L);
        Set<String> types = new LinkedHashSet<>();
        String typeParam = r.query("type");
        if (typeParam != null) {
            for (String type : typeParam.split(",")) {
                if (!type.isBlank()) types.add(type.trim());
            }
        }
        long before = longQuery(r, "before", Long.MAX_VALUE);
        int limit = (int) Math.max(1L, Math.min(AUDIT_MAX_PAGE, longQuery(r, "limit", AUDIT_PAGE)));

        List<AuditLog.GuildEntry> all = auditLog.readAll(true);
        Map<Long, String> names = serverNames(auditLog.guildIds(), all);

        List<Map<String, Object>> page = new ArrayList<>();
        Long nextBefore = null;
        long lastTime = 0L;
        for (AuditLog.GuildEntry item : all) {
            AuditEntry entry = item.entry();
            if (guildFilter != 0L && item.guildId() != guildFilter) continue;
            if (entry.time >= before) continue;
            if (!types.isEmpty() && !types.contains(entry.type)) continue;
            if (page.size() >= limit) {
                // Entries with the same time as the last one stay on this page, so "before" can't skip any.
                if (entry.time == lastTime) {
                    page.add(auditItem(item, names));
                    continue;
                }
                nextBefore = lastTime;
                break;
            }
            page.add(auditItem(item, names));
            lastTime = entry.time;
        }
        List<Map<String, Object>> servers = new ArrayList<>();
        boolean discord = guilds.discordAvailable();
        for (Map.Entry<Long, String> server : names.entrySet()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", server.getKey());
            json.put("name", server.getValue());
            json.put("inServer", discord ? inServer(server.getKey()) : null);
            servers.add(json);
        }
        servers.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                a.get("name") == null ? "\uffff" + a.get("id") : String.valueOf(a.get("name")),
                b.get("name") == null ? "\uffff" + b.get("id") : String.valueOf(b.get("name"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", page);
        result.put("nextBefore", nextBefore);
        result.put("servers", servers);
        return result;
    }

    private static Map<String, Object> auditItem(AuditLog.GuildEntry item, Map<Long, String> names) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("guildId", item.guildId());
        json.put("guildName", names.get(item.guildId()));
        json.put("entry", item.entry());
        return json;
    }

    /** A name for each server ID (null if none is known), in ID order. */
    private Map<Long, String> serverNames(List<Long> ids, List<AuditLog.GuildEntry> newestFirst) {
        Map<Long, String> fromEntries = new HashMap<>();
        for (AuditLog.GuildEntry item : newestFirst) {
            AuditEntry.Value subject = item.entry().subject;
            if (subject != null && AuditEntry.Value.SERVER.equals(subject.kind) && subject.name != null) {
                fromEntries.putIfAbsent(item.guildId(), subject.name);
            }
        }
        boolean discord = guilds.discordAvailable();
        Set<Long> withFiles = new java.util.HashSet<>(store.guildIds());
        Map<Long, String> names = new LinkedHashMap<>();
        for (long id : ids) {
            String name = null;
            if (discord) {
                try {
                    GuildData guild = guilds.guild(id);
                    if (guild != null) name = guild.name();
                } catch (ApiError e) {
                    // Discord went away meanwhile; fall back to the files.
                }
            }
            if (name == null) {
                try {
                    if (withFiles.contains(id)) name = store.get(id).blockedName;
                } catch (RuntimeException e) {
                    // Unreadable file: no name from it.
                }
            }
            if (name == null) name = fromEntries.get(id);
            names.put(id, name);
        }
        return names;
    }

    // --- removing and blocking servers ---

    static final class LeaveBody {
        String confirmName;
        String reason;
        Boolean block;
    }

    Object leave(ApiRequest r) throws ApiError, IOException {
        long guildId = r.longParam("id");
        LeaveBody body = r.body(LeaveBody.class);
        GuildData guild = guilds.guild(guildId);
        if (guild == null) throw ApiError.notFound("The bot isn't in that server.");
        String typed = body.confirmName == null ? "" : body.confirmName.trim();
        if (typed.isEmpty()) throw ApiError.invalid("confirmName", "Type the server's name to confirm.");
        if (!typed.equals(guild.name().trim())) {
            throw ApiError.invalid("confirmName", "That isn't the server's name. Type it exactly as shown.");
        }
        if (body.reason != null && body.reason.trim().length() > ServerSettings.MAX_BLOCK_REASON) {
            throw ApiError.invalid("reason", "The reason is too long (at most " + ServerSettings.MAX_BLOCK_REASON + " characters).");
        }
        boolean block = body.block == null || body.block;
        ServerSettings.LeaveResult result;
        try {
            result = settings.leaveGuild(guildId, body.reason, block, r.userId());
        } catch (ServerSettings.SettingsException e) {
            throw new ApiError(502, "leave_failed", e.getMessage());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("left", true);
        json.put("name", result.guildName());
        json.put("blocked", result.blocked());
        return json;
    }

    Object blocked(ApiRequest r) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ServerSettings.BlockedServer server : settings.blockedServers()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", server.guildId());
            json.put("name", server.name());
            json.put("blockedAt", server.blockedAt() == 0L ? null : server.blockedAt());
            json.put("blockedBy", server.blockedBy() == 0L ? null : server.blockedBy());
            json.put("blockedByName", server.blockedByName());
            json.put("reason", server.reason());
            json.put("inServer", inServer(server.guildId()));
            list.add(json);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("discordAvailable", guilds.discordAvailable());
        result.put("blocked", list);
        return result;
    }

    /** Whether the bot is still in the server (it should have left), or null if Discord isn't connected. */
    private Boolean inServer(long guildId) {
        if (!guilds.discordAvailable()) return null;
        try {
            return guilds.guild(guildId) != null;
        } catch (ApiError e) {
            return null;
        }
    }

    Object unblock(ApiRequest r) throws ApiError {
        long guildId = r.longParam("id");
        if (settings.unblock(guildId, r.userId()) == null) throw ApiError.notFound("That server isn't blocked.");
        return null;
    }

    // --- servers ---

    Object guilds(ApiRequest r) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (GuildData guild : guilds.all()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", guild.id());
            json.put("name", guild.name());
            json.put("iconUrl", guild.iconUrl());
            json.put("memberCount", guild.memberCount());
            try {
                ServerInfo info = store.get(guild.id());
                json.put("twitchEnabled", info.enabledTwitchTracking);
                json.put("streamers", info.streamers.size());
            } catch (RuntimeException e) {
                // An unreadable server file: still list the server, so it can be opened and looked at.
                json.put("twitchEnabled", false);
                json.put("streamers", 0);
                json.put("error", "Couldn't read this server's settings file.");
            }
            int problemCount;
            try {
                problemCount = problems.list(guild.id()).size();
            } catch (RuntimeException e) {
                problemCount = 0;
            }
            json.put("problems", problemCount);
            list.add(json);
        }
        list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.get("name")), String.valueOf(b.get("name"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("discordAvailable", guilds.discordAvailable());
        result.put("guilds", list);
        return result;
    }

    // --- system health ---

    Object system(ApiRequest r) throws ApiError {
        SystemHealth h = requireHealth();
        SystemHealth.Snapshot snapshot = h.snapshot();
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Map.Entry<Component, ComponentStatus> entry : snapshot.components().entrySet()) {
            Component component = entry.getKey();
            ComponentStatus status = entry.getValue();
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", component.name());
            card.put("label", component.label());
            card.put("state", status.state().name());
            card.put("since", status.since());
            card.put("message", status.message());
            card.put("lastError", status.lastError());
            card.put("lastErrorTime", status.lastErrorTime() == 0L ? null : status.lastErrorTime());
            card.put("details", details(status.details()));
            cards.add(card);
        }
        Map<String, Object> general = snapshot.general();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("anyRed", snapshot.anyRed());
        result.put("discordAvailable", guilds.discordAvailable());
        result.put("components", cards);
        result.put("startedAt", general.get("startedAt"));
        Object uptime = general.get("uptimeMillis");
        // A number (not a string like other longs), since the page counts it up.
        result.put("uptimeSeconds", uptime instanceof Number n ? (double) (n.longValue() / 1000L) : null);
        result.put("version", general.get("version"));
        result.put("buildTime", general.get("buildTime"));
        result.put("servers", general.get("servers") instanceof Number n ? n.intValue() : null);
        result.put("streamersTracked", general.get("streamersTracked") instanceof Number n ? n.intValue() : null);
        Map<String, String> labels = new LinkedHashMap<>();
        for (Component component : Component.values()) labels.put(component.name(), component.label());
        result.put("componentLabels", labels);
        return result;
    }

    /** Card details as labelled values: times in epoch millis (strings), numbers, or text. */
    static List<Map<String, Object>> details(Map<String, Object> details) {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> remaining = new LinkedHashMap<>(details);
        for (Map.Entry<String, DetailInfo> known : DETAILS.entrySet()) {
            if (!remaining.containsKey(known.getKey())) continue;
            Object value = remaining.remove(known.getKey());
            list.add(detail(known.getKey(), known.getValue(), value));
        }
        for (Map.Entry<String, Object> other : remaining.entrySet()) {
            list.add(detail(other.getKey(), new DetailInfo(other.getKey(), TEXT), other.getValue()));
        }
        return list;
    }

    private static Map<String, Object> detail(String key, DetailInfo info, Object value) {
        String kind = info.kind();
        String text;
        if (kind.equals(TIME)) {
            Long millis = toLong(value);
            if (millis != null && key.equals("rateLimitReset")) millis *= 1000L; // Twitch sends epoch seconds
            if (millis == null) {
                kind = TEXT;
                text = String.valueOf(value);
            } else {
                text = Long.toString(millis);
            }
        } else if (kind.equals(NUMBER)) {
            Long number = toLong(value);
            if (number == null) kind = TEXT;
            text = number == null ? String.valueOf(value) : Long.toString(number);
        } else {
            text = String.valueOf(value);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("key", key);
        json.put("label", info.label());
        json.put("kind", kind);
        json.put("value", text);
        return json;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // --- system log ---

    Object log(ApiRequest r) throws ApiError {
        SystemLog log = requireHealth().getLog();
        String component = upper(r.query("component"));
        if (component != null) {
            try {
                Component.valueOf(component);
            } catch (IllegalArgumentException e) {
                throw ApiError.invalid("component", "Unknown component.");
            }
        }
        String severity = upper(r.query("severity"));
        if (severity != null) {
            try {
                Severity.valueOf(severity);
            } catch (IllegalArgumentException e) {
                throw ApiError.invalid("severity", "Unknown severity.");
            }
        }
        long before = longQuery(r, "before", Long.MAX_VALUE);
        int limit = (int) Math.max(1L, Math.min(LOG_MAX_PAGE, longQuery(r, "limit", LOG_PAGE)));
        List<SystemLogEntry> page = new ArrayList<>();
        Long nextBefore = null;
        int total = 0;
        if (log != null) {
            List<SystemLogEntry> all = log.list();
            total = all.size();
            for (SystemLogEntry entry : all) {
                if (component != null && !component.equals(entry.component)) continue;
                if (severity != null && !severity.equals(entry.severity)) continue;
                if (entry.lastSeen >= before) continue;
                if (page.size() >= limit) {
                    // Entries last seen at the same time as the last one stay on this page, so "before" can't skip any.
                    if (entry.lastSeen == page.get(page.size() - 1).lastSeen) {
                        page.add(entry);
                        continue;
                    }
                    nextBefore = page.get(page.size() - 1).lastSeen;
                    break;
                }
                page.add(entry);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", page);
        result.put("nextBefore", nextBefore);
        result.put("total", total);
        return result;
    }

    Object dismissLog(ApiRequest r) throws ApiError {
        SystemLog log = requireHealth().getLog();
        if (log == null || !log.dismiss(r.param("id"))) throw ApiError.notFound("No such log entry (already dismissed?)");
        return null;
    }

    // --- helpers ---

    private SystemHealth requireHealth() throws ApiError {
        SystemHealth h = health.get();
        if (h == null) throw new ApiError(503, "unavailable", "System health isn't running.");
        return h;
    }

    private static String upper(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static long longQuery(ApiRequest r, String name, long fallback) throws ApiError {
        String value = r.query(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw ApiError.invalid(name, "Invalid " + name + ".");
        }
    }
}

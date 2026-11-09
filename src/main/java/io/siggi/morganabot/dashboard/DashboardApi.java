package io.siggi.morganabot.dashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.morganabot.Access;
import io.siggi.morganabot.LiveMessage;
import io.siggi.morganabot.ServerSettings;
import io.siggi.morganabot.ServerSettings.SelfServiceChannel;
import io.siggi.morganabot.ServerSettings.SelfServiceMember;
import io.siggi.morganabot.ServerSettings.SettingsException;
import io.siggi.morganabot.ServerSettings.StreamerRef;
import io.siggi.morganabot.ServerSettings.TwitchUser;
import io.siggi.morganabot.audit.Actor;
import io.siggi.morganabot.audit.AuditEntry;
import io.siggi.morganabot.audit.AuditLog;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;
import io.siggi.morganabot.dashboard.ApiRouter.Requirement;
import io.siggi.morganabot.diagnose.DiagnoseResult;
import io.siggi.morganabot.health.Health;
import io.siggi.morganabot.problems.ServerProblems;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The dashboard's JSON API for streamers and server admins ({@code /dashboard/api/me} and
 * {@code /dashboard/api/guilds/{guildId}/…}). {@link ApiRouter} has already checked the session, CSRF and the access
 * level when a handler runs; the handlers check the input (every channel and role ID must belong to the server) and
 * pass it to {@link ServerSettings}, which validates, saves and audits.
 * <p>
 * Changes are attributed to {@link Actor#dashboard} (or {@link Actor#superAdmin} for super admin sessions); a
 * streamer's own entry changes to {@link Actor#selfService}. A {@link SettingsException} becomes a 400
 * {@code invalid} error with its message, or 409 {@code twitch_disabled} when Twitch is off for the server; a failed
 * Twitch lookup becomes 502 {@code twitch_unavailable}.
 * <p>
 * IDs (snowflakes and Twitch IDs) and times are sent as strings. Request bodies may send IDs as strings or numbers;
 * {@code null}, {@code ""} and {@code "0"} mean "none".
 */
public final class DashboardApi {

    public static final int AUDIT_PAGE = 50;
    public static final int AUDIT_MAX_PAGE = 200;
    public static final int MEMBER_SEARCH_LIMIT = 25;
    /** Upper limit for the notification cooldown (a week). */
    public static final int MAX_COOLDOWN_SECONDS = 7 * 24 * 60 * 60;
    public static final String TWITCH_TROUBLE = "Twitch is having problems, posts may be delayed";
    /** The variables a live message can use, without {@code ${…}}. */
    public static final List<String> VARIABLES = List.of("streamer_name", "game_name", "stream_name", "twitch_link", "discord_name");

    /**
     * Twitch users by ID, for display names and logins. In production, batched Helix lookups.
     */
    @FunctionalInterface
    public interface TwitchUsers {
        Map<String, TwitchUser> byIds(Collection<String> ids) throws IOException;

        TwitchUsers NONE = ids -> Map.of();
    }

    private final ServerInfoStore store;
    private final ServerSettings settings;
    private final Access access;
    private final AuditLog auditLog;
    private final ServerProblems problems;
    private final GuildData.Provider guilds;
    private final TwitchUsers twitch;
    private final BooleanSupplier twitchTrouble;

    /**
     * @param twitchTrouble whether the system's Twitch status is red (only a yes/no reaches servers)
     */
    public DashboardApi(ServerInfoStore store, ServerSettings settings, Access access, AuditLog auditLog,
                        ServerProblems problems, GuildData.Provider guilds, TwitchUsers twitch, BooleanSupplier twitchTrouble) {
        this.store = store;
        this.settings = settings;
        this.access = access;
        this.auditLog = auditLog;
        this.problems = problems;
        this.guilds = guilds;
        this.twitch = twitch == null ? TwitchUsers.NONE : twitch;
        this.twitchTrouble = twitchTrouble == null ? () -> false : twitchTrouble;
    }

    public void register(ApiRouter api) {
        api.get("/me", Requirement.SESSION, this::me);

        String g = "/guilds/{guildId}";
        api.get(g + "/me/streamer", Requirement.GUILD_STREAMER, this::ownStreamer);
        api.put(g + "/me/streamer/template", Requirement.GUILD_STREAMER, this::ownTemplate);
        api.put(g + "/me/streamer/twitch", Requirement.GUILD_STREAMER, this::ownTwitch);
        api.delete(g + "/me/streamer", Requirement.GUILD_STREAMER, this::ownRemove);

        api.get(g + "/admin-roles", Requirement.GUILD_ADMIN, this::adminRoles);
        api.put(g + "/admin-roles", Requirement.GUILD_ADMIN, this::setAdminRoles);
        api.get(g + "/message-rules", Requirement.GUILD_ADMIN, this::messageRules);
        api.put(g + "/message-rules", Requirement.GUILD_ADMIN, this::setMessageRules);

        api.get(g + "/settings", Requirement.GUILD_ADMIN, this::getSettings);
        api.patch(g + "/settings", Requirement.GUILD_ADMIN, this::patchSettings);

        api.get(g + "/channels", Requirement.GUILD_ADMIN, this::channels);
        api.get(g + "/roles", Requirement.GUILD_ADMIN, this::roles);
        api.get(g + "/members", Requirement.GUILD_ADMIN, this::members);

        api.get(g + "/streamers", Requirement.GUILD_ADMIN, this::streamers);
        api.post(g + "/streamers", Requirement.GUILD_ADMIN, this::addStreamer);
        api.patch(g + "/streamers/{twitchId}", Requirement.GUILD_ADMIN, this::patchStreamer);
        api.delete(g + "/streamers/{twitchId}", Requirement.GUILD_ADMIN, this::deleteStreamer);

        api.get(g + "/self-service", Requirement.GUILD_ADMIN, this::selfService);
        api.put(g + "/self-service", Requirement.GUILD_ADMIN, this::setSelfService);

        api.get(g + "/audit", Requirement.GUILD_ADMIN, this::audit);
        api.post(g + "/diagnose", Requirement.GUILD_ADMIN, this::diagnose);
        api.get(g + "/problems", Requirement.GUILD_ADMIN, this::problems);
        api.delete(g + "/problems/{id}", Requirement.GUILD_ADMIN, this::dismissProblem);
        api.delete(g + "/problems", Requirement.GUILD_ADMIN, this::dismissProblems);
    }

    // --- who's logged in ---

    Object me(ApiRequest r) {
        long userId = r.userId();
        boolean superAdmin = r.isSuperAdmin();
        List<Map<String, Object>> servers = new ArrayList<>();
        for (GuildData guild : guilds.all()) {
            Access.MemberView view = guild.memberView(userId);
            if (view == null) {
                if (!superAdmin) continue;
                view = Access.MemberView.outsider(guild.id(), userId);
            }
            Access.Level level = access.of(view, superAdmin);
            if (level == Access.Level.NONE) continue;
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("id", guild.id());
            server.put("name", guild.name());
            server.put("iconUrl", guild.iconUrl());
            server.put("access", level.name().toLowerCase(Locale.ROOT));
            servers.add(server);
        }
        servers.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.get("name")), String.valueOf(b.get("name"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("name", guilds.userName(userId));
        result.put("superAdmin", superAdmin);
        result.put("discordAvailable", guilds.discordAvailable());
        result.put("csrfToken", r.session().csrfToken());
        result.put("guilds", servers);
        return result;
    }

    // --- a streamer's own entry ---

    Object ownStreamer(ApiRequest r) throws ApiError {
        GuildData guild = guild(r);
        ServerInfo info = store.get(r.guildId());
        ServerInfo.Streamer own = ownEntry(info, r.userId());
        Access.MemberView view = r.memberView();
        boolean admin = r.isAdmin();
        Access.MentionPermissions allowed = admin ? Access.MentionPermissions.ALL : Access.allowedMentions(info, view.roles());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("twitchDisabledMessage", twitchDisabledMessage(info));
        result.put("admin", admin);
        if (own == null) {
            result.put("streamer", null);
        } else {
            Map<String, TwitchUser> users = lookUp(List.of(own.twitchId));
            result.put("streamer", streamerJson(guild, info, own, users.get(own.twitchId)));
        }
        result.put("serverTemplate", info.liveNotificationTemplate != null ? info.liveNotificationTemplate : LiveMessage.DEFAULT_TEMPLATE);
        result.put("canCustomizeMessage", admin || Access.hasRule(info, view.roles()));
        Map<String, Object> mentions = new LinkedHashMap<>();
        mentions.put("all", allowed.all());
        mentions.put("everyone", allowed.allowsEveryone());
        mentions.put("users", allowed.allowsUsers());
        List<Map<String, Object>> roles = new ArrayList<>();
        for (long roleId : allowed.roles()) roles.add(roleRef(guild, roleId));
        mentions.put("roles", roles);
        result.put("allowedMentions", mentions);

        SelfServiceMember member = guild.selfServiceMember(r.userId());
        SelfServiceChannel resolved = member == null || !member.present()
                ? SelfServiceChannel.NONE : ServerSettings.resolveSelfServiceChannel(info, member.rolesHighestFirst());
        Map<String, Object> selfService = new LinkedHashMap<>();
        selfService.put("eligible", resolved.eligible());
        selfService.put("role", resolved.eligible() ? roleRef(guild, resolved.roleId()) : null);
        selfService.put("channel", resolved.eligible() ? channelRef(guild, resolved.channelId() != 0L ? resolved.channelId() : info.channelToPostLiveNotifications) : null);
        selfService.put("serverDefault", resolved.eligible() && resolved.channelId() == 0L);
        result.put("selfService", selfService);
        result.put("variables", VARIABLES);
        result.put("maxLength", LiveMessage.MAX_LENGTH);
        return result;
    }

    Object ownTemplate(ApiRequest r) throws Exception {
        JsonObject body = r.body(JsonObject.class);
        allowOnly(body, "template");
        if (!body.has("template")) throw ApiError.invalid("template", "template is required (null goes back to the server-wide message)");
        String template = string(body, "template");
        Access.MemberView view = r.memberView();
        if (r.isSuperAdmin() && !view.administrator()) {
            // The session's super admin flag counts as admin, like everywhere else in the dashboard.
            view = new Access.MemberView(view.guildId(), view.userId(), true, view.roles());
        }
        Access.MemberView editor = view;
        apply(() -> settings.setOwnTemplate(selfActor(r), editor, template), "template");
        return ownStreamer(r);
    }

    Object ownTwitch(ApiRequest r) throws Exception {
        GuildData guild = guild(r);
        JsonObject body = r.body(JsonObject.class);
        allowOnly(body, "twitchName");
        String name = string(body, "twitchName");
        if (name == null || name.isBlank()) throw ApiError.invalid("twitchName", "Enter your Twitch name.");
        SelfServiceMember member = guild.selfServiceMember(r.userId());
        if (member == null) throw ApiError.discordUnavailable();
        if (!member.present()) throw ApiError.forbidden("You aren't a member of this server.");
        apply(() -> settings.selfSetTwitch(selfActor(r), member, name), "twitchName");
        return ownStreamer(r);
    }

    Object ownRemove(ApiRequest r) throws Exception {
        apply(() -> settings.selfRemove(selfActor(r), r.guildId(), r.userId()), null);
        return null;
    }

    // --- admin roles and message rules ---

    Object adminRoles(ApiRequest r) throws ApiError {
        GuildData guild = guild(r);
        ServerInfo info = store.get(r.guildId());
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Long roleId : info.adminRoles()) {
            if (roleId != null) roles.add(roleRef(guild, roleId));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roles", roles);
        boolean canEdit = canEditAdminRoles(r);
        result.put("canEdit", canEdit);
        result.put("readOnlyReason", canEdit ? null
                : "Only members with Discord's Administrator permission can change the admin roles.");
        return result;
    }

    Object setAdminRoles(ApiRequest r) throws Exception {
        if (!canEditAdminRoles(r)) {
            throw ApiError.forbidden("Only members with Discord's Administrator permission can change the admin roles.");
        }
        GuildData guild = guild(r);
        JsonObject body = r.body(JsonObject.class);
        allowOnly(body, "roles");
        List<Long> roles = new ArrayList<>();
        for (JsonElement element : array(body, "roles")) {
            long roleId = id(element, "roles");
            requireRole(guild, roleId, "roles", false);
            roles.add(roleId);
        }
        Access.MemberView view = r.memberView();
        Access.MemberView editor = new Access.MemberView(view.guildId(), view.userId(), true, view.roles());
        run(() -> settings.setAdminRoles(actor(r), editor, roles), "roles");
        return adminRoles(r);
    }

    private boolean canEditAdminRoles(ApiRequest r) {
        return r.isSuperAdmin() || access.canEditAdminRoles(r.memberView());
    }

    Object messageRules(ApiRequest r) throws ApiError {
        GuildData guild = guild(r);
        ServerInfo info = store.get(r.guildId());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (ServerInfo.MessageCustomizationRule rule : info.messageCustomizationRules()) {
            if (rule == null) continue;
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("role", roleRef(guild, rule.roleId));
            json.put("allowEveryone", rule.allowEveryone);
            List<Map<String, Object>> mentionRoles = new ArrayList<>();
            for (Long roleId : rule.allowedMentionRoles()) {
                if (roleId != null) mentionRoles.add(roleRef(guild, roleId));
            }
            json.put("allowedMentionRoles", mentionRoles);
            rules.add(json);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("twitchDisabledMessage", twitchDisabledMessage(info));
        result.put("rules", rules);
        return result;
    }

    Object setMessageRules(ApiRequest r) throws Exception {
        GuildData guild = guild(r);
        JsonObject body = r.body(JsonObject.class);
        allowOnly(body, "rules");
        List<ServerInfo.MessageCustomizationRule> rules = new ArrayList<>();
        for (JsonElement element : array(body, "rules")) {
            if (!element.isJsonObject()) throw ApiError.invalid("rules", "Each rule must be an object.");
            JsonObject rule = element.getAsJsonObject();
            long roleId = id(rule.get("roleId"), "rules");
            if (roleId == 0L) throw ApiError.invalid("rules", "Each rule needs a role.");
            requireRole(guild, roleId, "rules", true);
            boolean allowEveryone = bool(rule, "allowEveryone", "rules");
            List<Long> mentionRoles = new ArrayList<>();
            JsonElement list = rule.get("allowedMentionRoles");
            if (list != null && !list.isJsonNull()) {
                if (!list.isJsonArray()) throw ApiError.invalid("rules", "allowedMentionRoles must be a list.");
                for (JsonElement mention : list.getAsJsonArray()) {
                    long mentionRole = id(mention, "rules");
                    requireRole(guild, mentionRole, "rules", false);
                    mentionRoles.add(mentionRole);
                }
            }
            rules.add(new ServerInfo.MessageCustomizationRule(roleId, allowEveryone, mentionRoles));
        }
        run(() -> settings.setMessageRules(actor(r), r.guildId(), rules), "rules");
        return messageRules(r);
    }

    // --- server settings ---

    Object getSettings(ApiRequest r) throws ApiError {
        GuildData guild = guild(r);
        ServerInfo info = store.get(r.guildId());
        Map<String, Object> result = new LinkedHashMap<>();
        if (r.isSuperAdmin()) result.put("enabledTwitchTracking", info.enabledTwitchTracking);
        result.put("twitchDisabledMessage", twitchDisabledMessage(info));
        result.put("liveChannel", info.channelToPostLiveNotifications == 0L ? null : channelRef(guild, info.channelToPostLiveNotifications));
        result.put("liveRole", info.roleForLiveUsers == 0L ? null : roleRef(guild, info.roleForLiveUsers));
        result.put("deleteOnOffline", info.deleteNotificationsOnOffline);
        result.put("liveMessageTemplate", info.liveNotificationTemplate);
        result.put("defaultLiveMessageTemplate", LiveMessage.DEFAULT_TEMPLATE);
        result.put("notificationCooldownSeconds", (int) Math.min(Integer.MAX_VALUE, Math.max(0L, info.streamNotificationCooldown / 1000L)));
        result.put("variables", VARIABLES);
        result.put("maxLength", LiveMessage.MAX_LENGTH);
        return result;
    }

    Object patchSettings(ApiRequest r) throws Exception {
        GuildData guild = guild(r);
        JsonObject body = r.body(JsonObject.class);
        allowOnly(body, "liveChannelId", "liveRoleId", "deleteOnOffline", "liveMessageTemplate",
                "notificationCooldownSeconds", "enabledTwitchTracking");
        if (body.has("enabledTwitchTracking")) {
            if (!r.isSuperAdmin()) throw new ApiError(403, "forbidden", "Only bot admins can change this.", "enabledTwitchTracking");
            throw ApiError.invalid("enabledTwitchTracking", "Turning Twitch on or off from the dashboard isn't available yet.");
        }
        // Check everything first, so a bad value changes nothing.
        Long channelId = null, roleId = null;
        Integer cooldown = null;
        if (body.has("liveChannelId")) {
            channelId = id(body.get("liveChannelId"), "liveChannelId");
            requireChannel(guild, channelId, "liveChannelId");
        }
        if (body.has("liveRoleId")) {
            roleId = id(body.get("liveRoleId"), "liveRoleId");
            RoleCheck check = requireRole(guild, roleId, "liveRoleId", false);
            if (check != null && check.role.managed) {
                throw ApiError.invalid("liveRoleId", "That role belongs to a bot or integration, so it can't be given to streamers.");
            }
        }
        Boolean delete = body.has("deleteOnOffline") ? bool(body, "deleteOnOffline", "deleteOnOffline") : null;
        if (body.has("notificationCooldownSeconds")) {
            cooldown = integer(body, "notificationCooldownSeconds");
            if (cooldown < 0 || cooldown > MAX_COOLDOWN_SECONDS) {
                throw ApiError.invalid("notificationCooldownSeconds", "The cooldown must be between 0 and " + MAX_COOLDOWN_SECONDS + " seconds (a week).");
            }
        }
        String template = body.has("liveMessageTemplate") ? string(body, "liveMessageTemplate") : null;
        Actor actor = actor(r);
        long guildId = r.guildId();
        if (channelId != null) {
            long id = channelId;
            run(() -> settings.setLiveChannel(actor, guildId, id), "liveChannelId");
        }
        if (roleId != null) {
            long id = roleId;
            run(() -> settings.setLiveRole(actor, guildId, id), "liveRoleId");
        }
        if (delete != null) {
            boolean value = delete;
            run(() -> settings.setDeleteOnOffline(actor, guildId, value), "deleteOnOffline");
        }
        if (cooldown != null) {
            long millis = cooldown * 1000L;
            run(() -> settings.setNotificationCooldown(actor, guildId, millis), "notificationCooldownSeconds");
        }
        if (body.has("liveMessageTemplate")) {
            run(() -> settings.setServerTemplate(actor, guildId, template), "liveMessageTemplate");
        }
        return getSettings(r);
    }

    // --- channels, roles, members ---

    Object channels(ApiRequest r) throws ApiError {
        return Map.of("channels", guild(r).channels());
    }

    Object roles(ApiRequest r) throws ApiError {
        return Map.of("roles", guild(r).roles());
    }

    Object members(ApiRequest r) throws ApiError {
        GuildData guild = guild(r);
        String q = r.query("q");
        if (q == null || q.isBlank()) return Map.of("members", List.of());
        if (q.length() > 100) throw ApiError.invalid("q", "Search text is too long.");
        return Map.of("members", guild.searchMembers(q, MEMBER_SEARCH_LIMIT));
    }

    // --- streamers ---

    Object streamers(ApiRequest r) throws ApiError {
        GuildData guild = guild(r);
        ServerInfo info = store.get(r.guildId());
        Set<String> ids = new LinkedHashSet<>();
        for (ServerInfo.Streamer streamer : info.streamers) {
            if (streamer.twitchId != null) ids.add(streamer.twitchId);
        }
        Map<String, TwitchUser> users;
        boolean lookupFailed = false;
        try {
            users = ids.isEmpty() ? Map.of() : twitch.byIds(ids);
        } catch (Exception e) {
            users = Map.of();
            lookupFailed = true;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ServerInfo.Streamer streamer : info.streamers) {
            list.add(streamerJson(guild, info, streamer, users.get(streamer.twitchId)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("twitchDisabledMessage", twitchDisabledMessage(info));
        result.put("twitchLookupFailed", lookupFailed);
        result.put("streamers", list);
        return result;
    }

    Object addStreamer(ApiRequest r) throws Exception {
        GuildData guild = guild(r);
        JsonObject body = r.body(JsonObject.class);
        allowOnly(body, "twitchName", "discordUserId", "channelId");
        String name = string(body, "twitchName");
        if (name == null || name.isBlank()) throw ApiError.invalid("twitchName", "Enter the streamer's Twitch name.");
        long discordUserId = id(body.get("discordUserId"), "discordUserId");
        requireMember(guild, discordUserId, "discordUserId");
        long channelId = id(body.get("channelId"), "channelId");
        requireChannel(guild, channelId, "channelId");
        TwitchUser user = apply(() -> settings.addStreamer(actor(r), r.guildId(), name, discordUserId, channelId), "twitchName");
        ServerInfo info = store.get(r.guildId());
        ServerInfo.Streamer added = find(info, user.id());
        return new ApiRouter.Response(201, added == null ? null : streamerJson(guild, info, added, user));
    }

    Object patchStreamer(ApiRequest r) throws Exception {
        GuildData guild = guild(r);
        String twitchId = r.param("twitchId");
        ServerInfo.Streamer current = find(store.get(r.guildId()), twitchId);
        if (current == null) throw ApiError.notFound("No such streamer in this server");
        JsonObject body = r.body(JsonObject.class);
        allowOnly(body, "discordUserId", "channelId", "template", "channelOverridePinned");

        Long discordUserId = null, channelId = null;
        if (body.has("discordUserId")) {
            discordUserId = id(body.get("discordUserId"), "discordUserId");
            requireMember(guild, discordUserId, "discordUserId");
        }
        if (body.has("channelId")) {
            channelId = id(body.get("channelId"), "channelId");
            requireChannel(guild, channelId, "channelId");
        }
        Boolean pinned = body.has("channelOverridePinned") ? bool(body, "channelOverridePinned", "channelOverridePinned") : null;
        if (pinned != null) {
            if (!current.selfService) {
                throw ApiError.invalid("channelOverridePinned", "Only self-added streamers can follow their roles. This streamer was added by an admin.");
            }
            if (!pinned && channelId != null) {
                throw ApiError.invalid("channelOverridePinned", "Choose either a channel or going back to role-based, not both.");
            }
            // Pinning without a channel pins the current one.
            if (pinned && channelId == null) channelId = current.channelToPostLiveNotifications;
        }
        String template = body.has("template") ? string(body, "template") : null;

        Actor actor = actor(r);
        long guildId = r.guildId();
        StreamerRef ref = StreamerRef.byTwitchId(twitchId);
        if (discordUserId != null) {
            long id = discordUserId;
            apply(() -> settings.setStreamerDiscordUser(actor, guildId, ref, id), "discordUserId");
        }
        if (channelId != null) {
            long id = channelId;
            apply(() -> settings.setChannelOverride(actor, guildId, ref, id), "channelId");
        }
        if (Boolean.FALSE.equals(pinned)) {
            apply(() -> settings.unpinChannel(actor, guildId, ref), "channelOverridePinned");
        }
        if (body.has("template")) {
            apply(() -> settings.setStreamerTemplate(actor, guildId, ref, template), "template");
        }
        ServerInfo info = store.get(guildId);
        ServerInfo.Streamer updated = find(info, twitchId);
        if (updated == null) throw ApiError.notFound("No such streamer in this server");
        return streamerJson(guild, info, updated, lookUp(List.of(twitchId)).get(twitchId));
    }

    Object deleteStreamer(ApiRequest r) throws Exception {
        String twitchId = r.param("twitchId");
        if (find(store.get(r.guildId()), twitchId) == null) throw ApiError.notFound("No such streamer in this server");
        apply(() -> settings.deleteStreamer(actor(r), r.guildId(), StreamerRef.byTwitchId(twitchId)), null);
        return null;
    }

    // --- self-service ---

    Object selfService(ApiRequest r) throws ApiError {
        GuildData guild = guild(r);
        ServerInfo info = store.get(r.guildId());
        // Sorted by Discord's role order, highest first, so the precedence is visible; deleted roles last.
        Map<Long, Integer> order = new HashMap<>();
        List<GuildData.RoleInfo> guildRoles = guild.roles();
        for (int i = 0; i < guildRoles.size(); i++) order.put(guildRoles.get(i).id, i);
        List<ServerInfo.SelfServiceRole> roles = new ArrayList<>();
        for (ServerInfo.SelfServiceRole role : info.selfServiceRoles()) {
            if (role != null) roles.add(role);
        }
        roles.sort((a, b) -> Integer.compare(order.getOrDefault(a.roleId, Integer.MAX_VALUE), order.getOrDefault(b.roleId, Integer.MAX_VALUE)));
        List<Map<String, Object>> list = new ArrayList<>();
        for (ServerInfo.SelfServiceRole role : roles) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("role", roleRef(guild, role.roleId));
            json.put("channel", role.channelId == 0L ? null : channelRef(guild, role.channelId));
            list.add(json);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("twitchDisabledMessage", twitchDisabledMessage(info));
        result.put("roles", list);
        result.put("removeAfterDays", info.selfServiceRemoveAfterDays);
        result.put("maxRemoveAfterDays", ServerSettings.MAX_REMOVE_AFTER_DAYS);
        return result;
    }

    Object setSelfService(ApiRequest r) throws Exception {
        GuildData guild = guild(r);
        JsonObject body = r.body(JsonObject.class);
        allowOnly(body, "roles", "removeAfterDays");
        List<ServerInfo.SelfServiceRole> roles = new ArrayList<>();
        for (JsonElement element : array(body, "roles")) {
            if (!element.isJsonObject()) throw ApiError.invalid("roles", "Each entry must be an object.");
            JsonObject role = element.getAsJsonObject();
            long roleId = id(role.get("roleId"), "roles");
            if (roleId == 0L) throw ApiError.invalid("roles", "Each entry needs a role.");
            requireRole(guild, roleId, "roles", false);
            long channelId = id(role.get("channelId"), "roles");
            requireChannel(guild, channelId, "roles");
            roles.add(new ServerInfo.SelfServiceRole(roleId, channelId));
        }
        if (!body.has("removeAfterDays")) throw ApiError.invalid("removeAfterDays", "removeAfterDays is required (0 = never).");
        int days = integer(body, "removeAfterDays");
        if (days < 0 || days > ServerSettings.MAX_REMOVE_AFTER_DAYS) {
            throw ApiError.invalid("removeAfterDays", "The number of days must be between 0 (never) and " + ServerSettings.MAX_REMOVE_AFTER_DAYS + ".");
        }
        Actor actor = actor(r);
        run(() -> settings.setSelfServiceRemoveAfterDays(actor, r.guildId(), days), "removeAfterDays");
        run(() -> settings.setSelfServiceRoles(actor, r.guildId(), roles), "roles");
        return selfService(r);
    }

    // --- audit, Diagnose, problems ---

    Object audit(ApiRequest r) throws ApiError {
        Set<String> types = new LinkedHashSet<>();
        String typeParam = r.query("type");
        if (typeParam != null) {
            for (String type : typeParam.split(",")) {
                if (!type.isBlank()) types.add(type.trim());
            }
        }
        long before = longQuery(r, "before", Long.MAX_VALUE);
        long user = longQuery(r, "user", 0L);
        int limit = (int) Math.max(1L, Math.min(AUDIT_MAX_PAGE, longQuery(r, "limit", AUDIT_PAGE)));
        List<AuditEntry> page = new ArrayList<>();
        Long nextBefore = null;
        for (AuditEntry entry : auditLog.read(r.guildId(), r.isSuperAdmin())) {
            if (entry.time >= before) continue;
            if (!types.isEmpty() && !types.contains(entry.type)) continue;
            if (user != 0L && (entry.actor == null || !Long.toString(user).equals(entry.actor.userId))) continue;
            if (page.size() >= limit) {
                // Entries with the same time as the last one stay on this page, so "before" can't skip any.
                if (entry.time == page.get(page.size() - 1).time) {
                    page.add(entry);
                    continue;
                }
                nextBefore = page.get(page.size() - 1).time;
                break;
            }
            page.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", page);
        result.put("nextBefore", nextBefore);
        return result;
    }

    Object diagnose(ApiRequest r) throws ApiError {
        GuildData guild = guild(r);
        String streamer = r.query("streamer");
        if (streamer != null && find(store.get(r.guildId()), streamer) == null) {
            throw ApiError.notFound("No such streamer in this server");
        }
        return guild.diagnose(streamer);
    }

    Object problems(ApiRequest r) throws ApiError {
        GuildData guild = guild(r);
        DiagnoseResult diagnose;
        try {
            diagnose = guild.diagnose(null);
        } catch (RuntimeException e) {
            Health.unexpected("Diagnose failed for guild " + r.guildId(), e);
            diagnose = null;
        }
        boolean trouble = twitchTrouble.getAsBoolean();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("problems", problems.list(r.guildId()));
        result.put("diagnose", diagnose);
        result.put("twitchTrouble", trouble);
        result.put("twitchMessage", trouble ? TWITCH_TROUBLE : null);
        return result;
    }

    Object dismissProblem(ApiRequest r) throws ApiError {
        if (!problems.dismiss(r.guildId(), r.param("id"))) throw ApiError.notFound("No such problem (already dismissed?)");
        return null;
    }

    Object dismissProblems(ApiRequest r) {
        return Map.of("dismissed", problems.dismissAll(r.guildId()));
    }

    // --- JSON for entries ---

    private Map<String, Object> streamerJson(GuildData guild, ServerInfo info, ServerInfo.Streamer streamer, TwitchUser user) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("twitchId", streamer.twitchId);
        json.put("twitchLogin", user == null ? null : user.login());
        json.put("twitchDisplayName", user == null ? null : user.displayName());
        json.put("twitchUrl", user == null ? null : "https://www.twitch.tv/" + user.login());
        json.put("discordUserId", streamer.discordId == 0L ? null : streamer.discordId);
        json.put("discordUser", streamer.discordId == 0L ? null : guild.cachedMember(streamer.discordId));
        json.put("channelOverride", streamer.channelToPostLiveNotifications == 0L ? null : channelRef(guild, streamer.channelToPostLiveNotifications));
        long effective = streamer.channelToPostLiveNotifications != 0L ? streamer.channelToPostLiveNotifications : info.channelToPostLiveNotifications;
        json.put("channel", effective == 0L ? null : channelRef(guild, effective));
        json.put("channelSource", streamer.selfService && !streamer.channelOverridePinned ? "role"
                : streamer.channelToPostLiveNotifications != 0L ? "override" : "default");
        json.put("template", streamer.liveNotificationTemplate);
        json.put("templateSetBy", streamer.templateSetBy == 0L ? null : streamer.templateSetBy);
        json.put("live", streamer.startedStreaming != 0L);
        json.put("liveSince", streamer.startedStreaming == 0L ? null : streamer.startedStreaming);
        json.put("lastStreamEnded", streamer.stoppedStreaming == 0L ? null : streamer.stoppedStreaming);
        json.put("selfService", streamer.selfService);
        json.put("addedBy", streamer.addedBy == 0L ? null : streamer.addedBy);
        json.put("paused", streamer.isSelfServicePaused());
        json.put("pausedSince", streamer.isSelfServicePaused() ? streamer.selfServiceLostAt : null);
        json.put("channelOverridePinned", streamer.channelOverridePinned);
        return json;
    }

    private static Map<String, Object> roleRef(GuildData guild, long roleId) {
        GuildData.RoleInfo role = guild.role(roleId);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", roleId);
        json.put("name", role == null ? null : role.name);
        json.put("exists", role != null);
        return json;
    }

    private static Map<String, Object> channelRef(GuildData guild, long channelId) {
        GuildData.ChannelInfo channel = guild.channel(channelId);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", channelId);
        json.put("name", channel == null ? null : channel.name);
        json.put("exists", channel != null);
        json.put("botCanPost", channel != null && channel.botCanPost);
        return json;
    }

    // --- helpers ---

    private GuildData guild(ApiRequest r) throws ApiError {
        GuildData guild = guilds.guild(r.guildId());
        if (guild == null) throw ApiError.notFound("The bot isn't in this server");
        return guild;
    }

    private static ServerInfo.Streamer ownEntry(ServerInfo info, long userId) {
        for (ServerInfo.Streamer streamer : info.streamers) {
            if (streamer.discordId != 0L && streamer.discordId == userId) return streamer;
        }
        return null;
    }

    private static ServerInfo.Streamer find(ServerInfo info, String twitchId) {
        if (twitchId == null) return null;
        for (ServerInfo.Streamer streamer : info.streamers) {
            if (twitchId.equals(streamer.twitchId)) return streamer;
        }
        return null;
    }

    private Map<String, TwitchUser> lookUp(Collection<String> ids) {
        try {
            return twitch.byIds(ids);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String twitchDisabledMessage(ServerInfo info) {
        return info.enabledTwitchTracking ? null : settings.notEnabledMessage();
    }

    private String userName(ApiRequest r) {
        if (r.member() != null) return r.member().getUser().getName();
        String name = guilds.userName(r.userId());
        return name == null ? "unknown" : name;
    }

    /** Admin changes: from the dashboard, or as bot admin from a super admin session. */
    private Actor actor(ApiRequest r) {
        return r.isSuperAdmin() ? Actor.superAdmin(r.userId(), userName(r)) : Actor.dashboard(r.userId(), userName(r));
    }

    /** A member changing their own entry. */
    private Actor selfActor(ApiRequest r) {
        return r.isSuperAdmin() ? Actor.superAdmin(r.userId(), userName(r)) : Actor.selfService(r.userId(), userName(r), null);
    }

    @FunctionalInterface
    private interface SettingsCall<T> {
        T call() throws SettingsException, IOException;
    }

    @FunctionalInterface
    private interface SettingsAction {
        void call() throws SettingsException, IOException;
    }

    private <T> T apply(SettingsCall<T> call, String field) throws ApiError {
        try {
            return call.call();
        } catch (SettingsException e) {
            if (e.getMessage().equals(settings.notEnabledMessage())) {
                throw new ApiError(409, "twitch_disabled", e.getMessage());
            }
            throw ApiError.invalid(field, e.getMessage());
        } catch (IOException e) {
            throw new ApiError(502, "twitch_unavailable", "Couldn't reach Twitch right now. Try again in a minute.", field);
        }
    }

    private void run(SettingsAction action, String field) throws ApiError {
        apply(() -> {
            action.call();
            return null;
        }, field);
    }

    private static void requireChannel(GuildData guild, long channelId, String field) throws ApiError {
        if (channelId == 0L) return;
        if (guild.channel(channelId) != null) return;
        if (guild.hasChannel(channelId)) {
            throw ApiError.invalid(field, "That channel isn't a text or announcement channel.");
        }
        throw ApiError.invalid(field, "That channel isn't in this server.");
    }

    private record RoleCheck(GuildData.RoleInfo role) {
    }

    /**
     * @return the role, or null for 0 (none)
     */
    private static RoleCheck requireRole(GuildData guild, long roleId, String field, boolean allowEveryone) throws ApiError {
        if (roleId == 0L) return null;
        GuildData.RoleInfo role = guild.role(roleId);
        if (role == null) throw ApiError.invalid(field, "That role isn't in this server.");
        if (role.everyone && !allowEveryone) throw ApiError.invalid(field, "The @everyone role can't be used here.");
        return new RoleCheck(role);
    }

    private static void requireMember(GuildData guild, long userId, String field) throws ApiError {
        if (userId == 0L) return;
        if (guild.member(userId) == null) throw ApiError.invalid(field, "That user isn't a member of this server.");
    }

    private static void allowOnly(JsonObject body, String... fields) throws ApiError {
        Set<String> allowed = Set.of(fields);
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) throw ApiError.invalid(key, "Unknown field \"" + key + "\".");
        }
    }

    /**
     * An ID from a request: a string or a whole number; null, {@code ""} and {@code "0"} are 0 (none).
     */
    static long id(JsonElement element, String field) throws ApiError {
        if (element == null || element.isJsonNull()) return 0L;
        if (!element.isJsonPrimitive() || element.getAsJsonPrimitive().isBoolean()) {
            throw ApiError.invalid(field, "Invalid ID.");
        }
        String text = element.getAsString().trim();
        if (text.isEmpty()) return 0L;
        long id;
        try {
            id = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw ApiError.invalid(field, "Invalid ID.");
        }
        if (id < 0L) throw ApiError.invalid(field, "Invalid ID.");
        return id;
    }

    private static String string(JsonObject body, String field) throws ApiError {
        JsonElement element = body.get(field);
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw ApiError.invalid(field, field + " must be text.");
        }
        return element.getAsString();
    }

    private static boolean bool(JsonObject body, String name, String field) throws ApiError {
        JsonElement element = body.get(name);
        if (element == null || element.isJsonNull()) return false;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw ApiError.invalid(field, name + " must be true or false.");
        }
        return element.getAsBoolean();
    }

    private static int integer(JsonObject body, String field) throws ApiError {
        JsonElement element = body.get(field);
        if (element == null || !element.isJsonPrimitive()) throw ApiError.invalid(field, field + " must be a whole number.");
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) throw ApiError.invalid(field, field + " must be a whole number.");
        try {
            return Integer.parseInt(primitive.getAsString().trim());
        } catch (NumberFormatException e) {
            throw ApiError.invalid(field, field + " must be a whole number.");
        }
    }

    private static JsonArray array(JsonObject body, String field) throws ApiError {
        JsonElement element = body.get(field);
        if (element == null || !element.isJsonArray()) throw ApiError.invalid(field, field + " must be a list.");
        return element.getAsJsonArray();
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

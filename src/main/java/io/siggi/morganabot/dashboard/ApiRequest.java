package io.siggi.morganabot.dashboard;

import com.google.gson.JsonParseException;
import io.siggi.http.HTTPRequest;
import io.siggi.morganabot.Access;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * One API request after {@link ApiRouter} has checked it: the logged-in session, path parameters, and for guild routes
 * the guild, the member and their access level (worked out for this request).
 */
public final class ApiRequest {
    /** The largest JSON body accepted. */
    public static final int MAX_BODY = 64 * 1024;

    private final HTTPRequest http;
    private final DashboardSession session;
    private final Map<String, String> params;
    private final long guildId;
    private final GuildResolver.GuildMember guildMember;
    private final Access.Level level;

    ApiRequest(HTTPRequest http, DashboardSession session, Map<String, String> params, long guildId,
               GuildResolver.GuildMember guildMember, Access.Level level) {
        this.http = http;
        this.session = session;
        this.params = params;
        this.guildId = guildId;
        this.guildMember = guildMember;
        this.level = level;
    }

    public HTTPRequest http() {
        return http;
    }

    public DashboardSession session() {
        return session;
    }

    public long userId() {
        return session.userId();
    }

    public boolean isSuperAdmin() {
        return session.superAdmin();
    }

    /** A path parameter, e.g. {@code twitchId} for {@code /guilds/{guildId}/streamers/{twitchId}}. */
    public String param(String name) {
        return params.get(name);
    }

    /** A path parameter that must be a number; 404 if it isn't. */
    public long longParam(String name) throws ApiError {
        String value = params.get(name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException | NullPointerException e) {
            throw ApiError.notFound("Not found");
        }
    }

    /** A query string parameter, or null. */
    public String query(String name) {
        return http.get().get(name);
    }

    /** The guild ID for guild routes, 0 otherwise. */
    public long guildId() {
        return guildId;
    }

    /** The guild (guild routes only). */
    public Guild guild() {
        return guildMember == null ? null : guildMember.guild();
    }

    /** The user's member in the guild, or null if they aren't in it (a super admin elsewhere). */
    public Member member() {
        return guildMember == null ? null : guildMember.member();
    }

    public Access.MemberView memberView() {
        return guildMember == null ? null : guildMember.view();
    }

    /** Their access level in the guild, checked for this request (null outside guild routes). */
    public Access.Level level() {
        return level;
    }

    public boolean isAdmin() {
        return level == Access.Level.ADMIN;
    }

    /**
     * Reads the JSON body into {@code type}.
     *
     * @throws ApiError 415 if it isn't JSON, 413 if it's over {@link #MAX_BODY}, 400 if it's missing or malformed
     */
    public <T> T body(Class<T> type) throws ApiError, IOException {
        if (!ApiRouter.isJson(http.getHeader("Content-Type"))) throw ApiError.notJson();
        String text = readBody(http.getInputStream());
        if (text == null || text.isBlank()) throw ApiError.badRequest("Request body is missing");
        T value;
        try {
            value = WebResponses.GSON.fromJson(text, type);
        } catch (JsonParseException | NumberFormatException | IllegalStateException e) {
            throw ApiError.badRequest("Request body isn't valid JSON for this request");
        }
        if (value == null) throw ApiError.badRequest("Request body is missing");
        return value;
    }

    static String readBody(InputStream in) throws ApiError, IOException {
        if (in == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_BODY) throw ApiError.tooLarge();
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}

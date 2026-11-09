package io.siggi.morganabot.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.LongSerializationPolicy;
import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Security headers and JSON responses for the dashboard.
 * <p>
 * API responses: {@code Cache-Control: no-store}, {@code Referrer-Policy: no-referrer}, {@code X-Frame-Options: DENY},
 * {@code X-Content-Type-Options: nosniff} and a CSP that allows nothing ({@link #API_CSP}). Pages and static files get
 * the same, with {@link #PAGE_CSP} instead: only same-origin scripts, styles and API calls, no inline script, no
 * framing.
 */
public final class WebResponses {
    private WebResponses() {
    }

    /** For JSON: nothing may load or run. */
    public static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /**
     * For the dashboard's pages: scripts, styles and fetches from this origin only, images also from Discord's and
     * Twitch's CDNs (server icons, avatars, stream thumbnails). No inline scripts or styles.
     */
    public static final String PAGE_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
            + "img-src 'self' data: https://cdn.discordapp.com https://static-cdn.jtvnw.net; font-src 'self'; "
            + "manifest-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    /**
     * The API's JSON. Every {@code long} is written as a string, because Discord snowflakes don't fit in a JavaScript
     * number; strings are read back into {@code long} fields too. Use {@code int} or {@code double} for numbers the
     * frontend does maths on.
     */
    public static final Gson GSON = new GsonBuilder()
            .setLongSerializationPolicy(LongSerializationPolicy.STRING)
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private static void common(HTTPResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    /** Headers for every API response. */
    public static void apiHeaders(HTTPResponse response) {
        common(response);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", API_CSP);
    }

    /** Headers for dashboard pages and static files. {@code cacheControl} is e.g. {@code no-cache} or {@code no-store}. */
    public static void pageHeaders(HTTPResponse response, String cacheControl) {
        common(response);
        response.setHeader("Cache-Control", cacheControl);
        response.setHeader("Content-Security-Policy", PAGE_CSP);
    }

    public static String statusLine(int status) {
        return status + " " + switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 413 -> "Payload Too Large";
            case 415 -> "Unsupported Media Type";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }

    /** Sends {@code body} as JSON with the API headers. */
    public static void sendJson(HTTPRequest request, int status, Object body) throws IOException {
        sendJsonText(request, status, GSON.toJson(body));
    }

    public static void sendJsonText(HTTPRequest request, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        HTTPResponse response = request.response;
        response.setHeader(statusLine(status));
        apiHeaders(response);
        response.setContentType("application/json; charset=utf-8");
        response.contentLength(bytes.length);
        response.write(bytes);
    }

    /** The body of an error: {@code {"error": message, "code": code}} plus {@code "field"} when there is one. */
    public static JsonObject errorBody(ApiError error) {
        JsonObject body = new JsonObject();
        body.addProperty("error", error.getMessage());
        body.addProperty("code", error.code());
        if (error.field() != null) body.addProperty("field", error.field());
        return body;
    }

    public static void sendError(HTTPRequest request, ApiError error) throws IOException {
        sendJson(request, error.status(), errorBody(error));
    }

    /** {@code {"ok": true}}. */
    public static void sendOk(HTTPRequest request) throws IOException {
        sendJson(request, 200, Map.of("ok", true));
    }
}

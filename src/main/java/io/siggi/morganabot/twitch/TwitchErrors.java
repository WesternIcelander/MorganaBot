package io.siggi.morganabot.twitch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.morganabot.health.State;

import java.time.Instant;

/**
 * Readable text and card colours for Twitch HTTP failures.
 */
public final class TwitchErrors {
    private TwitchErrors() {
    }

    /** Pulls "message" out of a Twitch error body ({@code {"error":..,"status":..,"message":..}}), or null. */
    public static String messageFromBody(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                JsonElement message = object.get("message");
                if (message != null && message.isJsonPrimitive() && !message.getAsString().isBlank()) return message.getAsString();
                JsonElement error = object.get("error");
                if (error != null && error.isJsonPrimitive()) return error.getAsString();
            }
        } catch (Exception ignored) {
        }
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    /**
     * Describes a failed Helix call.
     *
     * @param what           what was being done, e.g. "list EventSub subscriptions"
     * @param rateLimitReset the {@code Ratelimit-Reset} header (epoch seconds), or null
     */
    public static String describeApi(String what, int code, String twitchMessage, String rateLimitReset) {
        String reason = switch (code) {
            case 401 -> "app token rejected";
            case 403 -> "request refused";
            case 409 -> "conflict";
            case 429 -> "rate limited" + resetText(rateLimitReset);
            default -> code >= 500 ? "Twitch server error" : "request failed";
        };
        return "Twitch API: could not " + what + " (HTTP " + code + ", " + reason + ")"
                + (twitchMessage == null ? "" : ": " + twitchMessage);
    }

    /** Card colour for a failed Helix call. */
    public static State apiState(int code) {
        return code == 401 ? State.RED : State.AMBER;
    }

    /** Whether an id.twitch.tv token response code means the client ID/secret is wrong. */
    public static boolean credentialsRejected(int code) {
        return code == 400 || code == 401 || code == 403;
    }

    public static String describeTokenFailure(int code, String twitchMessage) {
        if (credentialsRejected(code)) {
            return "Twitch rejected the client ID/secret (HTTP " + code
                    + (twitchMessage == null ? "" : ": " + twitchMessage)
                    + "). Fix twitch.clientId / twitch.clientSecret in config.json.";
        }
        return "Could not get a Twitch app token (HTTP " + code + (twitchMessage == null ? "" : ": " + twitchMessage) + ")";
    }

    private static String resetText(String rateLimitReset) {
        if (rateLimitReset == null) return "";
        try {
            return " until " + Instant.ofEpochSecond(Long.parseLong(rateLimitReset.trim()));
        } catch (Exception e) {
            return "";
        }
    }
}

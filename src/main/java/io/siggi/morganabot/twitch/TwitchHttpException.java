package io.siggi.morganabot.twitch;

import java.io.IOException;

/**
 * A Twitch request that got a non-2xx response.
 */
public class TwitchHttpException extends IOException {
    private final int responseCode;
    private final String twitchMessage;

    public TwitchHttpException(int responseCode, String twitchMessage, String description) {
        super(description);
        this.responseCode = responseCode;
        this.twitchMessage = twitchMessage;
    }

    public int getResponseCode() {
        return responseCode;
    }

    /** The "message" field from Twitch's error body, or null. */
    public String getTwitchMessage() {
        return twitchMessage;
    }
}

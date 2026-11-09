package io.siggi.morganabot.twitch;

import io.siggi.morganabot.health.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TwitchErrorsTest {

    @Test
    void messageFromBody() {
        assertEquals("Invalid OAuth token",
                TwitchErrors.messageFromBody("{\"error\":\"Unauthorized\",\"status\":401,\"message\":\"Invalid OAuth token\"}"));
        assertEquals("Unauthorized", TwitchErrors.messageFromBody("{\"error\":\"Unauthorized\",\"status\":401}"));
        assertEquals("plain text", TwitchErrors.messageFromBody("plain text"));
        assertNull(TwitchErrors.messageFromBody(null));
        assertNull(TwitchErrors.messageFromBody("  "));
        assertTrue(TwitchErrors.messageFromBody("x".repeat(500)).length() < 210);
    }

    @Test
    void describeApi() {
        assertEquals("Twitch API: could not get channel information (HTTP 429, rate limited until 2023-11-14T22:13:20Z)",
                TwitchErrors.describeApi("get channel information", 429, null, "1700000000"));
        assertEquals("Twitch API: could not get channel information (HTTP 429, rate limited)",
                TwitchErrors.describeApi("get channel information", 429, null, "garbage"));
        assertEquals("Twitch API: could not create an EventSub subscription (HTTP 403, request refused): subscription cost exceeded",
                TwitchErrors.describeApi("create an EventSub subscription", 403, "subscription cost exceeded", null));
        assertTrue(TwitchErrors.describeApi("x", 503, null, null).contains("Twitch server error"));
        assertTrue(TwitchErrors.describeApi("x", 401, null, null).contains("app token rejected"));
    }

    @Test
    void states() {
        assertEquals(State.RED, TwitchErrors.apiState(401));
        assertEquals(State.AMBER, TwitchErrors.apiState(403));
        assertEquals(State.AMBER, TwitchErrors.apiState(429));
        assertEquals(State.AMBER, TwitchErrors.apiState(500));
    }

    @Test
    void tokenFailures() {
        assertTrue(TwitchErrors.credentialsRejected(400));
        assertTrue(TwitchErrors.credentialsRejected(401));
        assertTrue(TwitchErrors.credentialsRejected(403));
        assertFalse(TwitchErrors.credentialsRejected(500));
        assertTrue(TwitchErrors.describeTokenFailure(403, "invalid client secret").contains("twitch.clientSecret"));
        assertFalse(TwitchErrors.describeTokenFailure(503, null).contains("config.json"));
    }
}

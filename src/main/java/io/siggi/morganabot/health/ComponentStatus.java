package io.siggi.morganabot.health;

import java.util.Map;

/**
 * Snapshot of one status card.
 *
 * @param state         current colour
 * @param since         when the component entered this state (epoch millis)
 * @param message       what the card says, e.g. "Connected" or "Discord rejected the bot token ..."
 * @param lastError     the latest error or warning logged for this component, or null
 * @param lastErrorTime when {@code lastError} happened (epoch millis), or 0
 * @param details       extra live values (gateway ping, token expiry, subscription count, ...), read-only
 */
public record ComponentStatus(State state, long since, String message, String lastError, long lastErrorTime,
                              Map<String, Object> details) {
}

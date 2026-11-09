package io.siggi.morganabot.health;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.GatewayPingEvent;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;

import java.util.function.BooleanSupplier;

/**
 * Turns JDA connection events into the Discord status card.
 * <p>
 * A fatal close code (invalid token, disallowed intents) sticks: later status changes (JDA shutting itself down)
 * don't replace that message, and {@link #fatalCloseCode()} tells the connector not to retry.
 */
public class DiscordHealthListener extends ListenerAdapter {
    /** A disconnect lasting longer than this turns the card red. */
    public static final long LONG_DISCONNECT = 5L * 60L * 1000L;

    private final SystemHealth health;
    private final BooleanSupplier stopping;
    private volatile CloseCode fatalCloseCode;
    /** Whether the current connection attempt ever reached CONNECTED. */
    private volatile boolean connectedOnce;

    public DiscordHealthListener(SystemHealth health, BooleanSupplier stopping) {
        this.health = health;
        this.stopping = stopping;
    }

    /** The close code that made Discord unusable until a restart, or null. */
    public CloseCode fatalCloseCode() {
        return fatalCloseCode;
    }

    /** Called by the connector before each new connection attempt. */
    public void reset() {
        fatalCloseCode = null;
        connectedOnce = false;
    }

    /** How a JDA status or close code shows on the card. */
    public record Mapping(State state, String message, boolean fatal, boolean disconnected) {
    }

    /**
     * @return the mapping, or null for shutdown and login failure: those are reported with a reason by the close
     * code, the shutdown event or the connector, and a bare "Shut down" would get to the alert first
     */
    public static Mapping forStatus(JDA.Status status) {
        return switch (status) {
            case CONNECTED -> new Mapping(State.GREEN, "Connected", false, false);
            case INITIALIZING, INITIALIZED, LOGGING_IN, CONNECTING_TO_WEBSOCKET, IDENTIFYING_SESSION,
                 AWAITING_LOGIN_CONFIRMATION, LOADING_SUBSYSTEMS -> new Mapping(State.AMBER, "Connecting", false, true);
            case DISCONNECTED, RECONNECT_QUEUED, WAITING_TO_RECONNECT, ATTEMPTING_TO_RECONNECT ->
                    new Mapping(State.AMBER, "Reconnecting", false, true);
            case SHUTTING_DOWN, SHUTDOWN, FAILED_TO_LOGIN -> null;
        };
    }

    /**
     * @return the mapping for a gateway close code, or null if it's an ordinary reconnect that needs no special text
     */
    public static Mapping forCloseCode(CloseCode code) {
        if (code == null) return null;
        return switch (code) {
            case AUTHENTICATION_FAILED -> new Mapping(State.RED,
                    "Discord rejected the bot token (close code 4004). Fix discord.token in config.json and restart.", true, true);
            case DISALLOWED_INTENTS -> new Mapping(State.RED,
                    "Privileged intents aren't enabled for this bot in the Discord developer portal (close code 4014). Enable them and restart.", true, true);
            case INVALID_INTENTS -> new Mapping(State.RED,
                    "Discord says the requested gateway intents are invalid (close code 4013). Fix and restart.", true, true);
            case RATE_LIMITED -> new Mapping(State.AMBER,
                    "Rate limited by the Discord gateway (close code 4008), reconnecting", false, true);
            case RECONNECT, GRACEFUL_CLOSE -> null;
            default -> code.isReconnect()
                    ? new Mapping(State.AMBER, "Disconnected: " + code.getMeaning() + " (close code " + code.getCode() + "), reconnecting", false, true)
                    : new Mapping(State.RED, "Disconnected: " + code.getMeaning() + " (close code " + code.getCode() + ")", true, true);
        };
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        onStatus(event.getNewStatus());
    }

    void onStatus(JDA.Status status) {
        // After a fatal close code JDA shuts itself down; keep the more useful "fix and restart" message.
        if (stopping.getAsBoolean() || fatalCloseCode != null) return;
        if (status == JDA.Status.CONNECTED) connectedOnce = true;
        Mapping mapping = forStatus(status);
        if (mapping != null) apply(mapping, false);
    }

    @Override
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        if (stopping.getAsBoolean()) return;
        health.detail(Component.DISCORD, "lastDisconnect", System.currentTimeMillis());
        handleCloseCode(event.getCloseCode());
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        if (stopping.getAsBoolean()) return;
        CloseCode code = event.getCloseCode();
        if (code == null && event.getCode() != 1000) {
            code = CloseCode.from(event.getCode());
        }
        onShutdown(code, event.getCode());
    }

    void onShutdown(CloseCode code, int rawCode) {
        if (stopping.getAsBoolean() || fatalCloseCode != null) return;
        Mapping mapping = forCloseCode(code);
        if (mapping != null) {
            handleCloseCode(code);
        } else if (connectedOnce) {
            // JDA gave up on a working connection without a known reason. Before the first connect, the connector
            // reports the failure instead.
            fatalCloseCode = code;
            apply(new Mapping(State.RED, "The Discord connection shut down unexpectedly (code " + rawCode + "). Restart the bot.", true, true), true);
        }
    }

    @Override
    public void onGatewayPing(GatewayPingEvent event) {
        health.detail(Component.DISCORD, "gatewayPingMs", event.getNewPing());
    }

    void handleCloseCode(CloseCode code) {
        if (stopping.getAsBoolean()) return;
        Mapping mapping = forCloseCode(code);
        if (mapping == null) return;
        if (mapping.fatal()) fatalCloseCode = code;
        apply(mapping, true);
    }

    private void apply(Mapping mapping, boolean log) {
        long escalateAfter = mapping.state() == State.AMBER && mapping.disconnected() ? LONG_DISCONNECT : 0L;
        String escalateMessage = "Disconnected from Discord for more than " + (LONG_DISCONNECT / 60000L) + " minutes";
        if (log) {
            health.problem(Component.DISCORD, mapping.state(), mapping.message(), null, escalateAfter, escalateMessage);
        } else {
            health.setState(Component.DISCORD, mapping.state(), mapping.message(), escalateAfter, escalateMessage);
        }
    }
}

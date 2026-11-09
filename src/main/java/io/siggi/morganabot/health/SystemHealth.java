package io.siggi.morganabot.health;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Live status of the bot as a whole: one card per {@link Component} (green, amber or red, since when, latest error)
 * plus the persistent {@link SystemLog}.
 * <p>
 * <b>Alerting:</b> when a card turns red, or stops being red, the {@link Alerter} is called. At most one alert per
 * component is sent per {@link #ALERT_INTERVAL}; a change inside that window is sent by {@link #tick()} once the window
 * has passed, if the component is still in the changed state (so a quick flap sends nothing extra).
 * <p>
 * <b>Escalation:</b> an amber state can be given a deadline (e.g. "reconnecting" for more than 5 minutes); {@link #tick()}
 * turns it red once the deadline passes, and it stays red until the component reports a different state.
 * <p>
 * Thread-safe. The log is never written while this object's lock is held, so the log may call back into
 * {@link #setState} (for its own write failures) without deadlocking.
 */
public class SystemHealth {
    public static final long ALERT_INTERVAL = 15L * 60L * 1000L;

    /** Called when a component turns red or recovers. */
    public interface Alerter {
        void alert(Component component, ComponentStatus status, boolean recovered);
    }

    private static final class Card {
        State state = State.UNKNOWN;
        long since;
        String message = "Not checked yet";
        String lastError;
        long lastErrorTime;
        final Map<String, Object> details = new LinkedHashMap<>();
        /** Turn red at this time if still amber; 0 = never. */
        long escalateAt;
        String escalateMessage;
        boolean escalated;
        /** Whether the last alert sent was a "red" one. */
        boolean alertedRed;
        long lastAlertTime = Long.MIN_VALUE / 2;
    }

    private record PendingAlert(Component component, ComponentStatus status, boolean recovered) {
    }

    private final SystemLog log;
    private final LongSupplier clock;
    private final Map<Component, Card> cards = new EnumMap<>(Component.class);
    private final ConcurrentHashMap<String, Boolean> failingFiles = new ConcurrentHashMap<>();
    private volatile Alerter alerter;
    private volatile Supplier<Map<String, Object>> generalInfo = Collections::emptyMap;
    private ScheduledExecutorService scheduler;

    public SystemHealth(SystemLog log) {
        this(log, System::currentTimeMillis);
    }

    public SystemHealth(SystemLog log, LongSupplier clock) {
        this.log = log;
        this.clock = clock;
        long now = clock.getAsLong();
        for (Component component : Component.values()) {
            if (!component.hasCard()) continue;
            Card card = new Card();
            card.since = now;
            cards.put(component, card);
        }
        Card storage = cards.get(Component.STORAGE);
        storage.state = State.GREEN;
        storage.message = "No failed saves";
        if (log != null) {
            log.setWriteFailureListener(e -> setState(Component.STORAGE, State.AMBER,
                    "Could not write the system log: " + e.getMessage()));
        }
    }

    public SystemLog getLog() {
        return log;
    }

    public void setAlerter(Alerter alerter) {
        this.alerter = alerter;
    }

    /**
     * Supplies the "General" values (uptime, version, server count, ...) for {@link #snapshot()}.
     */
    public void setGeneralInfo(Supplier<Map<String, Object>> generalInfo) {
        this.generalInfo = generalInfo == null ? Collections::emptyMap : generalInfo;
    }

    /**
     * Runs {@link #tick()} every 30 seconds on a daemon thread.
     */
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "System health");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
    }

    // --- status ---

    public void setState(Component component, State state, String message) {
        setState(component, state, message, 0L, null);
    }

    /**
     * Sets a card's state. {@code since} only moves when the colour changes.
     *
     * @param escalateAfter   for an amber state: turn red if still amber after this many millis (0 = never)
     * @param escalateMessage the message shown once escalated
     */
    public void setState(Component component, State state, String message, long escalateAfter, String escalateMessage) {
        PendingAlert alert;
        synchronized (this) {
            Card card = card(component);
            long now = clock.getAsLong();
            if (card.escalated && card.state == State.RED && state == State.AMBER && escalateAfter > 0L) {
                // Still the same long-running problem: stay red, but show the latest detail.
                card.message = card.escalateMessage + " (" + message + ")";
                return;
            }
            if (card.state != state) {
                card.state = state;
                card.since = now;
                card.escalated = false;
                card.escalateAt = 0L;
            }
            card.message = message;
            if (state == State.AMBER && escalateAfter > 0L) {
                // Keep an existing deadline: the problem has been going on since then.
                if (card.escalateAt == 0L) card.escalateAt = now + escalateAfter;
                card.escalateMessage = escalateMessage == null ? message : escalateMessage;
            } else {
                card.escalateAt = 0L;
            }
            alert = checkAlert(component, card, now);
        }
        send(alert);
    }

    /**
     * Sets a detail value on a card (null removes it).
     */
    public synchronized void detail(Component component, String key, Object value) {
        Card card = card(component);
        if (value == null) {
            card.details.remove(key);
        } else {
            card.details.put(key, value);
        }
    }

    public synchronized ComponentStatus status(Component component) {
        Card card = card(component);
        return new ComponentStatus(card.state, card.since, card.message, card.lastError, card.lastErrorTime,
                Collections.unmodifiableMap(new LinkedHashMap<>(card.details)));
    }

    public synchronized Map<Component, ComponentStatus> statuses() {
        Map<Component, ComponentStatus> map = new EnumMap<>(Component.class);
        for (Component component : cards.keySet()) map.put(component, status(component));
        return map;
    }

    /**
     * For the red dot on "System": whether any card is red.
     */
    public synchronized boolean anyRed() {
        for (Card card : cards.values()) {
            if (card.state == State.RED) return true;
        }
        return false;
    }

    public record Snapshot(Map<Component, ComponentStatus> components, Map<String, Object> general, boolean anyRed) {
    }

    public Snapshot snapshot() {
        Map<String, Object> general;
        try {
            general = new LinkedHashMap<>(generalInfo.get());
        } catch (Exception e) {
            general = new LinkedHashMap<>();
            general.put("error", String.valueOf(e));
        }
        synchronized (this) {
            return new Snapshot(statuses(), Collections.unmodifiableMap(general), anyRed());
        }
    }

    // --- logging ---

    /**
     * Writes a system log entry (and prints it). For components with a card, it also becomes the card's
     * "latest error". Doesn't change the card's colour.
     */
    public SystemLogEntry log(Component component, Severity severity, String message, Throwable error) {
        System.err.println("[" + severity + "] " + component.label() + ": " + message);
        if (error != null) error.printStackTrace();
        if (component.hasCard()) {
            synchronized (this) {
                Card card = card(component);
                card.lastError = message;
                card.lastErrorTime = clock.getAsLong();
            }
        }
        if (log == null) return null;
        try {
            return log.record(component, severity, message, error == null ? null : SystemLog.stackTrace(error),
                    clock.getAsLong());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Sets the card's state and logs the message: as an error if the state is red, otherwise as a warning.
     */
    public void problem(Component component, State state, String message, Throwable error) {
        problem(component, state, message, error, 0L, null);
    }

    public void problem(Component component, State state, String message, Throwable error, long escalateAfter, String escalateMessage) {
        setState(component, state, message, escalateAfter, escalateMessage);
        log(component, state == State.RED ? Severity.ERROR : Severity.WARNING, message, error);
    }

    // --- storage ---

    /**
     * A file was saved. Clears the Storage card once every file that failed has been saved again.
     */
    public void saved(File file) {
        if (failingFiles.isEmpty()) return;
        if (failingFiles.remove(file.getPath()) == null) return;
        if (failingFiles.isEmpty()) {
            setState(Component.STORAGE, State.GREEN, "Saves working again");
        }
    }

    /**
     * A file couldn't be saved: the Storage card turns red until it's saved successfully.
     */
    public void saveFailed(File file, Throwable error) {
        failingFiles.put(file.getPath(), Boolean.TRUE);
        String reason = error == null ? "unknown error" : (error.getMessage() == null ? error.toString() : error.getMessage());
        problem(Component.STORAGE, State.RED, "Could not save " + file.getPath() + ": " + reason, error);
    }

    // --- alerting ---

    /**
     * Escalates overdue amber states and sends alerts that were held back by the rate limit.
     */
    public void tick() {
        List<PendingAlert> alerts = new ArrayList<>();
        List<String[]> escalations = new ArrayList<>();
        synchronized (this) {
            long now = clock.getAsLong();
            for (Map.Entry<Component, Card> entry : cards.entrySet()) {
                Card card = entry.getValue();
                if (card.state == State.AMBER && card.escalateAt != 0L && now >= card.escalateAt) {
                    card.state = State.RED;
                    card.since = now;
                    card.escalated = true;
                    card.escalateAt = 0L;
                    card.message = card.escalateMessage;
                    escalations.add(new String[]{entry.getKey().name(), card.message});
                }
                PendingAlert alert = checkAlert(entry.getKey(), card, now);
                if (alert != null) alerts.add(alert);
            }
        }
        for (String[] escalation : escalations) {
            Component component = Component.valueOf(escalation[0]);
            log(component, Severity.ERROR, escalation[1], null);
        }
        for (PendingAlert alert : alerts) send(alert);
    }

    /** Caller holds the lock. */
    private PendingAlert checkAlert(Component component, Card card, long now) {
        boolean red = card.state == State.RED;
        if (red == card.alertedRed) return null;
        if (now - card.lastAlertTime < ALERT_INTERVAL) return null; // tick() sends it later if still changed
        card.alertedRed = red;
        card.lastAlertTime = now;
        return new PendingAlert(component, status(component), !red);
    }

    private void send(PendingAlert alert) {
        if (alert == null) return;
        Alerter a = alerter;
        String text = (alert.recovered() ? "RECOVERED " : "RED ") + alert.component().label() + ": " + alert.status().message();
        if (a == null) {
            System.out.println("[ALERT] " + text);
            return;
        }
        try {
            a.alert(alert.component(), alert.status(), alert.recovered());
        } catch (Exception e) {
            System.out.println("[ALERT] " + text);
            e.printStackTrace();
        }
    }

    private Card card(Component component) {
        Card card = cards.get(component);
        if (card == null) throw new IllegalArgumentException(component + " has no status card");
        return card;
    }
}

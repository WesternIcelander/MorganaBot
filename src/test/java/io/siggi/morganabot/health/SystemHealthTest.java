package io.siggi.morganabot.health;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SystemHealthTest {

    @TempDir
    Path tempDir;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<String> alerts = new ArrayList<>();

    private SystemHealth health() {
        SystemLog log = new SystemLog(tempDir.resolve("system-log.jsonl").toFile(), new Gson());
        SystemHealth health = new SystemHealth(log, now::get);
        health.setAlerter((component, status, recovered) ->
                alerts.add(component + (recovered ? " recovered" : " red") + ": " + status.message()));
        return health;
    }

    private static final long MIN = 60_000L;

    @Test
    void initialState() {
        SystemHealth health = health();
        assertEquals(State.UNKNOWN, health.status(Component.DISCORD).state());
        assertEquals(State.UNKNOWN, health.status(Component.TWITCH_API).state());
        assertEquals(State.UNKNOWN, health.status(Component.EVENTSUB).state());
        assertEquals(State.GREEN, health.status(Component.STORAGE).state());
        assertFalse(health.anyRed());
        assertThrows(IllegalArgumentException.class, () -> health.status(Component.BOT));
    }

    @Test
    void sinceOnlyMovesWhenColourChanges() {
        SystemHealth health = health();
        health.setState(Component.DISCORD, State.AMBER, "Connecting");
        long since = health.status(Component.DISCORD).since();
        now.addAndGet(MIN);
        health.setState(Component.DISCORD, State.AMBER, "Reconnecting");
        assertEquals(since, health.status(Component.DISCORD).since());
        assertEquals("Reconnecting", health.status(Component.DISCORD).message());
        now.addAndGet(MIN);
        health.setState(Component.DISCORD, State.GREEN, "Connected");
        assertEquals(now.get(), health.status(Component.DISCORD).since());
    }

    @Test
    void alertsOnRedAndRecovery() {
        SystemHealth health = health();
        health.setState(Component.TWITCH_API, State.AMBER, "slow");
        assertTrue(alerts.isEmpty(), "amber doesn't alert");
        health.setState(Component.TWITCH_API, State.RED, "token rejected");
        assertEquals(List.of("TWITCH_API red: token rejected"), alerts);
        assertTrue(health.anyRed());
        assertTrue(health.snapshot().anyRed());

        now.addAndGet(20 * MIN);
        health.setState(Component.TWITCH_API, State.GREEN, "OK");
        assertEquals(List.of("TWITCH_API red: token rejected", "TWITCH_API recovered: OK"), alerts);
        assertFalse(health.anyRed());
    }

    @Test
    void recoveryWithoutEarlierAlertSendsNothing() {
        SystemHealth health = health();
        health.setState(Component.EVENTSUB, State.AMBER, "x");
        health.setState(Component.EVENTSUB, State.GREEN, "ok");
        assertTrue(alerts.isEmpty());
    }

    @Test
    void alertsAreRateLimitedPerComponent() {
        SystemHealth health = health();
        health.setState(Component.DISCORD, State.RED, "down");
        now.addAndGet(MIN);
        health.setState(Component.DISCORD, State.GREEN, "up");
        assertEquals(1, alerts.size(), "recovery within 15 minutes is held back");

        // Other components aren't affected.
        health.setState(Component.STORAGE, State.RED, "disk");
        assertEquals(2, alerts.size());

        // Once the window has passed, tick() sends the held-back recovery.
        now.addAndGet(5 * MIN);
        health.tick();
        assertEquals(2, alerts.size());
        now.addAndGet(10 * MIN);
        health.tick();
        assertEquals(3, alerts.size());
        assertEquals("DISCORD recovered: up", alerts.get(2));
        health.tick();
        assertEquals(3, alerts.size(), "sent once");
    }

    @Test
    void quickFlapSendsNothingExtra() {
        SystemHealth health = health();
        health.setState(Component.DISCORD, State.RED, "down");
        now.addAndGet(MIN);
        health.setState(Component.DISCORD, State.GREEN, "up");
        now.addAndGet(MIN);
        health.setState(Component.DISCORD, State.RED, "down again");
        now.addAndGet(20 * MIN);
        health.tick();
        assertEquals(1, alerts.size(), "still red, as last alerted");
    }

    @Test
    void amberEscalatesToRedAndStaysRed() {
        SystemHealth health = health();
        health.setState(Component.DISCORD, State.AMBER, "Reconnecting", 5 * MIN, "Disconnected for more than 5 minutes");
        now.addAndGet(4 * MIN);
        health.tick();
        assertEquals(State.AMBER, health.status(Component.DISCORD).state());
        // Another amber update keeps the original deadline.
        health.setState(Component.DISCORD, State.AMBER, "Attempting to reconnect", 5 * MIN, "Disconnected for more than 5 minutes");
        now.addAndGet(2 * MIN);
        health.tick();
        ComponentStatus status = health.status(Component.DISCORD);
        assertEquals(State.RED, status.state());
        assertEquals("Disconnected for more than 5 minutes", status.message());
        assertEquals(List.of("DISCORD red: Disconnected for more than 5 minutes"), alerts);
        assertEquals("Disconnected for more than 5 minutes", health.getLog().list().get(0).message);

        // Further reconnect attempts don't turn it amber again.
        health.setState(Component.DISCORD, State.AMBER, "Reconnecting", 5 * MIN, "Disconnected for more than 5 minutes");
        assertEquals(State.RED, health.status(Component.DISCORD).state());
        assertTrue(health.status(Component.DISCORD).message().contains("Reconnecting"));

        now.addAndGet(20 * MIN);
        health.setState(Component.DISCORD, State.GREEN, "Connected");
        assertEquals(State.GREEN, health.status(Component.DISCORD).state());
        assertEquals("DISCORD recovered: Connected", alerts.get(1));
    }

    @Test
    void amberWithoutDeadlineClearsEscalation() {
        SystemHealth health = health();
        health.setState(Component.EVENTSUB, State.AMBER, "sync failing", 5 * MIN, "failing a long time");
        health.setState(Component.EVENTSUB, State.AMBER, "1 subscription change failed");
        now.addAndGet(10 * MIN);
        health.tick();
        assertEquals(State.AMBER, health.status(Component.EVENTSUB).state());
    }

    @Test
    void logSetsLatestErrorButNotColour() {
        SystemHealth health = health();
        health.setState(Component.EVENTSUB, State.GREEN, "ok");
        health.log(Component.EVENTSUB, Severity.WARNING, "bad signature", null);
        ComponentStatus status = health.status(Component.EVENTSUB);
        assertEquals(State.GREEN, status.state());
        assertEquals("bad signature", status.lastError());
        assertEquals(now.get(), status.lastErrorTime());
        assertEquals(1, health.getLog().list().size());

        // BOT has no card but still logs.
        now.addAndGet(1000L);
        health.log(Component.BOT, Severity.ERROR, "boom", new RuntimeException("x"));
        SystemLogEntry entry = health.getLog().list().get(0);
        assertEquals("BOT", entry.component);
        assertTrue(entry.detail.contains("RuntimeException"));
    }

    @Test
    void problemLogsBySeverity() {
        SystemHealth health = health();
        health.problem(Component.TWITCH_API, State.AMBER, "rate limited", null);
        now.addAndGet(1000L);
        health.problem(Component.TWITCH_API, State.RED, "token rejected", null);
        List<SystemLogEntry> list = health.getLog().list();
        assertEquals("ERROR", list.get(0).severity);
        assertEquals("WARNING", list.get(1).severity);
    }

    @Test
    void storageTurnsRedUntilEveryFailedFileSaves() {
        SystemHealth health = health();
        File a = new File("a.json");
        File b = new File("b.json");
        health.saved(a);
        assertEquals(State.GREEN, health.status(Component.STORAGE).state());
        health.saveFailed(a, new java.io.IOException("No space left on device"));
        health.saveFailed(b, new java.io.IOException("No space left on device"));
        assertEquals(State.RED, health.status(Component.STORAGE).state());
        assertTrue(health.status(Component.STORAGE).message().contains("No space left"));
        health.saved(a);
        assertEquals(State.RED, health.status(Component.STORAGE).state());
        health.saved(b);
        assertEquals(State.GREEN, health.status(Component.STORAGE).state());
    }

    @Test
    void systemLogWriteFailureShowsOnStorageCard() {
        File dir = tempDir.resolve("dir.jsonl").toFile();
        assertTrue(dir.mkdir());
        SystemHealth health = new SystemHealth(new SystemLog(dir, new Gson()), now::get);
        health.log(Component.BOT, Severity.ERROR, "x", null);
        assertEquals(State.AMBER, health.status(Component.STORAGE).state());
    }

    @Test
    void snapshotIncludesGeneralInfo() {
        SystemHealth health = health();
        health.setGeneralInfo(() -> java.util.Map.of("servers", 3));
        SystemHealth.Snapshot snapshot = health.snapshot();
        assertEquals(3, snapshot.general().get("servers"));
        assertEquals(4, snapshot.components().size());
        health.setGeneralInfo(() -> {
            throw new IllegalStateException("no");
        });
        assertTrue(health.snapshot().general().containsKey("error"));
    }

    @Test
    void detailsAreCopied() {
        SystemHealth health = health();
        health.detail(Component.DISCORD, "gatewayPingMs", 42L);
        ComponentStatus status = health.status(Component.DISCORD);
        assertEquals(42L, status.details().get("gatewayPingMs"));
        assertThrows(UnsupportedOperationException.class, () -> status.details().put("x", 1));
        health.detail(Component.DISCORD, "gatewayPingMs", null);
        assertFalse(health.status(Component.DISCORD).details().containsKey("gatewayPingMs"));
    }
}

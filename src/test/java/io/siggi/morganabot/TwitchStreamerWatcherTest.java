package io.siggi.morganabot;

import io.siggi.morganabot.health.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TwitchStreamerWatcherTest {

    @Test
    void eventSubSummary() {
        assertEquals(State.GREEN, TwitchStreamerWatcher.eventSubState(0, 0));
        assertEquals("4 subscriptions active", TwitchStreamerWatcher.eventSubMessage(4, 0, 0));
        assertEquals("1 subscription active", TwitchStreamerWatcher.eventSubMessage(1, 0, 0));
        assertEquals(State.AMBER, TwitchStreamerWatcher.eventSubState(1, 0));
        assertEquals("1 subscription change failed in the last sync", TwitchStreamerWatcher.eventSubMessage(3, 1, 2));
        assertEquals(State.AMBER, TwitchStreamerWatcher.eventSubState(0, 2));
        assertEquals("2 subscriptions were disabled by Twitch and replaced", TwitchStreamerWatcher.eventSubMessage(4, 0, 2));
    }

    @Test
    void triggerUpdateBeforeStartAndAfterStopIsSafe() {
        TwitchStreamerWatcher watcher = new TwitchStreamerWatcher(null);
        watcher.triggerUpdate();
        watcher.stop();
        watcher.triggerUpdate();
    }
}

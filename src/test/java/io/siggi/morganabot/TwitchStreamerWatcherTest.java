package io.siggi.morganabot;

import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.health.State;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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

    private static ServerInfo server(boolean enabled) {
        ServerInfo info = new ServerInfo();
        info.enabledTwitchTracking = enabled;
        info.streamers.add(new ServerInfo.Streamer(1L, "111"));
        info.streamers.add(new ServerInfo.Streamer(2L, "222"));
        info.streamers.add(new ServerInfo.Streamer(3L, "111"));
        return info;
    }

    @Test
    void disabledServerNeedsNoSubscriptions() {
        assertEquals(Set.of(), TwitchStreamerWatcher.trackedStreamerIds(server(false)));
        assertEquals(Set.of("111", "222"), TwitchStreamerWatcher.trackedStreamerIds(server(true)));
    }

    @Test
    void blockedServerNeedsNoSubscriptionsAndIgnoresNotifications() {
        ServerInfo info = server(true);
        info.blocked = true;
        assertEquals(Set.of(), TwitchStreamerWatcher.trackedStreamerIds(info));
        assertEquals(List.of(), TwitchStreamerWatcher.recordNotification(info, "111", true, 1000L));
        assertEquals(0L, info.streamers.get(0).startedStreaming);
    }

    @Test
    void disabledServerIgnoresNotifications() {
        ServerInfo info = server(false);
        assertEquals(List.of(), TwitchStreamerWatcher.recordNotification(info, "111", true, 1000L));
        for (ServerInfo.Streamer streamer : info.streamers) {
            assertEquals(0L, streamer.startedStreaming);
        }
        info.streamers.get(0).startedStreaming = 500L;
        assertEquals(List.of(), TwitchStreamerWatcher.recordNotification(info, "111", false, 1000L));
        assertEquals(500L, info.streamers.get(0).startedStreaming);
    }

    @Test
    void enabledServerRecordsNotifications() {
        ServerInfo info = server(true);
        info.streamNotificationCooldown = 0L;
        assertEquals(List.of(true, true), TwitchStreamerWatcher.recordNotification(info, "111", true, 1000L));
        assertEquals(1000L, info.streamers.get(0).startedStreaming);
        assertEquals(0L, info.streamers.get(1).startedStreaming);
        assertEquals(List.of(true, true), TwitchStreamerWatcher.recordNotification(info, "111", false, 2000L));
        assertEquals(0L, info.streamers.get(0).startedStreaming);
        assertEquals(2000L, info.streamers.get(2).stoppedStreaming);
        info.streamNotificationCooldown = 5000L;
        assertEquals(List.of(false, false), TwitchStreamerWatcher.recordNotification(info, "111", true, 3000L));
    }

    @Test
    void autoPublishIsOnByDefaultInAnnouncementChannels() {
        ServerInfo info = new ServerInfo();
        assertTrue(TwitchStreamerWatcher.shouldPublish(info, 10L, true, false));
    }

    @Test
    void silentRepostIsNeverPublished() {
        ServerInfo info = new ServerInfo();
        assertFalse(TwitchStreamerWatcher.shouldPublish(info, 10L, true, true));
        info.autoPublishChannels().put(10L, true);
        assertFalse(TwitchStreamerWatcher.shouldPublish(info, 10L, true, true));
    }

    @Test
    void textChannelIgnoresAutoPublish() {
        ServerInfo info = new ServerInfo();
        assertFalse(TwitchStreamerWatcher.shouldPublish(info, 10L, false, false));
        info.autoPublishChannels().put(10L, true);
        assertFalse(TwitchStreamerWatcher.shouldPublish(info, 10L, false, false));
    }

    @Test
    void autoPublishOffPerChannel() {
        ServerInfo info = new ServerInfo();
        info.autoPublishChannels().put(10L, false);
        assertFalse(TwitchStreamerWatcher.shouldPublish(info, 10L, true, false));
        assertTrue(TwitchStreamerWatcher.shouldPublish(info, 11L, true, false), "other channels keep the default");
    }
}

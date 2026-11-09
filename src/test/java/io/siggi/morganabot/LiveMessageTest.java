package io.siggi.morganabot;

import io.siggi.morganabot.config.ServerInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiveMessageTest {

    @Test
    void templatePrecedence() {
        ServerInfo info = new ServerInfo();
        ServerInfo.Streamer streamer = new ServerInfo.Streamer(1L, "10");
        assertEquals(LiveMessage.DEFAULT_TEMPLATE, LiveMessage.templateFor(info, streamer));
        info.liveNotificationTemplate = "server";
        assertEquals("server", LiveMessage.templateFor(info, streamer));
        streamer.liveNotificationTemplate = "own";
        assertEquals("own", LiveMessage.templateFor(info, streamer));
    }

    @Test
    void fillsVariables() {
        String filled = LiveMessage.fill("${streamer_name} ${game_name} ${stream_name} ${twitch_link} ${discord_name}",
                "Game_X", "Title *bold*", "some_one", 42L);
        assertEquals("some\\_one Game\\_X Title \\*bold\\* https://www.twitch.tv/some_one <@42>", filled);
        assertTrue(LiveMessage.fill("${discord_name}", "g", "t", "s", 0L).equals("?"));
    }
}

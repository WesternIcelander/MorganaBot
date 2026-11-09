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

    @Test
    void twitchValuesCantPing() {
        String filled = LiveMessage.fill("${stream_name} | ${game_name} | ${streamer_name}",
                "@here game", "Hi @everyone <@&123> <@456>", "@name", "name", 0L);
        assertEquals("Hi @\u200Beveryone <@\u200B&123> <@\u200B456> | @\u200Bhere game | @\u200Bname", filled);
        Mentions inFilled = Mentions.parse(filled);
        assertTrue(inFilled.isEmpty(), "defused Twitch values must not parse as mentions");
    }

    @Test
    void templateTextIsNeverAltered() {
        String template = "@everyone <@&123> <@456> ${stream_name}";
        assertEquals("@everyone <@&123> <@456> title", LiveMessage.fill(template, "g", "title", "s", "s", 0L));
    }

    @Test
    void twitchLinkUsesLogin() {
        assertEquals("https://www.twitch.tv/jp_streamer",
                LiveMessage.fill("${twitch_link}", "g", "t", "日本の配信者", "jp_streamer", 0L));
    }

    @Test
    void twitchValuesLookingLikeVariablesArentFilledIn() {
        assertEquals("${discord\\_name} <@42>", LiveMessage.fill("${stream_name} ${discord_name}", "g", "${discord_name}", "s", "s", 42L));
    }

    @Test
    void buildAllowsOnlyTemplateMentions() {
        LiveMessage.Filled filled = LiveMessage.build("@here <@&5> <@6> ${discord_name} ${stream_name}",
                "g", "@everyone <@&7> <@8>", "s", "s", 42L);
        assertTrue(filled.mentions().here());
        assertFalse(filled.mentions().everyone());
        assertEquals(java.util.Set.of(5L), filled.mentions().roles());
        assertEquals(java.util.List.of(6L, 42L), java.util.List.copyOf(filled.users()));

        LiveMessage.Filled plain = LiveMessage.build(LiveMessage.DEFAULT_TEMPLATE, "g", "@everyone", "s", "s", 42L);
        assertTrue(plain.mentions().isEmpty());
        assertTrue(plain.users().isEmpty());
        // ${discord_name} pings the linked user only if the template uses it, and only when there is one.
        assertTrue(LiveMessage.build("${discord_name}", "g", "t", "s", "s", 0L).users().isEmpty());
    }

    @Test
    void allowedMentionTypes() {
        assertEquals(java.util.EnumSet.noneOf(net.dv8tion.jda.api.entities.Message.MentionType.class),
                TwitchStreamerWatcher.allowedMentionTypes(LiveMessage.build("${stream_name}", "g", "@everyone @here", "s", "s", 0L)));
        assertEquals(java.util.EnumSet.of(net.dv8tion.jda.api.entities.Message.MentionType.EVERYONE),
                TwitchStreamerWatcher.allowedMentionTypes(LiveMessage.build("@everyone", "g", "t", "s", "s", 0L)));
        assertEquals(java.util.EnumSet.of(net.dv8tion.jda.api.entities.Message.MentionType.HERE),
                TwitchStreamerWatcher.allowedMentionTypes(LiveMessage.build("@here", "g", "t", "s", "s", 0L)));
    }

    @Test
    void exampleLengthUsesLongValues() {
        assertTrue(LiveMessage.exampleLength("${stream_name}") >= 140);
        assertEquals(5, LiveMessage.exampleLength("short"));
    }
}

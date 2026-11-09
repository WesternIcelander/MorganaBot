package io.siggi.morganabot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MentionsTest {

    @Test
    void findsEveryKind() {
        Mentions m = Mentions.parse("@everyone <@&123> hi <@456> and <@!789> @here <@&123> <@456>");
        assertTrue(m.everyone());
        assertTrue(m.here());
        assertEquals(List.of(123L), List.copyOf(m.roles()));
        assertEquals(List.of(456L, 789L), List.copyOf(m.users()));
        assertFalse(m.isEmpty());
    }

    @Test
    void noMentions() {
        Mentions m = Mentions.parse("${streamer_name} is live! ${discord_name} ${twitch_link} email@example.com <#123> <@abc>");
        assertFalse(m.everyone());
        assertFalse(m.here());
        assertTrue(m.roles().isEmpty());
        assertTrue(m.users().isEmpty());
        assertTrue(m.isEmpty());
        assertTrue(Mentions.parse(null).isEmpty());
        assertTrue(Mentions.parse("").isEmpty());
    }

    @Test
    void everyoneAndHereSeparately() {
        Mentions everyone = Mentions.parse("hey @everyone!");
        assertTrue(everyone.everyone());
        assertFalse(everyone.here());
        Mentions here = Mentions.parse("@here");
        assertFalse(here.everyone());
        assertTrue(here.here());
    }

    @Test
    void oversizedIdIsIgnored() {
        assertTrue(Mentions.parse("<@&99999999999999999999>").roles().isEmpty());
    }
}

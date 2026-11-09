package io.siggi.morganabot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockedGuildListenerTest {

    @Test
    void logLineNamesServerOwnerAndMembers() {
        assertEquals("[Blocked server] Server \"Bad\" (100, owner 7, 42 members) tried to add the bot; leaving.",
                BlockedGuildListener.logLine(100L, "Bad", 7L, 42, BlockedGuildListener.Reason.JOIN));
        assertEquals("[Blocked server] Server \"Bad\" (100, owner 7, 1 member) was still joined at startup; leaving.",
                BlockedGuildListener.logLine(100L, "Bad", 7L, 1, BlockedGuildListener.Reason.STARTUP));
    }
}

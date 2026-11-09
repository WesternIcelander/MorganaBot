package io.siggi.morganabot.dashboard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PendingReplyTest {
    @Test
    void hookFirstThenRedeemed() {
        List<String> edits = new ArrayList<>();
        PendingReply reply = new PendingReply();
        reply.attach(edits::add);
        assertTrue(edits.isEmpty());
        reply.redeemed();
        reply.expired();
        assertEquals(List.of(PendingReply.LOGGED_IN), edits);
    }

    @Test
    void redeemedBeforeHookArrives() {
        List<String> edits = new ArrayList<>();
        PendingReply reply = new PendingReply();
        reply.redeemed();
        assertTrue(edits.isEmpty());
        reply.attach(edits::add);
        assertEquals(List.of(PendingReply.LOGGED_IN), edits);
        reply.attach(edits::add);
        assertEquals(1, edits.size());
    }

    @Test
    void expired() {
        List<String> edits = new ArrayList<>();
        PendingReply reply = new PendingReply();
        reply.attach(edits::add);
        reply.expired();
        assertEquals(List.of("Link expired, run /dashboard again"), edits);
        assertEquals("Logged in ✓", PendingReply.LOGGED_IN);
    }
}

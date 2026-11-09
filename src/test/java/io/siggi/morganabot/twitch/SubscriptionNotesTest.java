package io.siggi.morganabot.twitch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionNotesTest {

    @Test
    void disabledNotesExpireAndFailuresAreReplaced() {
        SubscriptionNotes notes = new SubscriptionNotes();
        notes.disabled("1", "stream.online", "notification_failures_exceeded", 1000L);
        notes.disabled("1", "stream.online", "authorization_revoked", 2000L); // replaces the older one of the same type
        notes.syncFinished(Map.of("1", List.of(new SubscriptionNotes.Note(SubscriptionNotes.Kind.CREATE_FAILED, "stream.offline", "429", 3000L))));

        List<SubscriptionNotes.Note> now = notes.notesFor("1", 3000L);
        assertEquals(2, now.size());
        assertEquals(SubscriptionNotes.Kind.CREATE_FAILED, now.get(0).kind());
        assertEquals("authorization_revoked", now.get(1).status());
        assertTrue(notes.notesFor("2", 3000L).isEmpty());

        notes.syncFinished(Map.of());
        List<SubscriptionNotes.Note> later = notes.notesFor("1", 2000L + SubscriptionNotes.DISABLED_KEEP + 1L);
        assertTrue(later.isEmpty());
    }
}

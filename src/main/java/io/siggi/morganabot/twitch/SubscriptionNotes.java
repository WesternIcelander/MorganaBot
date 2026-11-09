package io.siggi.morganabot.twitch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-streamer EventSub trouble, for Diagnose: subscriptions Twitch disabled or revoked (kept for
 * {@link #DISABLED_KEEP} so they're still visible after the sync replaced them), and subscriptions the last sync
 * couldn't create (replaced by every sync). Thread-safe.
 */
public final class SubscriptionNotes {

    /** How long a "disabled by Twitch" note stays visible. */
    public static final long DISABLED_KEEP = 24L * 60L * 60L * 1000L;

    public enum Kind {
        /** Twitch disabled or revoked the subscription. The sync deletes it and creates a new one. */
        DISABLED,
        /** The last sync couldn't create the subscription, so no notifications arrive for it. */
        CREATE_FAILED
    }

    /**
     * @param type   the subscription type, e.g. {@code stream.online}
     * @param status Twitch's status (e.g. {@code notification_failures_exceeded}), or the error for a failed create
     * @param time   when it was seen
     */
    public record Note(Kind kind, String type, String status, long time) {
    }

    private final Map<String, Map<String, Note>> disabled = new HashMap<>();
    private Map<String, List<Note>> createFailed = new HashMap<>();

    /**
     * Records that Twitch disabled or revoked a subscription.
     */
    public synchronized void disabled(String broadcasterId, String type, String status, long now) {
        disabled.computeIfAbsent(broadcasterId, k -> new HashMap<>()).put(type, new Note(Kind.DISABLED, type, status, now));
    }

    /**
     * Replaces the failed creates with those of the sync that just finished.
     *
     * @param failures notes by broadcaster ID
     */
    public synchronized void syncFinished(Map<String, List<Note>> failures) {
        Map<String, List<Note>> copy = new HashMap<>();
        failures.forEach((id, notes) -> copy.put(id, List.copyOf(notes)));
        createFailed = copy;
    }

    /**
     * The current notes for a streamer, failed creates first.
     */
    public synchronized List<Note> notesFor(String broadcasterId, long now) {
        List<Note> notes = new ArrayList<>(createFailed.getOrDefault(broadcasterId, List.of()));
        Map<String, Note> byType = disabled.get(broadcasterId);
        if (byType != null) {
            byType.values().removeIf(note -> now - note.time() > DISABLED_KEEP);
            if (byType.isEmpty()) {
                disabled.remove(broadcasterId);
            } else {
                byType.values().stream().sorted((a, b) -> a.type().compareTo(b.type())).forEach(notes::add);
            }
        }
        return notes;
    }
}

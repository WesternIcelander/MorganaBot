package io.siggi.morganabot.dashboard;

import java.util.function.Consumer;

/**
 * Edits the {@code /dashboard} reply once its token is redeemed or expires.
 * <p>
 * The interaction hook only arrives after Discord accepts the reply, and a fast click could redeem the token before
 * that. So whichever comes second (the hook or the outcome) does the edit, and it happens exactly once.
 */
public class PendingReply implements LoginTokenStore.Listener {
    public static final String LOGGED_IN = "Logged in ✓";
    public static final String EXPIRED = "Link expired, run /dashboard again";

    private Consumer<String> editor;
    private String outcome;
    private boolean done;

    /**
     * The reply was sent; {@code editor} replaces its text and removes the button.
     */
    public void attach(Consumer<String> editor) {
        String text;
        synchronized (this) {
            if (this.editor != null || done) return;
            this.editor = editor;
            if (outcome == null) return;
            text = outcome;
            done = true;
        }
        editor.accept(text);
    }

    @Override
    public void redeemed() {
        finish(LOGGED_IN);
    }

    @Override
    public void expired() {
        finish(EXPIRED);
    }

    private void finish(String text) {
        Consumer<String> target;
        synchronized (this) {
            if (outcome != null) return;
            outcome = text;
            if (editor == null) return;
            target = editor;
            done = true;
        }
        target.accept(text);
    }
}

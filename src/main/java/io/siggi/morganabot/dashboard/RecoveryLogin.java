package io.siggi.morganabot.dashboard;

import java.io.PrintStream;
import java.util.function.LongSupplier;

/**
 * The console recovery login: {@code /dashboard} needs Discord, so while Discord is down the bot prints a single-use
 * super admin login link to stdout, valid for 15 minutes, and a fresh one every hour while it stays down. Anyone who
 * can read the console can already read {@code config.json}, so this doesn't widen access.
 */
public class RecoveryLogin {
    public static final long REPEAT = 60L * 60L * 1000L;

    private final LoginTokenStore tokens;
    private final String apiEndpoint;
    private final long userId;
    private final PrintStream out;
    private final LongSupplier clock;
    /** Whether a link was printed during the current outage, and when. */
    private boolean printed;
    private long lastPrinted;

    /**
     * @param userId the user the session is for (the first super admin, or 0 if there are none)
     */
    public RecoveryLogin(LoginTokenStore tokens, String apiEndpoint, long userId, PrintStream out, LongSupplier clock) {
        this.tokens = tokens;
        this.apiEndpoint = apiEndpoint;
        this.userId = userId;
        this.out = out;
        this.clock = clock;
    }

    /**
     * Called whenever Discord's state may have changed, and periodically. Prints a link when Discord goes down, and
     * again every {@link #REPEAT} while it stays down.
     *
     * @return the printed token, or null if nothing was printed
     */
    public synchronized String check(boolean discordDown) {
        if (!discordDown) {
            printed = false;
            return null;
        }
        long now = clock.getAsLong();
        if (printed && now - lastPrinted < REPEAT) return null;
        printed = true;
        lastPrinted = now;
        String token = tokens.issue(new LoginTokenStore.Login(userId, true, 0L), LoginTokenStore.RECOVERY_TTL, null);
        out.println("[RECOVERY] Discord is unavailable, so /dashboard can't be used. Super admin login link "
                + "(single use, valid for 15 minutes): " + DashboardCommand.loginUrl(apiEndpoint, token));
        return token;
    }
}

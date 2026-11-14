package io.siggi.morganabot.dashboard;

import io.siggi.http.session.SessionHandle;
import io.siggi.http.session.Sessions;
import io.siggi.http.session.SessionsBuilder;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * What a logged-in dashboard session holds: the Discord user ID, the super admin flag, the CSRF token and the login
 * time. Access to servers isn't stored; it's checked on every request.
 * <p>
 * Sessions are SiggiHTTP's in-memory sessions (24 hours since last use, cookie {@code SameSite=Lax}), built by
 * {@link #createSessions()}. Super admin sessions are also cut off
 * {@link #SUPER_ADMIN_MAX_AGE} after login.
 */
public record DashboardSession(long userId, boolean superAdmin, String csrfToken, long loginTime) {
    /** How long an idle session lives (SiggiHTTP's expiry). */
    public static final long SESSION_TTL = 24L * 60L * 60L * 1000L;
    /** Super admin sessions end this long after login, however active they are. */
    public static final long SUPER_ADMIN_MAX_AGE = 8L * 60L * 60L * 1000L;
    /** The header every write request must carry, holding {@link #csrfToken()}. */
    public static final String CSRF_HEADER = "X-Morgana-CSRF";

    static final String KEY_USER = "morgana.userId";
    static final String KEY_SUPER_ADMIN = "morgana.superAdmin";
    static final String KEY_CSRF = "morgana.csrf";
    static final String KEY_LOGIN_TIME = "morgana.loginTime";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The dashboard's session store: in memory (a restart logs everyone out), {@link #SESSION_TTL} since last use. */
    public static Sessions createSessions() {
        return new SessionsBuilder().setExpireTime(SESSION_TTL).build();
    }

    /** Writes a fresh login into a writable session handle (from {@code Sessions.resetSession}). */
    public static DashboardSession login(SessionHandle session, long userId, boolean superAdmin, long now) {
        return login((BiConsumer<String, String>) session::set, userId, superAdmin, now);
    }

    /**
     * Reads the login from a session handle (from {@code Sessions.readSession}).
     *
     * @return the login, or null if there's no session, it isn't logged in, or it's a super admin session older than
     * {@link #SUPER_ADMIN_MAX_AGE}
     */
    public static DashboardSession from(SessionHandle session, long now) {
        if (session == null || !session.exists()) return null;
        return from((Function<String, String>) session::get, now);
    }

    /** Writes a fresh login, with a new CSRF token, through {@code set}. */
    static DashboardSession login(BiConsumer<String, String> set, long userId, boolean superAdmin, long now) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        DashboardSession login = new DashboardSession(userId, superAdmin,
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), now);
        set.accept(KEY_USER, Long.toString(userId));
        set.accept(KEY_SUPER_ADMIN, Boolean.toString(superAdmin));
        set.accept(KEY_CSRF, login.csrfToken);
        set.accept(KEY_LOGIN_TIME, Long.toString(now));
        return login;
    }

    /** Reads the login from session values; null as {@link #from(SessionHandle, long)}. */
    static DashboardSession from(Function<String, String> get, long now) {
        String user = get.apply(KEY_USER);
        String csrf = get.apply(KEY_CSRF);
        String time = get.apply(KEY_LOGIN_TIME);
        if (user == null || csrf == null || csrf.isEmpty() || time == null) return null;
        DashboardSession login;
        try {
            login = new DashboardSession(Long.parseLong(user), Boolean.parseBoolean(get.apply(KEY_SUPER_ADMIN)),
                    csrf, Long.parseLong(time));
        } catch (NumberFormatException e) {
            return null;
        }
        if (login.isExpired(now)) return null;
        return login;
    }

    /**
     * Super admin sessions expire {@link #SUPER_ADMIN_MAX_AGE} after login; normal ones only through SiggiHTTP's idle
     * expiry.
     */
    public boolean isExpired(long now) {
        return superAdmin && now - loginTime >= SUPER_ADMIN_MAX_AGE;
    }

    /** Constant-time comparison of a request's CSRF header with this session's token. */
    public boolean csrfMatches(String header) {
        if (header == null || header.isEmpty()) return false;
        return MessageDigest.isEqual(csrfToken.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
    }
}

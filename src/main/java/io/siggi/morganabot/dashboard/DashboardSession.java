package io.siggi.morganabot.dashboard;

import io.siggi.http.session.Session;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * What a logged-in dashboard session holds: the Discord user ID, the super admin flag, the CSRF token and the login
 * time. Access to servers isn't stored; it's checked on every request.
 * <p>
 * Sessions are SiggiHTTP's in-memory sessions (24 hours since last use). Super admin sessions are also cut off
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

    /**
     * Writes a fresh login into {@code session} (which should be new, from {@code resetSession()}), with a new CSRF
     * token.
     */
    public static DashboardSession login(Session session, long userId, boolean superAdmin, long now) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        DashboardSession login = new DashboardSession(userId, superAdmin,
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), now);
        session.set(KEY_USER, Long.toString(userId));
        session.set(KEY_SUPER_ADMIN, Boolean.toString(superAdmin));
        session.set(KEY_CSRF, login.csrfToken);
        session.set(KEY_LOGIN_TIME, Long.toString(now));
        return login;
    }

    /**
     * Reads the login from a session.
     *
     * @return the login, or null if the session is missing, isn't logged in, or is a super admin session older than
     * {@link #SUPER_ADMIN_MAX_AGE}
     */
    public static DashboardSession from(Session session, long now) {
        if (session == null || session.isDeleted()) return null;
        String user = session.get(KEY_USER);
        String csrf = session.get(KEY_CSRF);
        String time = session.get(KEY_LOGIN_TIME);
        if (user == null || csrf == null || csrf.isEmpty() || time == null) return null;
        DashboardSession login;
        try {
            login = new DashboardSession(Long.parseLong(user), Boolean.parseBoolean(session.get(KEY_SUPER_ADMIN)),
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

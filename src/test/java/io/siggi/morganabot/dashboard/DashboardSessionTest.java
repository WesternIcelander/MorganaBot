package io.siggi.morganabot.dashboard;

import io.siggi.http.session.Session;
import io.siggi.http.session.Sessions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DashboardSessionTest {
    private final Sessions sessions = Sessions.create(DashboardSession.SESSION_TTL);

    @Test
    void roundTrip() {
        Session session = sessions.newSession();
        DashboardSession login = DashboardSession.login(session, 1234567890123456789L, false, 5000L);
        assertTrue(login.csrfToken().length() >= 40);
        DashboardSession read = DashboardSession.from(sessions.get(session.getSessionId()), 6000L);
        assertEquals(login, read);
        assertTrue(read.csrfMatches(login.csrfToken()));
        assertFalse(read.csrfMatches("wrong"));
        assertFalse(read.csrfMatches(null));
        assertFalse(read.csrfMatches(""));
    }

    @Test
    void newCsrfTokenEachLogin() {
        DashboardSession a = DashboardSession.login(sessions.newSession(), 1L, false, 0L);
        DashboardSession b = DashboardSession.login(sessions.newSession(), 1L, false, 0L);
        assertNotEquals(a.csrfToken(), b.csrfToken());
    }

    @Test
    void notLoggedIn() {
        assertNull(DashboardSession.from(null, 0L));
        assertNull(DashboardSession.from(sessions.newSession(), 0L));
        Session deleted = sessions.newSession();
        DashboardSession.login(deleted, 1L, false, 0L);
        deleted.delete();
        assertNull(DashboardSession.from(deleted, 0L));
    }

    @Test
    void superAdminSessionsEndAfterEightHours() {
        Session session = sessions.newSession();
        DashboardSession.login(session, 1L, true, 0L);
        assertNotNull(DashboardSession.from(session, DashboardSession.SUPER_ADMIN_MAX_AGE - 1L));
        assertNull(DashboardSession.from(session, DashboardSession.SUPER_ADMIN_MAX_AGE));
    }

    @Test
    void normalSessionsDontHaveTheEightHourLimit() {
        Session session = sessions.newSession();
        DashboardSession.login(session, 1L, false, 0L);
        assertNotNull(DashboardSession.from(session, DashboardSession.SUPER_ADMIN_MAX_AGE * 2));
    }
}

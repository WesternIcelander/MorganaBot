package io.siggi.morganabot.dashboard;

import io.siggi.http.session.SessionHandle;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The stored login values; sessions themselves are covered over HTTP in LoginEndpointsTest. */
class DashboardSessionTest {
    private static DashboardSession login(Map<String, String> values, long userId, boolean superAdmin, long now) {
        return DashboardSession.login(values::put, userId, superAdmin, now);
    }

    private static DashboardSession read(Map<String, String> values, long now) {
        return DashboardSession.from(values::get, now);
    }

    @Test
    void roundTrip() {
        Map<String, String> values = new HashMap<>();
        DashboardSession login = login(values, 1234567890123456789L, false, 5000L);
        assertTrue(login.csrfToken().length() >= 40);
        DashboardSession read = read(values, 6000L);
        assertEquals(login, read);
        assertTrue(read.csrfMatches(login.csrfToken()));
        assertFalse(read.csrfMatches("wrong"));
        assertFalse(read.csrfMatches(null));
        assertFalse(read.csrfMatches(""));
    }

    @Test
    void newCsrfTokenEachLogin() {
        DashboardSession a = login(new HashMap<>(), 1L, false, 0L);
        DashboardSession b = login(new HashMap<>(), 1L, false, 0L);
        assertNotEquals(a.csrfToken(), b.csrfToken());
    }

    @Test
    void notLoggedIn() {
        assertNull(DashboardSession.from((SessionHandle) null, 0L));
        assertNull(read(new HashMap<>(), 0L));
        Map<String, String> broken = new HashMap<>();
        login(broken, 1L, false, 0L);
        broken.put(DashboardSession.KEY_USER, "not a number");
        assertNull(read(broken, 0L));
    }

    @Test
    void superAdminSessionsEndAfterEightHours() {
        Map<String, String> values = new HashMap<>();
        login(values, 1L, true, 0L);
        assertNotNull(read(values, DashboardSession.SUPER_ADMIN_MAX_AGE - 1L));
        assertNull(read(values, DashboardSession.SUPER_ADMIN_MAX_AGE));
    }

    @Test
    void normalSessionsDontHaveTheEightHourLimit() {
        Map<String, String> values = new HashMap<>();
        login(values, 1L, false, 0L);
        assertNotNull(read(values, DashboardSession.SUPER_ADMIN_MAX_AGE * 2));
    }
}

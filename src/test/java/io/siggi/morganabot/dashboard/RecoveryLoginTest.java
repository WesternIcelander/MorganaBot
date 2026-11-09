package io.siggi.morganabot.dashboard;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryLoginTest {
    private final AtomicLong now = new AtomicLong(0L);
    private final LoginTokenStore tokens = new LoginTokenStore(now::get);
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final RecoveryLogin recovery = new RecoveryLogin(tokens, "https://morgana.example/", 99L,
            new PrintStream(out, true, StandardCharsets.UTF_8), now::get);

    @Test
    void nothingWhileDiscordIsUp() {
        assertNull(recovery.check(false));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void printsOnFailureThenHourly() {
        String first = recovery.check(true);
        assertNotNull(first);
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("https://morgana.example/dashboard/login?token=" + first), printed);
        now.addAndGet(30_000L);
        assertNull(recovery.check(true), "not again straight away");
        now.addAndGet(RecoveryLogin.REPEAT);
        String second = recovery.check(true);
        assertNotNull(second);
        assertNotEquals(first, second);
    }

    @Test
    void recoveryTokenIsSuperAdminAndExpiresAfter15Minutes() {
        String token = recovery.check(true);
        assertEquals(new LoginTokenStore.Login(99L, true, 0L), tokens.redeem(token));
        assertNull(recovery.check(false));
        String another = recovery.check(true);
        now.addAndGet(LoginTokenStore.RECOVERY_TTL);
        assertNull(tokens.redeem(another));
    }

    @Test
    void recoveringResetsSoTheNextOutagePrintsAtOnce() {
        assertNotNull(recovery.check(true));
        assertNull(recovery.check(false));
        now.addAndGet(60_000L);
        assertNotNull(recovery.check(true));
    }

    @Test
    void loginUrl() {
        assertEquals("https://x.example/dashboard/login?token=abc", DashboardCommand.loginUrl("https://x.example//", "abc"));
        assertEquals("https://x.example/dashboard/login?token=abc", DashboardCommand.loginUrl(" https://x.example ", "abc"));
    }
}

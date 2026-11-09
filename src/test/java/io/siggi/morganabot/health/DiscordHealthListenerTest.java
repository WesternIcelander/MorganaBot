package io.siggi.morganabot.health;

import com.google.gson.Gson;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.requests.CloseCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DiscordHealthListenerTest {

    @TempDir
    Path tempDir;

    @Test
    void statusMapping() {
        assertEquals(State.GREEN, DiscordHealthListener.forStatus(JDA.Status.CONNECTED).state());
        assertEquals(State.AMBER, DiscordHealthListener.forStatus(JDA.Status.LOGGING_IN).state());
        DiscordHealthListener.Mapping reconnecting = DiscordHealthListener.forStatus(JDA.Status.WAITING_TO_RECONNECT);
        assertEquals(State.AMBER, reconnecting.state());
        assertTrue(reconnecting.disconnected());
        assertNull(DiscordHealthListener.forStatus(JDA.Status.FAILED_TO_LOGIN));
        assertNull(DiscordHealthListener.forStatus(JDA.Status.SHUTDOWN));
        for (JDA.Status status : JDA.Status.values()) {
            if (status == JDA.Status.SHUTTING_DOWN || status == JDA.Status.SHUTDOWN || status == JDA.Status.FAILED_TO_LOGIN) continue;
            assertNotNull(DiscordHealthListener.forStatus(status), status.name());
        }
    }

    @Test
    void closeCodeMapping() {
        DiscordHealthListener.Mapping auth = DiscordHealthListener.forCloseCode(CloseCode.AUTHENTICATION_FAILED);
        assertEquals(State.RED, auth.state());
        assertTrue(auth.fatal());
        assertTrue(auth.message().contains("token"));

        DiscordHealthListener.Mapping intents = DiscordHealthListener.forCloseCode(CloseCode.DISALLOWED_INTENTS);
        assertEquals(State.RED, intents.state());
        assertTrue(intents.fatal());
        assertTrue(intents.message().contains("intents"));

        DiscordHealthListener.Mapping rateLimited = DiscordHealthListener.forCloseCode(CloseCode.RATE_LIMITED);
        assertEquals(State.AMBER, rateLimited.state());
        assertFalse(rateLimited.fatal());

        assertNull(DiscordHealthListener.forCloseCode(CloseCode.RECONNECT));
        assertNull(DiscordHealthListener.forCloseCode(CloseCode.GRACEFUL_CLOSE));
        assertNull(DiscordHealthListener.forCloseCode(null));
        for (CloseCode code : CloseCode.values()) {
            DiscordHealthListener.Mapping mapping = DiscordHealthListener.forCloseCode(code);
            if (mapping != null && !code.isReconnect()) assertTrue(mapping.fatal(), code.name());
        }
    }

    @Test
    void fatalCloseCodeSticks() {
        SystemHealth health = new SystemHealth(new SystemLog(tempDir.resolve("log.jsonl").toFile(), new Gson()));
        DiscordHealthListener listener = new DiscordHealthListener(health, () -> false);
        listener.onStatus(JDA.Status.LOGGING_IN);
        assertEquals(State.AMBER, health.status(Component.DISCORD).state());
        listener.handleCloseCode(CloseCode.AUTHENTICATION_FAILED);
        assertEquals(CloseCode.AUTHENTICATION_FAILED, listener.fatalCloseCode());
        String message = health.status(Component.DISCORD).message();
        listener.onStatus(JDA.Status.SHUTDOWN);
        assertEquals(message, health.status(Component.DISCORD).message(), "shutdown after a fatal close keeps the message");
        assertEquals(State.RED, health.status(Component.DISCORD).state());
        assertEquals("ERROR", health.getLog().list().get(0).severity);

        listener.reset();
        listener.onStatus(JDA.Status.CONNECTED);
        assertEquals(State.GREEN, health.status(Component.DISCORD).state());
    }

    @Test
    void unexplainedShutdown() {
        SystemHealth health = new SystemHealth(new SystemLog(tempDir.resolve("log.jsonl").toFile(), new Gson()));
        DiscordHealthListener listener = new DiscordHealthListener(health, () -> false);
        // Before the first connect, the connector reports failures; the listener stays quiet.
        listener.onStatus(JDA.Status.LOGGING_IN);
        listener.onStatus(JDA.Status.SHUTDOWN);
        listener.onShutdown(null, 1000);
        assertEquals(State.AMBER, health.status(Component.DISCORD).state());

        listener.onStatus(JDA.Status.CONNECTED);
        listener.onShutdown(null, 1000);
        assertEquals(State.RED, health.status(Component.DISCORD).state());
        assertTrue(health.status(Component.DISCORD).message().contains("shut down unexpectedly"));
    }

    @Test
    void shutdownWithFatalCodeUsesItsMessage() {
        SystemHealth health = new SystemHealth(new SystemLog(tempDir.resolve("log.jsonl").toFile(), new Gson()));
        DiscordHealthListener listener = new DiscordHealthListener(health, () -> false);
        listener.onShutdown(CloseCode.DISALLOWED_INTENTS, 4014);
        assertEquals(CloseCode.DISALLOWED_INTENTS, listener.fatalCloseCode());
        assertTrue(health.status(Component.DISCORD).message().contains("intents"));
    }

    @Test
    void ignoredWhileStopping() {
        SystemHealth health = new SystemHealth(new SystemLog(tempDir.resolve("log.jsonl").toFile(), new Gson()));
        AtomicBoolean stopping = new AtomicBoolean(false);
        DiscordHealthListener listener = new DiscordHealthListener(health, stopping::get);
        listener.onStatus(JDA.Status.CONNECTED);
        stopping.set(true);
        listener.onStatus(JDA.Status.SHUTDOWN);
        listener.handleCloseCode(CloseCode.AUTHENTICATION_FAILED);
        assertEquals(State.GREEN, health.status(Component.DISCORD).state());
    }
}

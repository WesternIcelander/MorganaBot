package io.siggi.morganabot.health;

import com.google.gson.Gson;
import io.siggi.morganabot.config.Data;
import io.siggi.morganabot.config.UserData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code Data.save()} reports to the installed {@link SystemHealth} and throws on failure.
 */
class HealthTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void uninstall() {
        Health.install(null);
    }

    @Test
    void failedSaveThrowsAndTurnsStorageRed() throws Exception {
        SystemHealth health = new SystemHealth(new SystemLog(tempDir.resolve("log.jsonl").toFile(), new Gson()));
        Health.install(health);

        // The parent "directory" is a regular file, so the save can't work.
        File blocker = tempDir.resolve("blocker").toFile();
        Files.writeString(blocker.toPath(), "x");
        UserData data = Data.read(new File(blocker, "1.json"), UserData.class, new Gson());
        data.timezone = "Europe/Oslo";
        assertThrows(Data.DataSaveException.class, data::save);
        assertEquals(State.RED, health.status(Component.STORAGE).state());
        assertEquals("ERROR", health.getLog().list().get(0).severity);

        // Saving the same file successfully clears it.
        Files.delete(blocker.toPath());
        data.save();
        assertEquals(State.GREEN, health.status(Component.STORAGE).state());
        assertEquals("Europe/Oslo", Data.read(new File(blocker, "1.json"), UserData.class, new Gson()).timezone);
        // No temp files left behind.
        assertEquals(1, blocker.list().length);
    }

    @Test
    void saveWithoutHealthInstalledStillThrows() throws Exception {
        File blocker = tempDir.resolve("blocker").toFile();
        Files.writeString(blocker.toPath(), "x");
        UserData data = Data.read(new File(blocker, "1.json"), UserData.class, new Gson());
        assertThrows(Data.DataSaveException.class, data::save);
    }

    @Test
    void describe() {
        assertEquals("IllegalStateException: nope", Health.describe(new IllegalStateException("nope")));
        assertEquals("RuntimeException", Health.describe(new RuntimeException()));
        assertEquals("unknown error", Health.describe(null));
    }
}

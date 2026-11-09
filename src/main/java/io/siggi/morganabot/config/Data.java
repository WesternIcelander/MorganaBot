package io.siggi.morganabot.config;

import com.google.gson.Gson;
import io.siggi.morganabot.health.Health;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public class Data {
    public transient File file;
    public transient Gson gson;

    public static <T extends Data> T read(File file, Class<T> type, Gson gson) {
        T data;
        try (FileReader reader = new FileReader(file)) {
            data = gson.fromJson(reader, type);
        } catch (Exception e) {
            data = null;
        }
        if (data == null) {
            try {
                data = type.getDeclaredConstructor().newInstance();
            } catch (Exception e2) {
                throw new RuntimeException(e2);
            }
        }
        data.file = file;
        data.gson = gson;
        return data;
    }

    /**
     * Writes this object to its file (temp file, then atomic rename).
     *
     * @throws DataSaveException if it couldn't be saved; the failure is also reported to the Storage status
     */
    public void save() {
        File parentDirectory = file.getAbsoluteFile().getParentFile();
        File tmpSaveFile = new File(parentDirectory, file.getName() + UUID.randomUUID() + ".sav");
        try {
            try {
                if (!parentDirectory.exists() && !parentDirectory.mkdirs() && !parentDirectory.isDirectory()) {
                    throw new IOException("Could not create directory " + parentDirectory);
                }
                Files.writeString(tmpSaveFile.toPath(), gson.toJson(this), StandardCharsets.UTF_8);
                Files.move(tmpSaveFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmpSaveFile.toPath());
            }
        } catch (IOException | RuntimeException e) {
            Health.saveFailed(file, e);
            throw new DataSaveException(file, e);
        }
        Health.saved(file);
    }

    /**
     * Thrown by {@link #save()} when the file couldn't be written.
     */
    public static class DataSaveException extends UncheckedIOException {
        public DataSaveException(File file, Exception cause) {
            super("Could not save " + file, cause instanceof IOException io ? io : new IOException(cause));
        }
    }
}

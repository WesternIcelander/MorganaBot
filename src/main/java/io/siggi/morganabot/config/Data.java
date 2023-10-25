package io.siggi.morganabot.config;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.UUID;

public class Data {
    public transient File file;
    public transient Gson gson;

    public static <T extends Data> T read(File file, Class<T> type, Gson gson) {
        T data;
        try (FileReader reader = new FileReader(file)) {
            data = gson.fromJson(reader, type);
        } catch (Exception e) {
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

    public void save() {
        File parentDirectory = file.getParentFile();
        if (!parentDirectory.exists()) parentDirectory.mkdirs();
        File tmpSaveFile = new File(parentDirectory, file.getName() + UUID.randomUUID() + ".sav");
        try {
            try (FileWriter out = new FileWriter(tmpSaveFile)) {
                out.write(gson.toJson(this));
            }
            tmpSaveFile.renameTo(file);
        } catch (Exception ignored) {
        } finally {
            tmpSaveFile.delete();
        }
    }
}

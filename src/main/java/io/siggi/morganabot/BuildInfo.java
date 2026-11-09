package io.siggi.morganabot;

import java.io.InputStream;
import java.util.Properties;

/**
 * Version and build time, from {@code build-info.properties} (filled in by Maven resource filtering).
 */
public final class BuildInfo {
    private static final Properties properties = new Properties();

    static {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) properties.load(in);
        } catch (Exception ignored) {
        }
    }

    private BuildInfo() {
    }

    public static String version() {
        return value("version");
    }

    public static String buildTime() {
        return value("buildTime");
    }

    private static String value(String key) {
        String value = properties.getProperty(key);
        // Unfiltered (e.g. running from an IDE without Maven): no value.
        return value == null || value.startsWith("${") ? null : value;
    }
}

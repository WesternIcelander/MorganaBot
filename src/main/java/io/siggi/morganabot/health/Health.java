package io.siggi.morganabot.health;

import java.io.File;

/**
 * Static access to the running bot's {@link SystemHealth}, for code that has no reference to the bot
 * ({@code Data.save()}, the uncaught-exception handler). Does nothing until {@link #install} is called, so tests
 * and tools that don't start the bot aren't affected.
 */
public final class Health {
    private static volatile SystemHealth instance;

    private Health() {
    }

    public static void install(SystemHealth health) {
        instance = health;
    }

    public static SystemHealth get() {
        return instance;
    }

    public static void saved(File file) {
        SystemHealth health = instance;
        if (health != null) health.saved(file);
    }

    public static void saveFailed(File file, Throwable error) {
        SystemHealth health = instance;
        if (health != null) {
            health.saveFailed(file, error);
        } else {
            System.err.println("Could not save " + file + ": " + error);
        }
    }

    public static void log(Component component, Severity severity, String message, Throwable error) {
        SystemHealth health = instance;
        if (health != null) {
            health.log(component, severity, message, error);
        } else {
            System.err.println("[" + severity + "] " + component.label() + ": " + message);
            if (error != null) error.printStackTrace();
        }
    }

    /**
     * Logs an unexpected exception (one that would otherwise only be printed).
     */
    public static void unexpected(String what, Throwable error) {
        log(Component.BOT, Severity.ERROR, what + ": " + describe(error), error);
    }

    public static String describe(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}

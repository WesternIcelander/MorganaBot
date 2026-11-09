package io.siggi.morganabot.health;

import java.util.Objects;

/**
 * One line of the system log. Repeats (same component, severity and message) are combined into one entry: {@link #count}
 * goes up and {@link #lastSeen} moves forward.
 */
public class SystemLogEntry {
    /** Unique ID, used to dismiss this entry. */
    public String id;
    /** {@link Component} name. */
    public String component;
    /** {@link Severity} name. */
    public String severity;
    public String message;
    /** Stack trace of the latest occurrence, or null. */
    public String detail;
    /** First seen (epoch millis). */
    public long time;
    /** Last seen (epoch millis). */
    public long lastSeen;
    public int count;
    /** Only set on tombstone lines in the file, which remove the entry with this ID. */
    public Boolean dismissed;

    public SystemLogEntry() {
    }

    SystemLogEntry copy() {
        SystemLogEntry copy = new SystemLogEntry();
        copy.id = id;
        copy.component = component;
        copy.severity = severity;
        copy.message = message;
        copy.detail = detail;
        copy.time = time;
        copy.lastSeen = lastSeen;
        copy.count = count;
        copy.dismissed = dismissed;
        return copy;
    }

    String key() {
        return key(component, severity, message);
    }

    static String key(String component, String severity, String message) {
        return component + "\u0000" + severity + "\u0000" + Objects.toString(message);
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + component + ": " + message + (count > 1 ? " (x" + count + ")" : "");
    }
}

package io.siggi.morganabot.health;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Bot-wide log of errors and warnings: {@code data/system-log.jsonl}.
 * <p>
 * The file is append-only: every new or updated entry is appended as a full line, and a dismissal is appended as a
 * tombstone ({@code {"id":..., "dismissed":true}}). When the file is read, the last line for each ID wins. Once the
 * file has grown to twice {@link #MAX_ENTRIES} lines, it's rewritten (atomically) with just the current entries.
 * <p>
 * Repeats are combined (same component, severity and message), and only the {@link #MAX_ENTRIES} most recently seen
 * entries are kept. All methods are thread-safe. A missing file means an empty log; unreadable lines are skipped.
 * Write failures never throw: they're printed and passed to the write-failure callback (used for the Storage card).
 */
public class SystemLog {
    public static final int MAX_ENTRIES = 1000;
    static final int MAX_MESSAGE = 1000;
    static final int MAX_DETAIL = 16000;

    private final File file;
    private final Gson gson;
    private final int maxEntries;
    /** By ID, oldest first. */
    private final LinkedHashMap<String, SystemLogEntry> entries = new LinkedHashMap<>();
    /** Combining key → ID. */
    private final Map<String, String> byKey = new HashMap<>();
    private int linesInFile;
    private volatile Consumer<IOException> writeFailureListener;

    public SystemLog(File file, Gson gson) {
        this(file, gson, MAX_ENTRIES);
    }

    SystemLog(File file, Gson gson, int maxEntries) {
        this.file = file;
        this.gson = gson;
        this.maxEntries = maxEntries;
        load();
    }

    public File getFile() {
        return file;
    }

    public void setWriteFailureListener(Consumer<IOException> listener) {
        this.writeFailureListener = listener;
    }

    /**
     * Records an entry, combining it with an identical earlier one.
     *
     * @return a copy of the stored entry
     */
    public SystemLogEntry record(Component component, Severity severity, String message, Throwable error) {
        return record(component, severity, message, error == null ? null : stackTrace(error), System.currentTimeMillis());
    }

    synchronized SystemLogEntry record(Component component, Severity severity, String message, String detail, long now) {
        message = truncate(message == null ? "(no message)" : message, MAX_MESSAGE);
        detail = detail == null ? null : truncate(detail, MAX_DETAIL);
        String key = SystemLogEntry.key(component.name(), severity.name(), message);
        String existingId = byKey.get(key);
        SystemLogEntry entry = existingId == null ? null : entries.get(existingId);
        if (entry == null) {
            entry = new SystemLogEntry();
            entry.id = UUID.randomUUID().toString();
            entry.component = component.name();
            entry.severity = severity.name();
            entry.message = message;
            entry.time = now;
            entry.count = 1;
            byKey.put(key, entry.id);
        } else {
            entries.remove(entry.id);
            entry.count = Math.max(entry.count, 1) + 1;
        }
        entry.lastSeen = Math.max(entry.lastSeen, now);
        if (detail != null) entry.detail = detail;
        entries.put(entry.id, entry); // move to the end: most recently seen
        trimMemory();
        append(entry);
        return entry.copy();
    }

    /**
     * Returns copies of all entries, most recently seen first.
     */
    public synchronized List<SystemLogEntry> list() {
        List<SystemLogEntry> list = new ArrayList<>(entries.size());
        for (SystemLogEntry entry : entries.values()) list.add(entry.copy());
        list.sort(NEWEST_FIRST);
        return list;
    }

    /**
     * @return whether an entry with that ID existed
     */
    public synchronized boolean dismiss(String id) {
        SystemLogEntry removed = entries.remove(id);
        if (removed == null) return false;
        byKey.remove(removed.key());
        SystemLogEntry tombstone = new SystemLogEntry();
        tombstone.id = id;
        tombstone.dismissed = true;
        append(tombstone);
        return true;
    }

    /**
     * @return how many entries were removed
     */
    public synchronized int dismissAll() {
        int count = entries.size();
        entries.clear();
        byKey.clear();
        rewrite();
        return count;
    }

    // --- helpers ---

    private static final Comparator<SystemLogEntry> NEWEST_FIRST =
            Comparator.comparingLong((SystemLogEntry e) -> e.lastSeen).thenComparingLong(e -> e.time).reversed();

    private void trimMemory() {
        if (entries.size() <= maxEntries) return;
        List<SystemLogEntry> sorted = new ArrayList<>(entries.values());
        sorted.sort(NEWEST_FIRST);
        for (SystemLogEntry old : sorted.subList(maxEntries, sorted.size())) {
            entries.remove(old.id);
            byKey.remove(old.key());
        }
    }

    private void load() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            lines = List.of();
        } catch (IOException e) {
            System.err.println("Could not read system log " + file + ", starting empty");
            e.printStackTrace();
            lines = List.of();
        }
        linesInFile = lines.size();
        for (String line : lines) {
            if (line.isBlank()) continue;
            SystemLogEntry entry;
            try {
                entry = gson.fromJson(line, SystemLogEntry.class);
            } catch (JsonParseException e) {
                continue;
            }
            if (entry == null || entry.id == null) continue;
            SystemLogEntry previous = entries.remove(entry.id);
            if (previous != null) byKey.remove(previous.key());
            if (Boolean.TRUE.equals(entry.dismissed)) continue;
            if (entry.component == null || entry.severity == null) continue;
            entries.put(entry.id, entry);
            // If two IDs share a key (shouldn't happen), the later one wins for combining.
            byKey.put(entry.key(), entry.id);
        }
        // Order by last seen so trimming and listing behave the same as before the restart.
        List<SystemLogEntry> sorted = new ArrayList<>(entries.values());
        sorted.sort(NEWEST_FIRST.reversed());
        entries.clear();
        for (SystemLogEntry entry : sorted) entries.put(entry.id, entry);
        trimMemory();
    }

    private void append(SystemLogEntry entry) {
        if (linesInFile + 1 > maxEntries * 2) {
            rewrite();
            return;
        }
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            String line = gson.toJson(entry) + "\n";
            if (endsWithoutNewline()) line = "\n" + line;
            Files.writeString(file.toPath(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            linesInFile++;
        } catch (IOException e) {
            writeFailed(e);
        }
    }

    private void rewrite() {
        List<String> lines = new ArrayList<>(entries.size());
        for (SystemLogEntry entry : entries.values()) lines.add(gson.toJson(entry));
        File parent = file.getAbsoluteFile().getParentFile();
        File tmp = new File(parent, file.getName() + UUID.randomUUID() + ".sav");
        try {
            if (parent != null && !parent.exists()) parent.mkdirs();
            try {
                Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp.toPath());
            }
            linesInFile = lines.size();
        } catch (IOException e) {
            writeFailed(e);
        }
    }

    private boolean endsWithoutNewline() throws IOException {
        if (!file.exists() || file.length() == 0L) return false;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(raf.length() - 1L);
            return raf.read() != '\n';
        }
    }

    private void writeFailed(IOException e) {
        // Never log this into the system log itself: that would just fail again.
        System.err.println("Could not write system log " + file + ": " + e);
        Consumer<IOException> listener = writeFailureListener;
        if (listener != null) {
            try {
                listener.accept(e);
            } catch (Exception ignored) {
            }
        }
    }

    static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    public static String stackTrace(Throwable error) {
        java.io.StringWriter sw = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}

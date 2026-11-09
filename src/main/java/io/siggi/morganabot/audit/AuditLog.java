package io.siggi.morganabot.audit;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.siggi.morganabot.config.ServerInfoStore;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Per-server audit log: {@code <dir>/<guildId>-audit.jsonl}, one JSON {@link AuditEntry} per line, oldest first.
 * <p>
 * Reads and writes happen under the guild's {@link ServerInfoStore} lock. Each file is trimmed to its newest
 * {@link #MAX_ENTRIES} entries. A missing file means no entries, and lines that can't be parsed (e.g. a write cut off
 * by a crash) are skipped.
 */
public class AuditLog {

    public static final int MAX_ENTRIES = 1000;

    /**
     * Looks up names for IDs at the time an entry is recorded. Each method returns null if the name isn't known.
     * Called outside the server lock, so it may do a (short) network lookup.
     */
    public interface Names {
        String channel(long guildId, long channelId);

        String role(long guildId, long roleId);

        String user(long guildId, long userId);

        String twitchLogin(String twitchId);

        Names NONE = new Names() {
            @Override
            public String channel(long guildId, long channelId) {
                return null;
            }

            @Override
            public String role(long guildId, long roleId) {
                return null;
            }

            @Override
            public String user(long guildId, long userId) {
                return null;
            }

            @Override
            public String twitchLogin(String twitchId) {
                return null;
            }
        };
    }

    private final File directory;
    private final Gson gson;
    private final ServerInfoStore store;
    private final Names names;
    private final int maxEntries;

    public AuditLog(File directory, Gson gson, ServerInfoStore store, Names names) {
        this(directory, gson, store, names, MAX_ENTRIES);
    }

    AuditLog(File directory, Gson gson, ServerInfoStore store, Names names, int maxEntries) {
        this.directory = directory;
        this.gson = gson;
        this.store = store;
        this.names = names == null ? Names.NONE : names;
        this.maxEntries = maxEntries;
    }

    public File getFile(long guildId) {
        return new File(directory, guildId + "-audit.jsonl");
    }

    /**
     * Fills in missing names, sets the actor (and the time, if not set yet), and appends {@code entry} to the guild's
     * log. A failure to write is printed and otherwise ignored: the change itself has already been saved, so it
     * shouldn't be reported as failed.
     * <p>
     * Must not be called while inside {@link ServerInfoStore#modify} for the same guild.
     */
    public void record(long guildId, Actor actor, AuditEntry entry) {
        entry.actor = actor;
        if (entry.time == 0L) entry.time = System.currentTimeMillis();
        fillNames(guildId, entry);
        try {
            append(guildId, entry);
        } catch (Exception e) {
            System.err.println("Could not write audit entry for guild " + guildId + ": " + gson.toJson(entry));
            e.printStackTrace();
        }
        if (actor != null && actor.source == Actor.Source.SUPER_ADMIN) {
            System.out.println("Super admin change in guild " + guildId + " by " + actor + ": " + describe(entry));
        }
    }

    /**
     * Appends {@code entry} as is (no name lookup) and trims the file.
     */
    void append(long guildId, AuditEntry entry) {
        String line = gson.toJson(entry) + "\n";
        store.withLock(guildId, () -> {
            try {
                File file = getFile(guildId);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                if (endsWithoutNewline(file)) {
                    // A previous write was cut off; don't glue this entry onto the broken line.
                    line(file, "\n");
                }
                line(file, line);
                trim(file);
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Returns the guild's entries, newest first.
     *
     * @param includeSuperAdminOnly whether to include entries only super admins may see
     */
    public List<AuditEntry> read(long guildId, boolean includeSuperAdminOnly) {
        List<String> lines = store.withLock(guildId, () -> readLines(getFile(guildId)));
        List<AuditEntry> entries = new ArrayList<>(lines.size());
        for (String line : lines) {
            AuditEntry entry = parse(line);
            if (entry == null) continue;
            if (!includeSuperAdminOnly && entry.isSuperAdminOnly()) continue;
            entries.add(entry);
        }
        Collections.reverse(entries);
        return entries;
    }

    // --- helpers ---

    private AuditEntry parse(String line) {
        if (line.isBlank()) return null;
        try {
            AuditEntry entry = gson.fromJson(line, AuditEntry.class);
            if (entry == null) return null;
            if (entry.changes == null) entry.changes = new ArrayList<>();
            if (entry.visibility == null) entry.visibility = AuditEntry.VISIBILITY_SERVER;
            return entry;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private void fillNames(long guildId, AuditEntry entry) {
        fillName(guildId, entry.streamer);
        for (AuditEntry.Change change : entry.changes) {
            fillName(guildId, change.oldValue);
            fillName(guildId, change.newValue);
        }
    }

    private void fillName(long guildId, AuditEntry.Value value) {
        if (value == null || value.name != null || value.id == null || value.kind == null) return;
        try {
            value.name = switch (value.kind) {
                case AuditEntry.Value.CHANNEL -> names.channel(guildId, Long.parseLong(value.id));
                case AuditEntry.Value.ROLE -> names.role(guildId, Long.parseLong(value.id));
                case AuditEntry.Value.USER -> names.user(guildId, Long.parseLong(value.id));
                case AuditEntry.Value.TWITCH -> names.twitchLogin(value.id);
                default -> null;
            };
        } catch (Exception e) {
            // A missing name isn't worth failing over; the ID is still recorded.
            value.name = null;
        }
    }

    private static String describe(AuditEntry entry) {
        StringBuilder sb = new StringBuilder(entry.type);
        if (entry.streamer != null) sb.append(" for streamer ").append(entry.streamer);
        for (AuditEntry.Change change : entry.changes) {
            sb.append("; ").append(change.field).append(": ").append(change.oldValue).append(" -> ").append(change.newValue);
        }
        if (entry.note != null) sb.append("; note: ").append(entry.note);
        return sb.toString();
    }

    private static void line(File file, String text) throws IOException {
        Files.writeString(file.toPath(), text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    private static boolean endsWithoutNewline(File file) throws IOException {
        if (!file.exists() || file.length() == 0L) return false;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(raf.length() - 1L);
            return raf.read() != '\n';
        }
    }

    private static List<String> readLines(File file) {
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Keeps only the newest {@link #maxEntries} non-blank lines, replacing the file atomically.
     */
    private void trim(File file) throws IOException {
        List<String> lines = new ArrayList<>(readLines(file));
        lines.removeIf(String::isBlank);
        if (lines.size() <= maxEntries) return;
        List<String> kept = lines.subList(lines.size() - maxEntries, lines.size());
        File tmp = new File(file.getParentFile(), file.getName() + UUID.randomUUID() + ".sav");
        try {
            Files.write(tmp.toPath(), kept, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }
}

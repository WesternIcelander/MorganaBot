package io.siggi.morganabot.audit;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.siggi.morganabot.config.ServerInfoStore;
import io.siggi.morganabot.health.Health;

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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * Repeated {@link AuditEntry#BLOCKED_JOIN} attempts from the same server are combined into one entry while each
     * comes within this long of the previous one.
     */
    public static final long JOIN_COMBINE_WINDOW = 60L * 60L * 1000L;

    private static final Pattern AUDIT_FILE = Pattern.compile("([0-9]{1,19})-audit\\.jsonl");

    /** Entry types that end a run of combined join attempts (the block changed in between). */
    private static final Set<String> BLOCK_CHANGES = Set.of(AuditEntry.SERVER_REMOVE, AuditEntry.SERVER_BLOCK,
            AuditEntry.SERVER_UNBLOCK);

    /**
     * An entry from the merged view, with the server it belongs to.
     */
    public record GuildEntry(long guildId, AuditEntry entry) {
    }

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
            Health.saveFailed(getFile(guildId), e);
        }
        // Super-admin-only entries (removing, blocking) print their own log line where they happen.
        if (actor != null && actor.source == Actor.Source.SUPER_ADMIN && !entry.isSuperAdminOnly()) {
            System.out.println("Super admin change in guild " + guildId + " by " + actor + ": " + describe(entry));
        }
    }

    /**
     * Like {@link #record}, but if the guild's latest entry of the same type is from within {@code window} (and the
     * block wasn't changed since), that entry is replaced by this one, with {@link AuditEntry#count} one higher and
     * {@link AuditEntry#firstTime} kept. The combined entry moves to the end of the file, so the order stays by time.
     */
    public void recordCombined(long guildId, Actor actor, AuditEntry entry, long window) {
        entry.actor = actor;
        if (entry.time == 0L) entry.time = System.currentTimeMillis();
        fillNames(guildId, entry);
        try {
            store.withLock(guildId, () -> {
                try {
                    combineLocked(guildId, entry, window);
                    return null;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (Exception e) {
            System.err.println("Could not write audit entry for guild " + guildId + ": " + gson.toJson(entry));
            Health.saveFailed(getFile(guildId), e);
        }
    }

    private void combineLocked(long guildId, AuditEntry entry, long window) throws IOException {
        File file = getFile(guildId);
        List<String> lines = new ArrayList<>(readLines(file));
        lines.removeIf(String::isBlank);
        int found = -1;
        AuditEntry previous = null;
        for (int i = lines.size() - 1; i >= 0; i--) {
            AuditEntry e = parse(lines.get(i));
            if (e == null) continue;
            if (e.time < entry.time - window) break;
            if (BLOCK_CHANGES.contains(e.type)) break;
            if (entry.type.equals(e.type)) {
                found = i;
                previous = e;
                break;
            }
        }
        if (previous == null) {
            entry.count = null;
            entry.firstTime = null;
            String line = gson.toJson(entry) + "\n";
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (endsWithoutNewline(file)) line(file, "\n");
            line(file, line);
            trim(file);
        } else {
            entry.count = previous.times() + 1;
            entry.firstTime = previous.firstTime != null ? previous.firstTime : previous.time;
            lines.remove(found);
            lines.add(gson.toJson(entry));
            if (lines.size() > maxEntries) lines = lines.subList(lines.size() - maxEntries, lines.size());
            replace(file, lines);
        }
        Health.saved(file);
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
                Health.saved(file);
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

    /**
     * The IDs of every server with an audit file, including servers the bot has left.
     */
    public List<Long> guildIds() {
        List<Long> ids = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files == null) return ids;
        for (File file : files) {
            Matcher matcher = AUDIT_FILE.matcher(file.getName());
            if (!matcher.matches() || !file.isFile()) continue;
            try {
                ids.add(Long.parseLong(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Too long to be a snowflake.
            }
        }
        ids.sort(null);
        return ids;
    }

    /**
     * Every server's entries merged, newest first (for the super admin audit page). Each file is read under its own
     * server's lock; one that can't be read is skipped.
     *
     * @param includeSuperAdminOnly whether to include entries only super admins may see
     */
    public List<GuildEntry> readAll(boolean includeSuperAdminOnly) {
        List<GuildEntry> all = new ArrayList<>();
        for (long guildId : guildIds()) {
            List<AuditEntry> entries;
            try {
                entries = read(guildId, includeSuperAdminOnly);
            } catch (RuntimeException e) {
                continue;
            }
            for (AuditEntry entry : entries) all.add(new GuildEntry(guildId, entry));
        }
        // Stable, so entries with the same time keep their order within a server.
        all.sort(Comparator.comparingLong((GuildEntry g) -> g.entry().time).reversed());
        return all;
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
        fillName(guildId, entry.subject);
        if (entry.facts != null) {
            for (AuditEntry.Fact fact : entry.facts) fillName(guildId, fact.value);
        }
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
        if (entry.subject != null) sb.append(" for ").append(entry.subject);
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
        replace(file, lines.subList(lines.size() - maxEntries, lines.size()));
    }

    /**
     * Replaces the file's lines atomically.
     */
    private static void replace(File file, List<String> kept) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(file.getParentFile(), file.getName() + UUID.randomUUID() + ".sav");
        try {
            Files.write(tmp.toPath(), kept, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }
}

package io.siggi.morganabot.problems;

import com.google.gson.Gson;
import io.siggi.morganabot.config.ServerInfoStore;
import io.siggi.morganabot.health.Health;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Per-server log of failed (or skipped) Discord actions: {@code <dir>/<guildId>-problems.json}.
 * <p>
 * Reads and writes happen under the guild's {@link ServerInfoStore} lock. Identical failures are combined into one
 * entry with a count and last-seen time, and each file keeps the {@link #MAX_ENTRIES} most recently seen entries.
 * A missing file means no problems. A file that can't be parsed is reported and treated as empty, since losing old
 * failure reports is better than failing to record new ones.
 */
public class ServerProblems {

    public static final int MAX_ENTRIES = 100;

    /** File contents. */
    static class ProblemFile {
        List<Problem> problems = new ArrayList<>();
    }

    private final File directory;
    private final Gson gson;
    private final ServerInfoStore store;
    private final int maxEntries;

    public ServerProblems(File directory, Gson gson, ServerInfoStore store) {
        this(directory, gson, store, MAX_ENTRIES);
    }

    ServerProblems(File directory, Gson gson, ServerInfoStore store, int maxEntries) {
        this.directory = directory;
        this.gson = gson;
        this.store = store;
        this.maxEntries = maxEntries;
    }

    public File getFile(long guildId) {
        return new File(directory, guildId + "-problems.json");
    }

    /**
     * Records {@code problem}, combining it with an identical existing entry if there is one. Never throws: a
     * failure to write is printed, since there's nowhere else to report it.
     *
     * @return the stored entry (the combined one if it was merged), or null if it couldn't be saved
     */
    public Problem record(long guildId, Problem problem) {
        return record(guildId, problem, System.currentTimeMillis());
    }

    Problem record(long guildId, Problem problem, long now) {
        System.err.println("Problem in guild " + guildId + ": " + problem);
        try {
            return store.withLock(guildId, () -> {
                ProblemFile data = load(guildId);
                Problem stored = null;
                for (Problem existing : data.problems) {
                    if (existing.sameAs(problem)) {
                        stored = existing;
                        break;
                    }
                }
                if (stored == null) {
                    stored = problem;
                    if (stored.id == null) stored.id = UUID.randomUUID().toString();
                    if (stored.time == 0L) stored.time = now;
                    stored.lastSeen = now;
                    stored.count = 1;
                    data.problems.add(stored);
                } else {
                    stored.count = Math.max(stored.count, 1) + 1;
                    stored.lastSeen = Math.max(stored.lastSeen, now);
                    // Keep the newest names, e.g. after a channel was renamed.
                    if (problem.target != null && problem.target.name != null && stored.target != null) {
                        stored.target.name = problem.target.name;
                    }
                    if (problem.streamer != null && problem.streamer.name != null && stored.streamer != null) {
                        stored.streamer.name = problem.streamer.name;
                    }
                }
                trim(data);
                save(guildId, data);
                return stored;
            });
        } catch (Exception e) {
            System.err.println("Could not write problem entry for guild " + guildId);
            Health.saveFailed(getFile(guildId), e);
            return null;
        }
    }

    /**
     * Returns the guild's problems, most recently seen first.
     */
    public List<Problem> list(long guildId) {
        List<Problem> problems = store.withLock(guildId, () -> load(guildId).problems);
        problems.sort(NEWEST_FIRST);
        return problems;
    }

    /**
     * Removes one entry.
     *
     * @return whether an entry with that ID existed
     */
    public boolean dismiss(long guildId, String problemId) {
        return store.withLock(guildId, () -> {
            ProblemFile data = load(guildId);
            boolean removed = data.problems.removeIf(p -> p.id != null && p.id.equals(problemId));
            if (removed) save(guildId, data);
            return removed;
        });
    }

    /**
     * Removes every entry.
     *
     * @return how many entries were removed
     */
    public int dismissAll(long guildId) {
        return store.withLock(guildId, () -> {
            ProblemFile data = load(guildId);
            int count = data.problems.size();
            if (count == 0) return 0;
            try {
                Files.deleteIfExists(getFile(guildId).toPath());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return count;
        });
    }

    // --- helpers ---

    private static final Comparator<Problem> NEWEST_FIRST =
            Comparator.comparingLong((Problem p) -> p.lastSeen).thenComparingLong(p -> p.time).reversed();

    private void trim(ProblemFile data) {
        if (data.problems.size() <= maxEntries) return;
        data.problems.sort(NEWEST_FIRST);
        data.problems = new ArrayList<>(data.problems.subList(0, maxEntries));
    }

    private ProblemFile load(long guildId) {
        File file = getFile(guildId);
        ProblemFile data;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            data = gson.fromJson(reader, ProblemFile.class);
        } catch (NoSuchFileException e) {
            data = null;
        } catch (Exception e) {
            System.err.println("Could not read problem file " + file + ", starting a new one");
            e.printStackTrace();
            data = null;
        }
        if (data == null) data = new ProblemFile();
        if (data.problems == null) data.problems = new ArrayList<>();
        data.problems.removeIf(p -> p == null);
        return data;
    }

    private void save(long guildId, ProblemFile data) {
        File file = getFile(guildId);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, file.getName() + UUID.randomUUID() + ".sav");
        try {
            try {
                Files.writeString(tmp.toPath(), gson.toJson(data), StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp.toPath());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Health.saved(file);
    }
}

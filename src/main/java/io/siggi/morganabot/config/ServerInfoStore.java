package io.siggi.morganabot.config;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps one cached {@link ServerInfo} per guild ({@code <dir>/<guildId>.json}), with a lock per guild.
 * <p>
 * All reads and writes of server files go through here:
 * <ul>
 *     <li>{@link #get(long)} returns an independent copy, safe to read from any thread. Changing it does nothing.</li>
 *     <li>{@link #modify(long, Function)} runs a change under the guild's lock on a working copy, then saves it
 *     (only if something actually changed) and makes it the cached value. If the function throws, nothing is
 *     saved and the cache is left as it was.</li>
 * </ul>
 * Before every access, the file's modified time and size on disk are compared with what the cache was loaded
 * from or last saved as. If they differ (e.g. the file was edited by hand while the bot runs), the file is
 * reloaded first, so hand edits are picked up and never overwritten by a stale cache.
 * <p>
 * A file that exists but can't be parsed throws {@link IllegalStateException} instead of being treated as empty,
 * so a half-written hand edit is never replaced with defaults.
 */
public class ServerInfoStore {
    private final File directory;
    private final Gson gson;
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

    public ServerInfoStore(File directory, Gson gson) {
        this.directory = directory;
        this.gson = gson;
    }

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        ServerInfo info;
        FileStamp stamp;
    }

    private record FileStamp(boolean exists, long modifiedNanos, long size, Object fileKey) {
    }

    public File getFile(long guildId) {
        return new File(directory, guildId + ".json");
    }

    /**
     * Returns a copy of the guild's current settings. The copy is not tied to the store: changes to it are not saved.
     */
    public ServerInfo get(long guildId) {
        Entry entry = entry(guildId);
        entry.lock.lock();
        try {
            return copy(current(guildId, entry));
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * Applies {@code fn} to the guild's settings under the guild's lock and saves the result if anything changed.
     * The {@link ServerInfo} passed to {@code fn} must not be kept or used after {@code fn} returns; use
     * {@link #copy(ServerInfo)} inside {@code fn} to hand data out.
     * <p>
     * Must not be called from inside another {@code modify} or {@code get} for the same guild on the same thread.
     *
     * @return whatever {@code fn} returns
     */
    public <T> T modify(long guildId, Function<ServerInfo, T> fn) {
        Entry entry = entry(guildId);
        if (entry.lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Nested modify for guild " + guildId);
        }
        entry.lock.lock();
        try {
            ServerInfo cached = current(guildId, entry);
            String before = gson.toJson(cached);
            ServerInfo working = copy(cached);
            T result = fn.apply(working);
            String after = gson.toJson(working);
            if (!before.equals(after)) {
                working.save();
                entry.info = working;
                entry.stamp = stamp(working.file);
            }
            return result;
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * Runs {@code fn} while holding the guild's lock, for other per-server files (e.g. the audit log) that must be
     * written under the same lock. The lock is reentrant, but {@link #modify} must not be called from inside
     * {@code fn}.
     *
     * @return whatever {@code fn} returns
     */
    public <T> T withLock(long guildId, Supplier<T> fn) {
        Entry entry = entry(guildId);
        entry.lock.lock();
        try {
            return fn.get();
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * The IDs of every guild that has a settings file ({@code <dir>/<guildId>.json}), whether or not the bot is in it.
     * Other files in the directory (audit logs, problem lists, backups) are left out.
     */
    public List<Long> guildIds() {
        List<Long> ids = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files == null) return ids;
        for (File file : files) {
            Matcher matcher = GUILD_FILE.matcher(file.getName());
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

    private static final Pattern GUILD_FILE = Pattern.compile("([0-9]{1,19})\\.json");

    /**
     * Makes an independent deep copy of {@code info}, bound to the same file.
     */
    public ServerInfo copy(ServerInfo info) {
        ServerInfo copy = gson.fromJson(gson.toJson(info), ServerInfo.class);
        copy.file = info.file;
        copy.gson = gson;
        return copy;
    }

    private Entry entry(long guildId) {
        return entries.computeIfAbsent(guildId, id -> new Entry());
    }

    /**
     * Returns the cached value, reloading it first if the file on disk changed. Caller holds the lock.
     */
    private ServerInfo current(long guildId, Entry entry) {
        File file = getFile(guildId);
        FileStamp stamp = stamp(file);
        if (entry.info == null || !stamp.equals(entry.stamp)) {
            entry.info = load(file);
            entry.stamp = stamp;
        }
        return entry.info;
    }

    private ServerInfo load(File file) {
        ServerInfo info;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            info = gson.fromJson(reader, ServerInfo.class);
            if (info == null) {
                throw new IllegalStateException("Server file is empty: " + file);
            }
        } catch (NoSuchFileException e) {
            info = new ServerInfo();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read server file " + file, e);
        }
        info.file = file;
        info.gson = gson;
        return info;
    }

    private static FileStamp stamp(File file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            return new FileStamp(
                    true,
                    attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                    attributes.size(),
                    attributes.fileKey()
            );
        } catch (IOException e) {
            return new FileStamp(false, 0L, 0L, null);
        }
    }
}

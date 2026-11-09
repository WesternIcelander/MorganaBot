package io.siggi.morganabot.diagnose;

/**
 * One Diagnose check: how it went, why, and how to fix it. Plain fields so it serializes to JSON as is.
 */
public final class Check {

    public enum Level {
        /** Works as intended. */
        OK,
        /** It posts, but something is degraded, e.g. a mention won't ping or the live role won't be given. */
        WARNING,
        /** Nothing will post. */
        ERROR;

        public Level worst(Level other) {
            return other.ordinal() > ordinal() ? other : this;
        }
    }

    /** Short machine-readable name of the check, e.g. {@code channel.send}. */
    public final String code;
    public final Level level;
    /** Plain-English result. */
    public final String message;
    /** What to do about it, or null if nothing is needed. */
    public final String fix;

    public Check(String code, Level level, String message, String fix) {
        this.code = code;
        this.level = level;
        this.message = message;
        this.fix = fix;
    }

    public static Check ok(String code, String message) {
        return new Check(code, Level.OK, message, null);
    }

    public static Check warning(String code, String message, String fix) {
        return new Check(code, Level.WARNING, message, fix);
    }

    public static Check error(String code, String message, String fix) {
        return new Check(code, Level.ERROR, message, fix);
    }

    /** The same check at a different level. */
    Check withLevel(Level newLevel) {
        return new Check(code, newLevel, message, fix);
    }

    @Override
    public String toString() {
        return level + " " + code + ": " + message + (fix == null ? "" : " (" + fix + ")");
    }
}

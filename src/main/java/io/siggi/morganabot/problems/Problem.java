package io.siggi.morganabot.problems;

import java.util.Objects;

/**
 * One recorded failure (or skipped action) in a server's problem log. Identical failures are combined into one
 * entry: {@link #count} goes up and {@link #lastSeen} moves forward.
 */
public class Problem {

    public static final String SEND_LIVE_MESSAGE = "send-live-message";
    public static final String DELETE_LIVE_MESSAGE = "delete-live-message";
    public static final String ADD_LIVE_ROLE = "add-live-role";
    public static final String REMOVE_LIVE_ROLE = "remove-live-role";
    /** Publishing a live alert to the servers following its announcement channel. */
    public static final String PUBLISH_LIVE_MESSAGE = "publish-live-message";

    /** Unique ID, used to dismiss this entry. */
    public String id;
    /** When this problem was first seen (epoch millis). */
    public long time;
    /** When this problem was last seen (epoch millis). */
    public long lastSeen;
    /** How many times it happened between {@link #time} and {@link #lastSeen}. */
    public int count;
    /** What the bot was trying to do, one of the action constants above. */
    public String action;
    /** What the action was aimed at: a channel, role or member. */
    public Target target;
    /** The streamer the action was for, if any. */
    public Target streamer;
    /** Readable reason, e.g. "Missing permission Send Messages in #live". */
    public String reason;

    public Problem() {
    }

    public Problem(String action, Target target, Target streamer, String reason) {
        this.action = action;
        this.target = target;
        this.streamer = streamer;
        this.reason = reason;
    }

    /**
     * Whether {@code other} describes the same failure, so they're combined into one entry. Names are ignored, so a
     * renamed channel still matches by ID.
     */
    public boolean sameAs(Problem other) {
        return Objects.equals(action, other.action)
                && Target.sameId(target, other.target)
                && Target.sameId(streamer, other.streamer)
                && Objects.equals(reason, other.reason);
    }

    public static class Target {
        public static final String CHANNEL = "channel";
        public static final String ROLE = "role";
        public static final String MEMBER = "member";
        public static final String STREAMER = "streamer";

        public String kind;
        /** Discord snowflake or Twitch user ID, as a string. */
        public String id;
        /** Name at the time it was recorded; may be null. */
        public String name;

        public Target() {
        }

        public Target(String kind, String id, String name) {
            this.kind = kind;
            this.id = id;
            this.name = name;
        }

        public static Target channel(long id, String name) {
            return new Target(CHANNEL, Long.toString(id), name);
        }

        public static Target role(long id, String name) {
            return new Target(ROLE, Long.toString(id), name);
        }

        public static Target member(long id, String name) {
            return new Target(MEMBER, Long.toString(id), name);
        }

        public static Target streamer(String twitchId, String name) {
            return new Target(STREAMER, twitchId, name);
        }

        static boolean sameId(Target a, Target b) {
            if (a == null || b == null) return a == b;
            return Objects.equals(a.kind, b.kind) && Objects.equals(a.id, b.id);
        }

        @Override
        public String toString() {
            return kind + " " + (name == null ? id : name + " (" + id + ")");
        }
    }

    @Override
    public String toString() {
        return action + " -> " + target + (streamer == null ? "" : " for " + streamer) + ": " + reason
                + (count > 1 ? " (x" + count + ")" : "");
    }
}

package io.siggi.morganabot;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mentions written in a live message template: {@code @everyone}, {@code @here}, role mentions
 * ({@code <@&id>}) and user mentions ({@code <@id>} or {@code <@!id>}). Template variables such as
 * {@code ${discord_name}} aren't mentions here; they're filled in later.
 * <p>
 * Used by Diagnose to check which mentions will ping, and by the save-time mention check (step 8).
 */
public final class Mentions {

    private static final Pattern MENTION = Pattern.compile("@(everyone|here)|<@(&|!)?(\\d{1,20})>");

    private final boolean everyone;
    private final boolean here;
    private final Set<Long> roles;
    private final Set<Long> users;

    private Mentions(boolean everyone, boolean here, Set<Long> roles, Set<Long> users) {
        this.everyone = everyone;
        this.here = here;
        this.roles = Collections.unmodifiableSet(roles);
        this.users = Collections.unmodifiableSet(users);
    }

    /**
     * Finds the mentions in {@code template}. Each mention is listed once, in order of first appearance. A null
     * template has no mentions.
     */
    public static Mentions parse(String template) {
        boolean everyone = false;
        boolean here = false;
        Set<Long> roles = new LinkedHashSet<>();
        Set<Long> users = new LinkedHashSet<>();
        if (template != null) {
            Matcher matcher = MENTION.matcher(template);
            while (matcher.find()) {
                if (matcher.group(1) != null) {
                    if (matcher.group(1).equals("everyone")) everyone = true;
                    else here = true;
                    continue;
                }
                long id;
                try {
                    id = Long.parseLong(matcher.group(3));
                } catch (NumberFormatException e) {
                    continue; // too big to be a snowflake, so Discord won't treat it as a mention either
                }
                if ("&".equals(matcher.group(2))) roles.add(id);
                else users.add(id);
            }
        }
        return new Mentions(everyone, here, roles, users);
    }

    /** {@code @everyone} appears in the template. */
    public boolean everyone() {
        return everyone;
    }

    /** {@code @here} appears in the template. */
    public boolean here() {
        return here;
    }

    /** Role IDs mentioned, in order of first appearance. */
    public Set<Long> roles() {
        return roles;
    }

    /** User IDs mentioned, in order of first appearance. */
    public Set<Long> users() {
        return users;
    }

    public boolean isEmpty() {
        return !everyone && !here && roles.isEmpty() && users.isEmpty();
    }
}

package io.siggi.morganabot.health;

/**
 * A part of the bot that the system health page reports on. The ones with a {@linkplain #hasCard() card} get a live
 * green/amber/red status; {@link #BOT} is only used for system log entries (unexpected errors, command failures).
 */
public enum Component {
    DISCORD("Discord", true),
    TWITCH_API("Twitch API", true),
    EVENTSUB("Twitch EventSub", true),
    STORAGE("Storage", true),
    BOT("Bot", false);

    private final String label;
    private final boolean card;

    Component(String label, boolean card) {
        this.label = label;
        this.card = card;
    }

    public String label() {
        return label;
    }

    public boolean hasCard() {
        return card;
    }
}

// Audit log entries in words (io.siggi.morganabot.audit.AuditEntry). No DOM here, so this runs under the Node tests.

import type { AuditActor, AuditChange, AuditEntry, AuditValue } from "./api.js";

/** Entry types and their labels, for the filter and the list (AuditEntry's type constants). */
export const AUDIT_TYPES: Record<string, string> = {
    liveChannel: "Live channel",
    liveRole: "Live role",
    deleteOnOffline: "Delete on offline",
    notificationCooldown: "Notification cooldown",
    serverTemplate: "Server live message",
    streamerAdd: "Streamer added",
    streamerRemove: "Streamer removed",
    streamerChannelOverride: "Streamer channel",
    streamerDiscordUser: "Streamer's Discord user",
    streamerTemplate: "Streamer live message",
    streamerChannelPin: "Streamer channel pin",
    selfServiceSet: "Self-service: set Twitch channel",
    selfServiceRemove: "Self-service: removed",
    selfServicePause: "Self-service: paused",
    selfServiceResume: "Self-service: resumed",
    selfServiceRoles: "Self-service roles",
    selfServiceExpiry: "Self-service removal time",
    adminRoles: "Admin roles",
    messageRules: "Message permissions",
};

export function typeLabel(type: string): string {
    return AUDIT_TYPES[type] ?? type;
}

const FIELDS: Record<string, string> = {
    channel: "Channel",
    role: "Role",
    deleteOnOffline: "Delete on offline",
    cooldownMillis: "Cooldown",
    template: "Message",
    twitch: "Twitch channel",
    discordUser: "Discord user",
    pinned: "Channel pinned",
    paused: "Paused",
    adminRole: "Admin role",
    rule: "Rule",
    allowEveryone: "@everyone/@here",
    mentionRole: "Mentionable role",
    removeAfterDays: "Remove after (days)",
};

export function fieldLabel(field: string): string {
    return FIELDS[field] ?? field;
}

/** Who made the change, e.g. "alice via /setlivechannel", "Bot admin siggi", "MorganaBot (automatic)". */
export function actorText(actor: AuditActor | null): string {
    if (actor === null) return "Unknown";
    const name = actor.name ?? (actor.userId !== null ? "user " + actor.userId : "unknown");
    switch (actor.source) {
        case "command":
            return name + (actor.command !== null ? " via " + actor.command : " via a command");
        case "dashboard":
            return name + " (dashboard)";
        case "superAdmin":
            return "Bot admin " + name;
        case "selfService":
            return name + (actor.command !== null ? " via " + actor.command : " (self-service, dashboard)");
        case "system":
            return "MorganaBot (automatic)";
        default:
            return name;
    }
}

/** Milliseconds as a short duration, e.g. "1 h 30 min", "45 s", "0 s". */
export function duration(ms: number): string {
    let seconds = Math.round(ms / 1000);
    if (seconds <= 0) return "0 s";
    const parts: string[] = [];
    const days = Math.floor(seconds / 86400);
    seconds -= days * 86400;
    const hours = Math.floor(seconds / 3600);
    seconds -= hours * 3600;
    const minutes = Math.floor(seconds / 60);
    seconds -= minutes * 60;
    if (days > 0) parts.push(days + " d");
    if (hours > 0) parts.push(hours + " h");
    if (minutes > 0) parts.push(minutes + " min");
    if (seconds > 0) parts.push(seconds + " s");
    return parts.join(" ");
}

/** What an empty (null) value means for a field of an entry type. */
function emptyText(field: string, type: string): string {
    if (field === "channel") return type === "liveChannel" ? "none" : "server default";
    if (field === "template") return type === "serverTemplate" ? "the default message" : "the server-wide message";
    return "none";
}

/**
 * A value in words. `field` and `type` (the entry's) decide how numbers and empty values read, e.g. an empty streamer
 * channel is "server default".
 */
export function valueText(value: AuditValue | null, field = "", type = ""): string {
    if (value === null) return emptyText(field, type);
    const name = value.name ?? null;
    const id = value.id ?? null;
    switch (value.kind) {
        case "channel":
            if (id === null || id === "0") return emptyText(field, type);
            return (name !== null ? "#" + name : "channel") + " (" + id + ")";
        case "role":
            if (id === null || id === "0") return "none";
            return (name !== null ? "@" + name : "role") + " (" + id + ")";
        case "user":
            if (id === null || id === "0") return "none";
            return (name !== null ? "@" + name : "user") + " (" + id + ")";
        case "twitch":
            return name !== null ? name + " (" + (id ?? "?") + ")" : "Twitch account " + (id ?? "?");
        case "boolean":
            return value.value === "true" ? "on" : "off";
        case "number": {
            const n = Number(value.value ?? "0");
            if (field === "cooldownMillis") return duration(n);
            if (field === "removeAfterDays") return n === 0 ? "never" : n + (n === 1 ? " day" : " days");
            return String(value.value ?? "");
        }
        case "text":
            return value.value ?? emptyText(field, type);
        default:
            return value.value ?? name ?? id ?? "";
    }
}

/** Whether a change holds a template, shown as a block of text rather than inline. */
export function isTemplate(change: AuditChange): boolean {
    return change.field === "template";
}

/** A change as one line, e.g. "Channel: #old (1) → #new (2)"; list items read "added"/"removed". */
export function changeText(change: AuditChange, type = ""): string {
    const label = fieldLabel(change.field);
    const oldValue = change.old ?? null;
    const newValue = change.new ?? null;
    const from = valueText(oldValue, change.field, type);
    const to = valueText(newValue, change.field, type);
    const added = oldValue === null && newValue !== null;
    const removed = newValue === null && oldValue !== null;
    if (change.field === "adminRole" || change.field === "mentionRole" || change.field === "rule") {
        if (added) return label + " added: " + to;
        if (removed) return label + " removed: " + from;
    }
    if (change.field === "twitch") {
        if (added) return "Added " + to;
        if (removed) return "Removed " + from;
    }
    if (type === "selfServiceRoles" && change.field === "channel") {
        if (added) return "Added to the list, posting to " + to;
        if (removed) return "Removed from the list (was posting to " + from + ")";
    }
    if (type === "messageRules" && change.field === "allowEveryone" && (added || removed)) {
        return label + ": " + (added ? to : from);
    }
    return label + ": " + from + " → " + to;
}

/** Every distinct person (or the bot) in some entries, for the "who" filter: [userId or "system", label]. */
export function actorsOf(entries: readonly AuditEntry[]): [string, string][] {
    const seen = new Map<string, string>();
    for (const entry of entries) {
        const actor = entry.actor;
        if (actor === null || actor.userId === null) continue;
        if (!seen.has(actor.userId)) seen.set(actor.userId, (actor.name ?? "user") + " (" + actor.userId + ")");
    }
    return [...seen.entries()].sort((a, b) => a[1].localeCompare(b[1]));
}

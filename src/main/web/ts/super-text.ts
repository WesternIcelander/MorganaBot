// Super admin views in words: the server switcher's filter and the System page's values. No DOM here, so this runs
// under the Node tests.

import type { AdminGuild, HealthDetail, HealthState, LogSeverity } from "./api.js";
import { duration } from "./audit-text.js";

export interface GuildFilter {
    /** Matches the name (case-insensitive) or the ID. */
    query: string;
    twitchOnly: boolean;
    problemsOnly: boolean;
}

/** The servers that match the switcher's search and filters, in the order given. */
export function filterGuilds(guilds: readonly AdminGuild[], filter: GuildFilter): AdminGuild[] {
    const query = filter.query.trim().toLowerCase();
    return guilds.filter((guild) => {
        if (filter.twitchOnly && !guild.twitchEnabled) return false;
        if (filter.problemsOnly && guild.problems === 0 && guild.error === undefined) return false;
        if (query.length === 0) return true;
        return guild.name.toLowerCase().includes(query) || guild.id.includes(query);
    });
}

/** "1 streamer", "3 streamers". */
export function plural(n: number, word: string): string {
    return n + " " + word + (n === 1 ? "" : "s");
}

/** The switcher's one-line summary of a server, e.g. "1,234 members · Twitch on · 3 streamers · 2 problems". */
export function guildSummary(guild: AdminGuild): string {
    const parts = [
        guild.memberCount.toLocaleString("en-US") + (guild.memberCount === 1 ? " member" : " members"),
        guild.twitchEnabled ? "Twitch on" : "Twitch off",
        plural(guild.streamers, "streamer"),
    ];
    if (guild.problems > 0) parts.push(plural(guild.problems, "problem"));
    return parts.join(" · ");
}

export function stateLabel(state: HealthState): string {
    switch (state) {
        case "GREEN":
            return "OK";
        case "AMBER":
            return "Warning";
        case "RED":
            return "Failing";
        default:
            return "Not checked yet";
    }
}

/** CSS class for a state: ok / warn / error / unknown. */
export function stateClass(state: HealthState): string {
    switch (state) {
        case "GREEN":
            return "ok";
        case "AMBER":
            return "warn";
        case "RED":
            return "error";
        default:
            return "unknown";
    }
}

export function severityLabel(severity: LogSeverity | string): string {
    return severity === "ERROR" ? "Error" : severity === "WARNING" ? "Warning" : severity;
}

/** How long ago (or from now) a time is, e.g. "5 min ago", "in 2 h", "just now". */
export function relative(ms: string | number, now: number): string {
    const value = typeof ms === "string" ? Number(ms) : ms;
    if (!Number.isFinite(value) || value <= 0) return "";
    const diff = now - value;
    if (Math.abs(diff) < 1000) return "just now";
    // Rounded to the largest two units, so it stays short.
    const text = duration(roundForDisplay(Math.abs(diff)));
    return diff > 0 ? text + " ago" : "in " + text;
}

/** Drops the smaller units, keeping at most two (e.g. 1 d 3 h, not 1 d 3 h 12 min 5 s). */
function roundForDisplay(ms: number): number {
    const units = [86_400_000, 3_600_000, 60_000, 1000];
    for (let i = 0; i < units.length; i++) {
        const unit = units[i]!;
        if (ms >= unit) {
            const smaller = units[i + 1] ?? unit;
            return Math.floor(ms / smaller) * smaller;
        }
    }
    return ms;
}

/** Uptime in words, e.g. "3 d 4 h 10 min" (no seconds once it's over an hour). */
export function uptimeText(seconds: number): string {
    if (seconds >= 3600) seconds = Math.floor(seconds / 60) * 60;
    return duration(seconds * 1000);
}

/** A status card detail's value in words (times through `formatTime`, which uses the viewer's locale). */
export function detailText(detail: HealthDetail, now: number, formatTime: (ms: number) => string): string {
    if (detail.kind === "time") {
        const ms = Number(detail.value);
        if (!Number.isFinite(ms) || ms <= 0) return detail.value;
        return formatTime(ms) + " (" + relative(ms, now) + ")";
    }
    if (detail.kind === "number") {
        const n = Number(detail.value);
        return Number.isFinite(n) ? n.toLocaleString("en-US") : detail.value;
    }
    return detail.value;
}

/** The query string for `GET /admin/system/log`. */
export function logQuery(component: string, severity: string, before: string | null): string {
    const params = new URLSearchParams();
    if (component !== "") params.set("component", component);
    if (severity !== "") params.set("severity", severity);
    if (before !== null) params.set("before", before);
    const query = params.toString();
    return query.length > 0 ? "?" + query : "";
}

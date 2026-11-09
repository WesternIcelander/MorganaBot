// Reading Diagnose results and recorded problems: which checks belong to which setting, levels, and labels.
// No DOM here, so this runs under the Node tests too.

import type { Check, CheckLevel, DiagnoseResult, Problem, ProblemTarget, StreamerResult } from "./api.js";

const ORDER: Record<CheckLevel, number> = { OK: 0, WARNING: 1, ERROR: 2 };

/** The worse of two levels. */
export function worse(a: CheckLevel, b: CheckLevel): CheckLevel {
    return ORDER[b] > ORDER[a] ? b : a;
}

/** The worst level of some checks (OK when there are none). */
export function worstLevel(checks: readonly Check[]): CheckLevel {
    let level: CheckLevel = "OK";
    for (const check of checks) level = worse(level, check.level);
    return level;
}

/** Checks whose code is `prefix` or starts with `prefix.`. */
export function checksFor(checks: readonly Check[], ...prefixes: string[]): Check[] {
    return checks.filter((check) => prefixes.some((prefix) => check.code === prefix || check.code.startsWith(prefix + ".")));
}

/** Checks that aren't OK. */
export function issues(checks: readonly Check[]): Check[] {
    return checks.filter((check) => check.level !== "OK");
}

/**
 * Server-wide checks grouped by the setting they're about, so the settings page can show each next to its field.
 * Codes are the Java side's (Diagnose): `defaultChannel.*`, `liveRole.*`, `deleteOnOffline`, `messageRule.*`,
 * `selfService.*`, `twitch.*`.
 */
export const SERVER_CHECK_GROUPS = {
    liveChannel: ["defaultChannel"],
    liveRole: ["liveRole"],
    deleteOnOffline: ["deleteOnOffline"],
    messageRules: ["messageRule"],
    selfService: ["selfService"],
    twitch: ["twitch"],
} as const;

export type ServerCheckGroup = keyof typeof SERVER_CHECK_GROUPS;

/** The server-wide issues (warnings and errors) for one setting. */
export function serverIssues(result: DiagnoseResult | null, group: ServerCheckGroup): Check[] {
    if (result === null) return [];
    return issues(checksFor(result.server, ...SERVER_CHECK_GROUPS[group]));
}

/** A streamer's Diagnose result, or null if they weren't checked. */
export function streamerResult(result: DiagnoseResult | null, twitchId: string): StreamerResult | null {
    if (result === null) return null;
    return result.streamers.find((s) => s.twitchId === twitchId) ?? null;
}

/** Whether there's anything to report: a server-wide or streamer issue, a recorded problem, or Twitch trouble. */
export function hasAnything(result: DiagnoseResult | null, problems: readonly Problem[], twitchTrouble: boolean): boolean {
    if (twitchTrouble || problems.length > 0) return true;
    if (result === null) return false;
    return result.serverLevel !== "OK" || result.streamers.some((s) => s.level !== "OK");
}

/** Newest first, by last seen. */
export function sortProblems(problems: readonly Problem[]): Problem[] {
    return [...problems].sort((a, b) => Number(b.lastSeen) - Number(a.lastSeen));
}

const ACTIONS: Record<string, string> = {
    "send-live-message": "Posting a live message",
    "delete-live-message": "Deleting a live message",
    "add-live-role": "Giving the live role",
    "remove-live-role": "Taking away the live role",
    "publish-live-message": "Publishing a live message to following servers",
};

/** What the bot was trying to do, in words. */
export function actionLabel(action: string): string {
    return ACTIONS[action] ?? action;
}

/** A problem target in words, e.g. "#live", "@Live", "member alice", "streamer bob". */
export function targetLabel(target: ProblemTarget | null): string {
    if (target === null) return "";
    const name = target.name;
    switch (target.kind) {
        case "channel":
            return name !== null ? "#" + name : "channel " + target.id;
        case "role":
            return name !== null ? "@" + name : "role " + target.id;
        case "member":
            return "member " + (name ?? target.id);
        case "streamer":
            return name ?? "Twitch account " + target.id;
        default:
            return name ?? target.id;
    }
}

/** The symbol shown for a level. */
export function levelSymbol(level: CheckLevel): string {
    switch (level) {
        case "OK":
            return "✓";
        case "WARNING":
            return "!";
        case "ERROR":
            return "✕";
    }
}

/** A level in words. */
export function levelText(level: CheckLevel): string {
    switch (level) {
        case "OK":
            return "OK";
        case "WARNING":
            return "Warning";
        case "ERROR":
            return "Error";
    }
}

/**
 * Diagnose messages and fix hints use Discord-style `*bold*` for permission names. Splits text into plain and bold
 * parts, so they can be shown without inserting HTML.
 */
export function boldParts(text: string): { text: string; bold: boolean }[] {
    const parts: { text: string; bold: boolean }[] = [];
    const pattern = /\*([^*\n]+)\*/g;
    let last = 0;
    for (const match of text.matchAll(pattern)) {
        const index = match.index ?? 0;
        if (index > last) parts.push({ text: text.substring(last, index), bold: false });
        parts.push({ text: match[1] ?? "", bold: true });
        last = index + match[0].length;
    }
    if (last < text.length) parts.push({ text: text.substring(last), bold: false });
    return parts;
}

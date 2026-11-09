import { test } from "node:test";
import assert from "node:assert/strict";
import type { AdminGuild, BlockedServer } from "../ts/api.js";
import { blockedByText, blockedName, confirmNameMatches, detailText, filterGuilds, guildSummary, logQuery, relative, stateClass, stateLabel, uptimeText } from "../ts/super-text.js";

const guild = (id: string, name: string, twitchEnabled: boolean, problems: number, extra: Partial<AdminGuild> = {}): AdminGuild =>
    ({ id, name, iconUrl: null, memberCount: 10, twitchEnabled, streamers: 2, problems, ...extra });

const guilds = [
    guild("111", "Alpha Gaming", true, 0),
    guild("222", "beta lounge", false, 3),
    guild("333", "Gamma", true, 1),
    guild("444", "Broken", false, 0, { error: "Couldn't read this server's settings file." }),
];

const ids = (list: AdminGuild[]): string[] => list.map((g) => g.id);

test("server switcher search and filters", () => {
    assert.deepEqual(ids(filterGuilds(guilds, { query: "", twitchOnly: false, problemsOnly: false })), ["111", "222", "333", "444"]);
    assert.deepEqual(ids(filterGuilds(guilds, { query: "GAM", twitchOnly: false, problemsOnly: false })), ["111", "333"], "name, any case");
    assert.deepEqual(ids(filterGuilds(guilds, { query: " 22 ", twitchOnly: false, problemsOnly: false })), ["222"], "by ID");
    assert.deepEqual(ids(filterGuilds(guilds, { query: "", twitchOnly: true, problemsOnly: false })), ["111", "333"]);
    assert.deepEqual(ids(filterGuilds(guilds, { query: "", twitchOnly: false, problemsOnly: true })), ["222", "333", "444"],
        "an unreadable settings file counts as a problem");
    assert.deepEqual(ids(filterGuilds(guilds, { query: "a", twitchOnly: true, problemsOnly: true })), ["333"]);
});

test("server summary line", () => {
    assert.equal(guildSummary(guild("1", "x", true, 0, { memberCount: 1234 })), "1,234 members · Twitch on · 2 streamers");
    assert.equal(guildSummary(guild("1", "x", false, 1, { memberCount: 1, streamers: 1 })), "1 member · Twitch off · 1 streamer · 1 problem");
});

test("status card states", () => {
    assert.equal(stateLabel("GREEN"), "OK");
    assert.equal(stateLabel("RED"), "Failing");
    assert.equal(stateLabel("UNKNOWN"), "Not checked yet");
    assert.equal(stateClass("AMBER"), "warn");
    assert.equal(stateClass("RED"), "error");
});

test("times relative to now, and uptime", () => {
    const now = 10_000_000_000;
    assert.equal(relative(now - 500, now), "just now");
    assert.equal(relative(String(now - 90_000), now), "1 min 30 s ago");
    assert.equal(relative(now - (26 * 3600 + 125) * 1000, now), "1 d 2 h ago", "only the two largest units");
    assert.equal(relative(now + 2 * 3600 * 1000, now), "in 2 h");
    assert.equal(relative("0", now), "");
    assert.equal(uptimeText(45), "45 s");
    assert.equal(uptimeText(3 * 86400 + 4 * 3600 + 10 * 60 + 7), "3 d 4 h 10 min");
});

test("status card details", () => {
    const now = 10_000_000_000;
    const fmt = (ms: number): string => "T" + ms;
    assert.equal(detailText({ key: "lastSync", label: "Last sync", kind: "time", value: String(now - 60_000) }, now, fmt),
        "T" + (now - 60_000) + " (1 min ago)");
    assert.equal(detailText({ key: "subscriptionCount", label: "Subscriptions", kind: "number", value: "12345" }, now, fmt), "12,345");
    assert.equal(detailText({ key: "x", label: "x", kind: "text", value: "[a, b]" }, now, fmt), "[a, b]");
});

test("system log query", () => {
    assert.equal(logQuery("", "", null), "");
    assert.equal(logQuery("TWITCH_API", "", null), "?component=TWITCH_API");
    assert.equal(logQuery("", "ERROR", "1700000000000"), "?severity=ERROR&before=1700000000000");
});

test("remove confirmation needs the exact name", () => {
    assert.equal(confirmNameMatches("Main", "Main"), true);
    assert.equal(confirmNameMatches("  Main ", "Main"), true, "spaces at the ends are ignored");
    assert.equal(confirmNameMatches("main", "Main"), false, "case matters");
    assert.equal(confirmNameMatches("Mai", "Main"), false);
    assert.equal(confirmNameMatches("", ""), false, "never matches an empty name");
    assert.equal(confirmNameMatches("   ", "   "), false);
});

test("blocked server labels", () => {
    const server = (extra: Partial<BlockedServer>): BlockedServer =>
        ({ id: "1", name: "Bad", blockedAt: "5", blockedBy: "9", blockedByName: "siggi", reason: null, inServer: false, ...extra });
    assert.equal(blockedName(server({})), "Bad");
    assert.equal(blockedName(server({ name: null })), "Unknown server");
    assert.equal(blockedByText(server({})), "siggi (9)");
    assert.equal(blockedByText(server({ blockedByName: null })), "User 9");
    assert.equal(blockedByText(server({ blockedBy: null })), "unknown");
});

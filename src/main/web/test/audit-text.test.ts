import { test } from "node:test";
import assert from "node:assert/strict";
import type { AuditActor, AuditEntry } from "../ts/api.js";
import { actorsOf, actorText, attemptsText, changeText, duration, factText, serverLabel, typeLabel, valueText } from "../ts/audit-text.js";

const actor = (source: AuditActor["source"], name: string | null, userId: string | null, command: string | null = null): AuditActor =>
    ({ source, name, userId, command });

test("who made a change", () => {
    assert.equal(actorText(actor("command", "alice", "1", "/setlivechannel")), "alice via /setlivechannel");
    assert.equal(actorText(actor("dashboard", "alice", "1")), "alice (dashboard)");
    assert.equal(actorText(actor("superAdmin", "siggi", "2")), "Bot admin siggi");
    assert.equal(actorText(actor("selfService", "bob", "3", "/settwitchchannel")), "bob via /settwitchchannel");
    assert.equal(actorText(actor("selfService", "bob", "3")), "bob (self-service, dashboard)");
    assert.equal(actorText(actor("system", "system", null)), "MorganaBot (automatic)");
    assert.equal(actorText(null), "Unknown");
});

test("values in words, with empty values depending on the setting", () => {
    assert.equal(valueText({ kind: "channel", id: "5", name: "live" }), "#live (5)");
    assert.equal(valueText(null, "channel", "liveChannel"), "none");
    assert.equal(valueText(null, "channel", "streamerChannelOverride"), "server default");
    assert.equal(valueText(null, "template", "serverTemplate"), "the default message");
    assert.equal(valueText(null, "template", "streamerTemplate"), "the server-wide message");
    assert.equal(valueText({ kind: "role", id: "6", name: null }), "role (6)");
    assert.equal(valueText({ kind: "boolean", value: "true" }), "on");
    assert.equal(valueText({ kind: "number", value: "1800000" }, "cooldownMillis"), "30 min");
    assert.equal(valueText({ kind: "number", value: "0" }, "removeAfterDays"), "never");
    assert.equal(valueText({ kind: "number", value: "1" }, "removeAfterDays"), "1 day");
    assert.equal(valueText({ kind: "twitch", id: "1001", name: "alice" }), "alice (1001)");
    assert.equal(valueText({ kind: "twitch", id: "1001", name: null }), "Twitch account 1001");
});

test("durations", () => {
    assert.equal(duration(0), "0 s");
    assert.equal(duration(45_000), "45 s");
    assert.equal(duration(5_400_000), "1 h 30 min");
    assert.equal(duration(90_061_000), "1 d 1 h 1 min 1 s");
});

test("changes as one line", () => {
    assert.equal(changeText({ field: "channel", old: { kind: "channel", id: "1", name: "a" }, new: { kind: "channel", id: "2", name: "b" } }, "liveChannel"),
        "Channel: #a (1) → #b (2)");
    assert.equal(changeText({ field: "adminRole", old: null, new: { kind: "role", id: "3", name: "Mods" } }, "adminRoles"), "Admin role added: @Mods (3)");
    assert.equal(changeText({ field: "mentionRole", old: { kind: "role", id: "3", name: "Mods" }, new: null }, "messageRules"), "Mentionable role removed: @Mods (3)");
    assert.equal(changeText({ field: "twitch", old: null, new: { kind: "twitch", id: "9", name: "bob" } }, "streamerAdd"), "Added bob (9)");
    assert.equal(changeText({ field: "channel", old: null, new: { kind: "text", value: "server default" } }, "selfServiceRoles"),
        "Added to the list, posting to server default");
    assert.equal(changeText({ field: "channel", old: { kind: "channel", id: "1", name: "a" }, new: null }, "streamerChannelOverride"),
        "Channel: #a (1) → server default");
    assert.equal(changeText({ field: "allowEveryone", old: null, new: { kind: "boolean", value: "false" } }, "messageRules"), "@everyone/@here: off");
});

test("type labels fall back to the raw type", () => {
    assert.equal(typeLabel("liveChannel"), "Live channel");
    assert.equal(typeLabel("serverLeave"), "serverLeave");
});

test("actorsOf lists each person once, without the bot", () => {
    const entry = (a: AuditActor): AuditEntry => ({ time: "1", type: "liveChannel", actor: a, visibility: "server", streamer: null, subject: null, changes: [], note: null });
    const people = actorsOf([entry(actor("command", "bob", "2")), entry(actor("dashboard", "alice", "1")), entry(actor("command", "bob", "2")), entry(actor("system", "system", null))]);
    assert.deepEqual(people, [["1", "alice (1)"], ["2", "bob (2)"]]);
});

test("the Twitch switch reads as enabled/disabled", () => {
    assert.equal(typeLabel("twitchTracking"), "Twitch announcements");
    assert.equal(changeText({ field: "enabled", old: { kind: "boolean", value: "false" }, new: { kind: "boolean", value: "true" } }, "twitchTracking"),
        "Twitch announcements enabled");
    assert.equal(changeText({ field: "enabled", old: { kind: "boolean", value: "true" }, new: { kind: "boolean", value: "false" } }, "twitchTracking"),
        "Twitch announcements disabled");
});

test("super-admin-only entries: removing, blocking and blocked servers coming back", () => {
    assert.equal(typeLabel("serverRemove"), "Bot removed from server");
    assert.equal(typeLabel("serverUnblock"), "Server unblocked");
    assert.equal(typeLabel("blockedJoin"), "Blocked server added the bot");
    assert.equal(typeLabel("blockedStartupLeave"), "Blocked server left at startup");
    assert.equal(changeText({ field: "blocked", old: { kind: "boolean", value: "false" }, new: { kind: "boolean", value: "true" } }, "serverRemove"),
        "Blocked from adding the bot back");
    assert.equal(changeText({ field: "blocked", old: { kind: "boolean", value: "true" }, new: { kind: "boolean", value: "false" } }, "serverUnblock"),
        "Block lifted");
    assert.equal(valueText({ kind: "server", id: "100", name: "Bad" }), "Bad (100)");
    assert.equal(valueText({ kind: "server", id: "100", name: null }), "server (100)");
    assert.equal(factText({ field: "owner", value: { kind: "user", id: "7", name: "bob" } }), "Owner: @bob (7)");
    assert.equal(factText({ field: "memberCount", value: { kind: "number", value: "42" } }), "Members: 42");
    assert.equal(factText({ field: "addedBy", value: { kind: "text", value: "unknown" } }), "Added by: unknown");
    assert.equal(factText({ field: "addedBy", value: null }), "Added by: unknown");
    const entry: AuditEntry = { time: "2000", type: "blockedJoin", actor: null, visibility: "superAdmin", streamer: null, subject: null, changes: [], note: null };
    assert.equal(attemptsText(entry), null);
    assert.equal(attemptsText({ ...entry, count: 1 }), null);
    assert.equal(attemptsText({ ...entry, count: 3, firstTime: "1000" }), "3 attempts");
    assert.equal(serverLabel("100", "Bad"), "Bad (100)");
    assert.equal(serverLabel("100", null), "Server 100");
});

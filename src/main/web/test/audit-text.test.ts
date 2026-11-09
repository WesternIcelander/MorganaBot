import { test } from "node:test";
import assert from "node:assert/strict";
import type { AuditActor, AuditEntry } from "../ts/api.js";
import { actorsOf, actorText, changeText, duration, typeLabel, valueText } from "../ts/audit-text.js";

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

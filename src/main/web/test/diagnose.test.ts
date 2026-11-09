import { test } from "node:test";
import assert from "node:assert/strict";
import type { Check, DiagnoseResult, Problem } from "../ts/api.js";
import {
    actionLabel,
    boldParts,
    checksFor,
    hasAnything,
    serverIssues,
    sortProblems,
    streamerResult,
    targetLabel,
    worstLevel,
} from "../ts/diagnose.js";

const check = (code: string, level: Check["level"]): Check => ({ code, level, message: code, fix: null });

function result(server: Check[], levels: Check["level"][] = []): DiagnoseResult {
    return {
        guildId: "1", time: "1", twitchEnabled: true, server,
        streamers: levels.map((level, i) => ({
            twitchId: String(i + 1), twitchLogin: "s" + (i + 1), discordId: "0", channelId: "0", channel: "#live",
            level, checks: [check("channel.permissions", level)],
        })),
        serverLevel: worstLevel(server), streamersOk: 0, streamersWithWarnings: 0, streamersWithErrors: 0, summary: "",
    };
}

function problem(id: string, lastSeen: string): Problem {
    return { id, time: "1", lastSeen, count: 1, action: "send-live-message", target: null, streamer: null, reason: "x" };
}

test("worstLevel picks the worst, OK when empty", () => {
    assert.equal(worstLevel([]), "OK");
    assert.equal(worstLevel([check("a", "OK"), check("b", "WARNING")]), "WARNING");
    assert.equal(worstLevel([check("a", "ERROR"), check("b", "WARNING")]), "ERROR");
});

test("checksFor matches the code or its sub-codes only", () => {
    const checks = [check("liveRole", "OK"), check("liveRole.hierarchy", "WARNING"), check("liveRoles", "ERROR"), check("defaultChannel.set", "OK")];
    assert.deepEqual(checksFor(checks, "liveRole").map((c) => c.code), ["liveRole", "liveRole.hierarchy"]);
});

test("serverIssues groups server checks by setting and leaves out OK ones", () => {
    const r = result([
        check("defaultChannel.set", "OK"),
        check("defaultChannel.permissions", "ERROR"),
        check("liveRole.hierarchy", "WARNING"),
        check("selfService.role.exists", "WARNING"),
        check("selfService.channel.permissions", "ERROR"),
        check("messageRule.role.exists", "WARNING"),
    ]);
    assert.deepEqual(serverIssues(r, "liveChannel").map((c) => c.code), ["defaultChannel.permissions"]);
    assert.deepEqual(serverIssues(r, "liveRole").map((c) => c.code), ["liveRole.hierarchy"]);
    assert.deepEqual(serverIssues(r, "selfService").map((c) => c.code), ["selfService.role.exists", "selfService.channel.permissions"]);
    assert.deepEqual(serverIssues(r, "messageRules").map((c) => c.code), ["messageRule.role.exists"]);
    assert.deepEqual(serverIssues(r, "deleteOnOffline"), []);
    assert.deepEqual(serverIssues(null, "liveRole"), []);
});

test("hasAnything: Twitch trouble, recorded failures, or a non-OK Diagnose", () => {
    assert.equal(hasAnything(result([check("a", "OK")], ["OK"]), [], false), false);
    assert.equal(hasAnything(result([check("a", "OK")], ["OK", "WARNING"]), [], false), true);
    assert.equal(hasAnything(result([check("a", "ERROR")]), [], false), true);
    assert.equal(hasAnything(null, [problem("p", "1")], false), true);
    assert.equal(hasAnything(null, [], true), true);
    assert.equal(hasAnything(null, [], false), false);
});

test("streamerResult finds a streamer by Twitch ID", () => {
    const r = result([], ["OK", "ERROR"]);
    assert.equal(streamerResult(r, "2")?.level, "ERROR");
    assert.equal(streamerResult(r, "9"), null);
    assert.equal(streamerResult(null, "1"), null);
});

test("problems are newest first by last seen; IDs as strings compare as numbers", () => {
    const sorted = sortProblems([problem("a", "900"), problem("b", "1700000000000"), problem("c", "1000")]);
    assert.deepEqual(sorted.map((p) => p.id), ["b", "c", "a"]);
});

test("labels for actions and targets", () => {
    assert.equal(actionLabel("send-live-message"), "Posting a live message");
    assert.equal(actionLabel("something-new"), "something-new");
    assert.equal(targetLabel({ kind: "channel", id: "5", name: "live" }), "#live");
    assert.equal(targetLabel({ kind: "channel", id: "5", name: null }), "channel 5");
    assert.equal(targetLabel({ kind: "role", id: "6", name: "Live" }), "@Live");
    assert.equal(targetLabel({ kind: "streamer", id: "7", name: null }), "Twitch account 7");
    assert.equal(targetLabel(null), "");
});

test("boldParts splits Discord-style *bold* without HTML", () => {
    assert.deepEqual(boldParts("Give MorganaBot *Send Messages* in #live."), [
        { text: "Give MorganaBot ", bold: false },
        { text: "Send Messages", bold: true },
        { text: " in #live.", bold: false },
    ]);
    assert.deepEqual(boldParts("<b>not html</b>"), [{ text: "<b>not html</b>", bold: false }]);
    assert.deepEqual(boldParts(""), []);
});

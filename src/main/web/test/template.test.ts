import { test } from "node:test";
import assert from "node:assert/strict";
import { checkMentions, exampleLength, fill, mentionText, parseMentions, segments, type MentionRules } from "../ts/template.js";

const DEFAULT_TEMPLATE = "${streamer_name} has gone live on Twitch, playing ${game_name}! Check 'em out!\n${twitch_link}";
const NONE: MentionRules = { all: false, everyone: false, users: false, roles: [] };

test("parseMentions finds everyone, here, roles and users once each, like Mentions.parse", () => {
    const mentions = parseMentions("@everyone hi <@&123> <@456> <@!456> @here <@&123> <@&99999999999999999999>");
    assert.deepEqual(mentions, { everyone: true, here: true, roles: ["123"], users: ["456"] });
});

test("parseMentions of null has nothing", () => {
    assert.deepEqual(parseMentions(null), { everyone: false, here: false, roles: [], users: [] });
});

test("IDs are compared as numbers (leading zeros), like Long.parseLong", () => {
    assert.deepEqual(parseMentions("<@&007>").roles, ["7"]);
});

test("segments split text, variables and mentions in order", () => {
    assert.deepEqual(segments("Hi ${streamer_name} @here!"), [
        { kind: "text", text: "Hi " },
        { kind: "variable", name: "streamer_name" },
        { kind: "text", text: " " },
        { kind: "here", raw: "@here" },
        { kind: "text", text: "!" },
    ]);
    assert.deepEqual(segments("${unknown} <@&99999999999999999999>"), [
        { kind: "text", text: "${unknown} <@&99999999999999999999>" },
    ]);
});

test("exampleLength matches LiveMessage.exampleLength", () => {
    // Values computed with the Java implementation.
    assert.equal(exampleLength(DEFAULT_TEMPLATE), 179);
    assert.equal(exampleLength("@everyone ${discord_name} <@!0012> ${stream_name} ${twitch_link} ${game_name} <@&99999999999999999999>"), 308);
});

test("fill escapes and defuses Twitch values and fills in one pass", () => {
    const text = fill("${streamer_name} ${game_name}", { streamerName: "x_y", gameName: "a_b@c*d~", streamName: "t", streamerLogin: "login", discordId: null });
    assert.equal(text, "x\\_y a\\_b@​c\\*d\\~");
    const nested = fill("${stream_name}", { streamerName: "", gameName: "", streamName: "${game_name}", streamerLogin: "", discordId: null });
    assert.equal(nested, "${game\\_name}"); // escaped, and not filled in again
    assert.equal(fill("${discord_name} ${twitch_link}", { streamerName: "", gameName: "", streamName: "", streamerLogin: "me", discordId: "5" }), "<@5> https://www.twitch.tv/me");
});

test("checkMentions refuses what isn't allowed and wasn't saved before", () => {
    const result = checkMentions("@everyone @here <@&1> <@&2> <@3> <@9>", null, { ...NONE, roles: ["1"] }, "9");
    assert.deepEqual(result.refused.map(mentionText), ["@everyone", "@here", "<@&2>", "<@3>"]);
    assert.deepEqual(result.kept, []);
});

test("checkMentions keeps mentions from the previously saved message", () => {
    const result = checkMentions("@everyone <@&2> <@3> @here", "old @everyone <@&2> <@3>", NONE, "9");
    assert.deepEqual(result.kept.map(mentionText), ["@everyone", "<@&2>", "<@3>"]);
    assert.deepEqual(result.refused.map(mentionText), ["@here"]);
});

test("checkMentions: everyone rule covers @here, admins may use anything", () => {
    assert.deepEqual(checkMentions("@everyone @here", null, { ...NONE, everyone: true }, "1").refused, []);
    assert.deepEqual(checkMentions("@everyone <@&5> <@6>", null, { all: true, everyone: true, users: true, roles: [] }, "1").refused, []);
});

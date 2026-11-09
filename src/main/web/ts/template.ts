// Live message templates: the same rules as the Java side (LiveMessage, Mentions and
// ServerSettings.disallowedMentions), so the editor can show what the server will accept before saving.
// No DOM here, so this runs under the Node tests too.

/** The variables a template can use, without `${…}` (the server sends its own list too). */
export const VARIABLES = ["streamer_name", "game_name", "stream_name", "twitch_link", "discord_name"] as const;

export const VARIABLE_HELP: Record<string, string> = {
    streamer_name: "Your Twitch display name",
    game_name: "The game or category you're streaming",
    stream_name: "Your stream title",
    twitch_link: "The link to your Twitch channel",
    discord_name: "Mentions you (your linked Discord account)",
};

/** Long example Twitch values, the same as LiveMessage uses for the length check. */
export const EXAMPLE_TITLE = "A very long example stream title, written to fill the whole 140 characters "
    + "that Twitch allows for a stream title, just like this one, okay!!";
export const EXAMPLE_GAME = "Example Game With A Rather Long Name: Deluxe Edition";
export const EXAMPLE_STREAMER = "an_example_streamer_name_";
const EXAMPLE_DISCORD_ID = "9000000000000000000";

const MAX_LONG = 9223372036854775807n;

export type Segment =
    | { kind: "text"; text: string }
    | { kind: "variable"; name: string }
    | { kind: "everyone"; raw: string }
    | { kind: "here"; raw: string }
    | { kind: "role"; id: string; raw: string }
    | { kind: "user"; id: string; raw: string };

// Group 1: variable. Group 2: everyone/here. Group 3: & or ! or nothing, group 4: the ID.
const TOKEN = /\$\{(game_name|stream_name|streamer_name|twitch_link|discord_name)\}|@(everyone|here)|<@(&|!)?(\d{1,20})>/g;

/** A mention ID as Java's Long.parseLong would read it, or null if it's too big to be one. */
function snowflake(digits: string): string | null {
    const value = BigInt(digits);
    return value > MAX_LONG ? null : value.toString();
}

/** Splits a template into text, variables and mentions, in order. */
export function segments(template: string): Segment[] {
    const result: Segment[] = [];
    let last = 0;
    const pushText = (text: string): void => {
        if (text.length === 0) return;
        const previous = result[result.length - 1];
        if (previous !== undefined && previous.kind === "text") previous.text += text;
        else result.push({ kind: "text", text });
    };
    for (const match of template.matchAll(TOKEN)) {
        const index = match.index ?? 0;
        pushText(template.substring(last, index));
        last = index + match[0].length;
        if (match[1] !== undefined) {
            result.push({ kind: "variable", name: match[1] });
        } else if (match[2] !== undefined) {
            result.push({ kind: match[2] === "everyone" ? "everyone" : "here", raw: match[0] });
        } else {
            const id = snowflake(match[4] ?? "");
            if (id === null) pushText(match[0]);
            else result.push(match[3] === "&" ? { kind: "role", id, raw: match[0] } : { kind: "user", id, raw: match[0] });
        }
    }
    pushText(template.substring(last));
    return result;
}

export interface Mentions {
    everyone: boolean;
    here: boolean;
    /** Role IDs, in order of first appearance. */
    roles: string[];
    /** User IDs, in order of first appearance. */
    users: string[];
}

/** The mentions written in a template (like Java's Mentions.parse). Null has none. */
export function parseMentions(template: string | null): Mentions {
    const mentions: Mentions = { everyone: false, here: false, roles: [], users: [] };
    if (template === null) return mentions;
    for (const segment of segments(template)) {
        if (segment.kind === "everyone") mentions.everyone = true;
        else if (segment.kind === "here") mentions.here = true;
        else if (segment.kind === "role" && !mentions.roles.includes(segment.id)) mentions.roles.push(segment.id);
        else if (segment.kind === "user" && !mentions.users.includes(segment.id)) mentions.users.push(segment.id);
    }
    return mentions;
}

/** What a member may mention (the API's `allowedMentions`, with role IDs only). */
export interface MentionRules {
    all: boolean;
    everyone: boolean;
    users: boolean;
    roles: string[];
}

export type MentionKey = { kind: "everyone" } | { kind: "here" } | { kind: "role"; id: string } | { kind: "user"; id: string };

export interface MentionCheck {
    /** Mentions that would be refused on save. */
    refused: MentionKey[];
    /** Mentions that aren't allowed by the member's rules but are kept from the previously saved message. */
    kept: MentionKey[];
}

/**
 * Which mentions in `wanted` the server would refuse (like ServerSettings.disallowedMentions): those neither allowed
 * nor already in `previous`. Mentioning yourself is always fine.
 */
export function checkMentions(wanted: string, previous: string | null, rules: MentionRules, self: string): MentionCheck {
    const want = parseMentions(wanted);
    const before = parseMentions(previous);
    const refused: MentionKey[] = [];
    const kept: MentionKey[] = [];
    const everyoneOk = rules.all || rules.everyone;
    const sort = (key: MentionKey, allowed: boolean, wasThere: boolean): void => {
        if (allowed) return;
        if (wasThere) kept.push(key);
        else refused.push(key);
    };
    if (want.everyone) sort({ kind: "everyone" }, everyoneOk, before.everyone);
    if (want.here) sort({ kind: "here" }, everyoneOk, before.here);
    for (const id of want.roles) sort({ kind: "role", id }, rules.all || rules.roles.includes(id), before.roles.includes(id));
    for (const id of want.users) sort({ kind: "user", id }, id === self || rules.all || rules.users, before.users.includes(id));
    return { refused, kept };
}

/** A mention as it's written in a message. */
export function mentionText(key: MentionKey): string {
    switch (key.kind) {
        case "everyone":
            return "@everyone";
        case "here":
            return "@here";
        case "role":
            return "<@&" + key.id + ">";
        case "user":
            return "<@" + key.id + ">";
    }
}

/** Values for filling in a template. */
export interface FillValues {
    streamerName: string;
    gameName: string;
    streamName: string;
    streamerLogin: string;
    /** The linked Discord user's ID, or null. */
    discordId: string | null;
}

/** A value from Twitch, as LiveMessage.twitchValue makes it: Markdown escaped, and `@` defused. */
export function twitchValue(value: string): string {
    return value.replaceAll("_", "\\_").replaceAll("~", "\\~").replaceAll("*", "\\*").replaceAll("@", "@​");
}

/** The text a variable is replaced with, as LiveMessage.fill does it. */
export function variableValue(name: string, values: FillValues): string {
    switch (name) {
        case "game_name":
            return twitchValue(values.gameName);
        case "stream_name":
            return twitchValue(values.streamName);
        case "streamer_name":
            return twitchValue(values.streamerName);
        case "twitch_link":
            return "https://www.twitch.tv/" + values.streamerLogin;
        default:
            return values.discordId === null ? "?" : "<@" + values.discordId + ">";
    }
}

/** Fills in a template in one pass, like LiveMessage.fill. */
export function fill(template: string, values: FillValues): string {
    let result = "";
    for (const segment of segments(template)) {
        switch (segment.kind) {
            case "text":
                result += segment.text;
                break;
            case "variable":
                result += variableValue(segment.name, values);
                break;
            default:
                // Mentions stay exactly as written.
                result += segment.raw;
        }
    }
    return result;
}

/**
 * How long the posted message can get: the template filled with long example Twitch values, as the server checks it
 * against Discord's limit (LiveMessage.exampleLength).
 */
export function exampleLength(template: string): number {
    return fill(template, {
        streamerName: EXAMPLE_STREAMER,
        gameName: EXAMPLE_GAME,
        streamName: EXAMPLE_TITLE,
        streamerLogin: EXAMPLE_STREAMER,
        discordId: EXAMPLE_DISCORD_ID,
    }).length;
}

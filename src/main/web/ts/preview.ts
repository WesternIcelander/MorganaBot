// The live message preview: a template as it would look in Discord, with example Twitch values. Built from text nodes
// only.

import { h, type Child } from "./dom.js";
import { segments } from "./template.js";

export const PREVIEW_GAME = "Just Chatting";
export const PREVIEW_TITLE = "Chill stream, come say hi!";

export interface PreviewValues {
    /** Twitch display name. */
    display: string;
    /** Twitch login, for the link. */
    login: string;
    /** The linked Discord user's ID and the name to show for them, or null when nobody is linked. */
    discordId: string | null;
    discordName: string;
    /** Role names by ID, for role mentions. */
    roleNames: Map<string, string>;
    /** Member names by ID, for member mentions (optional). */
    memberNames?: Map<string, string>;
}

export function previewNodes(template: string, values: PreviewValues): Child[] {
    const nodes: Child[] = [];
    for (const segment of segments(template)) {
        switch (segment.kind) {
            case "text":
                nodes.push(segment.text);
                break;
            case "variable":
                switch (segment.name) {
                    case "streamer_name":
                        nodes.push(values.display);
                        break;
                    case "game_name":
                        nodes.push(PREVIEW_GAME);
                        break;
                    case "stream_name":
                        nodes.push(PREVIEW_TITLE);
                        break;
                    case "twitch_link":
                        nodes.push(h("span", { class: "link" }, "https://www.twitch.tv/" + values.login));
                        break;
                    default:
                        nodes.push(values.discordId === null ? "?" : h("span", { class: "mention" }, "@" + values.discordName));
                }
                break;
            case "everyone":
                nodes.push(h("span", { class: "mention" }, "@everyone"));
                break;
            case "here":
                nodes.push(h("span", { class: "mention" }, "@here"));
                break;
            case "role":
                nodes.push(h("span", { class: "mention" }, "@" + (values.roleNames.get(segment.id) ?? "unknown-role")));
                break;
            case "user": {
                const name = segment.id === values.discordId ? values.discordName : values.memberNames?.get(segment.id) ?? "user " + segment.id;
                nodes.push(h("span", { class: "mention" }, "@" + name));
                break;
            }
        }
    }
    return nodes;
}

/** The preview box. */
export function previewBox(content: HTMLElement): HTMLElement {
    return h("div", { class: "preview" }, h("div", { class: "preview-author" }, "MorganaBot"), content);
}

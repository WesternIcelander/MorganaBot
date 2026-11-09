// The live message editor for admins (the server-wide message and streamers' own messages): variables, mentions,
// length against Discord's limit, and a live preview. Admins can use any mention, so nothing is greyed out here.

import { GuildRefs, uid } from "./admin-ui.js";
import { h, replace } from "./dom.js";
import { previewBox, previewNodes, type PreviewValues } from "./preview.js";
import { exampleLength, VARIABLE_HELP } from "./template.js";

const ADMIN_VARIABLE_HELP: Record<string, string> = {
    ...VARIABLE_HELP,
    streamer_name: "The streamer's Twitch display name",
    game_name: "The game or category they're streaming",
    stream_name: "The stream title",
    twitch_link: "The link to their Twitch channel",
    discord_name: "Mentions the streamer's linked Discord account",
};

export interface TemplateEditor {
    element: HTMLElement;
    textarea: HTMLTextAreaElement;
    /** Re-draws the length and preview (after setting the text from code). */
    update(): void;
    /** Whether the text fits the length limit. */
    fits(): boolean;
}

export function templateEditor(refs: GuildRefs, options: {
    label: string;
    value: string;
    variables: string[];
    maxLength: number;
    disabled: boolean;
    preview: Omit<PreviewValues, "roleNames">;
}): TemplateEditor {
    const id = uid("template");
    const roleNames = refs.roleNames();
    const textarea = h("textarea", { id, rows: 5, disabled: options.disabled });
    textarea.value = options.value;
    const length = h("p", { class: "hint", "aria-live": "polite" });
    const preview = h("div", { class: "preview-text" });
    const update = (): void => {
        const count = exampleLength(textarea.value);
        length.textContent = count + " / " + options.maxLength + " characters with a long stream title";
        length.classList.toggle("error-text", count > options.maxLength);
        replace(preview, previewNodes(textarea.value, { ...options.preview, roleNames }));
    };
    textarea.addEventListener("input", update);
    const insert = (text: string): void => {
        if (text === "") return;
        textarea.setRangeText(text, textarea.selectionStart, textarea.selectionEnd, "end");
        textarea.focus();
        update();
    };
    const chips = h("div", { class: "chip-row" }, options.variables.map((name) => h("button", {
        type: "button", class: "chip", disabled: options.disabled, title: ADMIN_VARIABLE_HELP[name] ?? name,
        onclick: () => insert("${" + name + "}"),
    }, "${" + name + "}")));
    const mentionSelect = h("select", { "aria-label": "Insert a mention", disabled: options.disabled },
        h("option", { value: "" }, "Insert a mention…"),
        h("option", { value: "@everyone" }, "@everyone"),
        h("option", { value: "@here" }, "@here"),
        refs.roles.filter((role) => !role.everyone).map((role) =>
            h("option", { value: "<@&" + role.id + ">" }, "@" + role.name + (role.mentionable ? "" : " (not mentionable by everyone)"))));
    mentionSelect.addEventListener("change", () => {
        insert(mentionSelect.value);
        mentionSelect.value = "";
    });
    const element = h("div", { class: "stack template-editor" },
        h("label", { for: id }, options.label),
        textarea,
        length,
        h("div", { class: "row" }, h("span", { class: "muted" }, "Insert:"), chips, mentionSelect),
        h("details", { class: "variables-help" }, h("summary", null, "What the variables mean"),
            h("ul", null, options.variables.map((name) => h("li", null, h("code", null, "${" + name + "}"), " – " + (ADMIN_VARIABLE_HELP[name] ?? ""))))),
        h("p", { class: "hint" }, "Mentions ping as written, if MorganaBot has permission to mention them in the channel. To mention a member, write <@their user ID>."),
        previewBox(preview),
        h("p", { class: "hint" }, "Preview with an example stream title and game. Discord formatting isn't shown."));
    update();
    return { element, textarea, update, fits: () => exampleLength(textarea.value) <= options.maxLength };
}

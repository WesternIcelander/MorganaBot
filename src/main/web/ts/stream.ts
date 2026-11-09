// The "My stream" page: a member's own streamer entry. Status, setting or removing their Twitch channel
// (the same self-service rules as /settwitchchannel and /removetwitchchannel), and their own live message.

import type { ChannelRef, OwnStreamer, RoleInfo, RolesResponse, Streamer } from "./api.js";
import { busy, FormErrors, formatTime, h, replace, type Child } from "./dom.js";
import { api, ApiFailure } from "./http.js";
import { guildPath, type Page, type PageContext } from "./page.js";
import { previewBox, previewNodes } from "./preview.js";
import {
    checkMentions,
    exampleLength,
    VARIABLE_HELP,
    type MentionKey,
    type MentionRules,
} from "./template.js";

export const streamPage: Page = {
    id: "stream",
    label: "My stream",
    available: () => true,
    render: renderStreamPage,
};

async function renderStreamPage(root: HTMLElement, context: PageContext): Promise<void> {
    const data = await api<OwnStreamer>("GET", guildPath(context.guild) + "/me/streamer");
    draw(root, context, data);
}

type Redraw = (next: OwnStreamer, saved?: string) => void;

function draw(root: HTMLElement, context: PageContext, data: OwnStreamer, saved?: string): void {
    const redraw: Redraw = (next, message) => draw(root, context, next, message);
    replace(root,
        h("h1", null, "My stream"),
        data.twitchDisabledMessage !== null ? h("p", { class: "notice warn", role: "status" }, data.twitchDisabledMessage) : null,
        statusSection(data),
        twitchSection(context, data, redraw),
        data.streamer !== null ? messageSection(context, data, data.streamer, redraw, saved ?? "") : null,
    );
}

// --- status ---

function channelText(channel: ChannelRef | null): string {
    if (channel === null) return "";
    return channel.name !== null ? "#" + channel.name : "deleted channel (" + channel.id + ")";
}

/** Where posts go, with a warning when the bot can't post there. */
function channelLine(channel: ChannelRef | null, source: string): Child[] {
    if (channel === null) {
        return [h("span", { class: "warn-text" }, "No live channel is set. Ask a server admin to set one.")];
    }
    const parts: Child[] = [h("span", { class: "channel" }, channelText(channel)), " ", h("span", { class: "muted" }, "(" + source + ")")];
    if (!channel.exists) {
        parts.push(h("span", { class: "badge error" }, "Channel deleted"), " Ask a server admin to pick another channel.");
    } else if (!channel.botCanPost) {
        parts.push(" ", h("span", { class: "badge error" }, "Can't post here"),
            " MorganaBot isn't allowed to post in this channel. Ask a server admin to fix its permissions.");
    }
    return parts;
}

function sourceText(streamer: Streamer): string {
    switch (streamer.channelSource) {
        case "role":
            return streamer.channelOverride === null ? "server default, from your role" : "from your role";
        case "override":
            return "set by an admin";
        default:
            return "server default";
    }
}

function twitchName(streamer: Streamer): Child {
    const label = streamer.twitchDisplayName ?? streamer.twitchLogin;
    if (label === null) return "Twitch account " + streamer.twitchId;
    const text = streamer.twitchLogin !== null && streamer.twitchDisplayName !== null
        && streamer.twitchLogin.toLowerCase() !== streamer.twitchDisplayName.toLowerCase()
        ? label + " (" + streamer.twitchLogin + ")" : label;
    if (streamer.twitchUrl === null) return text;
    return h("a", { href: streamer.twitchUrl, target: "_blank", rel: "noopener noreferrer" }, text);
}

function statusSection(data: OwnStreamer): HTMLElement {
    const streamer = data.streamer;
    if (streamer === null) {
        return h("section", { class: "card" },
            h("h2", null, "Status"),
            h("p", null, "You don't have a Twitch channel set up in this server."));
    }
    const rows: [string, Child][] = [];
    rows.push(["Twitch channel", twitchName(streamer)]);
    const state: Child[] = [];
    if (streamer.paused) {
        state.push(h("span", { class: "badge warn" }, "Paused"),
            " No self-service role since " + formatTime(streamer.pausedSince)
            + ". MorganaBot won't post when you go live until you get one of the server's self-service roles back.");
    } else if (streamer.live) {
        state.push(h("span", { class: "badge live" }, "Live"), " since " + formatTime(streamer.liveSince));
    } else {
        state.push(h("span", { class: "badge" }, "Offline"));
    }
    rows.push(["Status", state]);
    rows.push(["Last stream", streamer.lastStreamEnded !== null ? "Ended " + formatTime(streamer.lastStreamEnded) : "None recorded"]);
    rows.push(["Live posts go to", channelLine(streamer.channel, sourceText(streamer))]);
    rows.push(["Added", streamer.selfService ? "By you (self-service)" : "By a server admin"]);
    return h("section", { class: "card" },
        h("h2", null, "Status"),
        h("dl", { class: "facts" }, rows.map(([term, value]) => [h("dt", null, term), h("dd", null, value)])));
}

// --- Twitch channel ---

function twitchSection(context: PageContext, data: OwnStreamer, redraw: Redraw): HTMLElement {
    const section = h("section", { class: "card" }, h("h2", null, "Twitch channel"));
    const disabled = data.twitchDisabledMessage !== null;
    const streamer = data.streamer;
    const self = data.selfService;

    if (self.eligible) {
        const errors = new FormErrors();
        const input = h("input", {
            id: "twitch-name", type: "text", name: "twitchName", autocomplete: "off", maxlength: 50,
            value: streamer?.twitchLogin ?? "", placeholder: "your_twitch_name", disabled,
        });
        const fieldError = errors.field("twitchName", input);
        const submit = h("button", { type: "submit", class: "primary", disabled }, streamer === null ? "Add my channel" : "Change");
        const role = self.role !== null ? h("span", { class: "role" }, "@" + (self.role.name ?? self.role.id)) : "your role";
        const where: Child[] = ["Your live posts will go to ",
            self.channel !== null ? h("span", { class: "channel" }, channelText(self.channel)) : "the server's live channel (none is set yet)",
            self.serverDefault ? [" (server default, from ", role, ")."] : [" (from ", role, ")."]];
        const form = h("form", {
            class: "stack", onsubmit: (event) => {
                event.preventDefault();
                errors.clear();
                const name = input.value.trim();
                if (name.length === 0) {
                    errors.show(localError("twitchName", "Enter your Twitch name."));
                    return;
                }
                void busy(submit, async () => {
                    try {
                        redraw(await api<OwnStreamer>("PUT", guildPath(context.guild) + "/me/streamer/twitch", { twitchName: name }));
                    } catch (error) {
                        errors.show(error);
                    }
                });
            },
        },
            h("label", { for: "twitch-name" }, "Twitch name"),
            h("div", { class: "row" }, input, submit),
            fieldError,
            h("p", { class: "hint" }, where),
            streamer !== null ? h("p", { class: "hint" }, "Changing it replaces your current Twitch channel. Your live message is kept.") : null,
            errors.general);
        section.append(form);
    } else {
        section.append(h("p", { class: "hint" }, streamer === null
            ? "To add your Twitch channel yourself, you need one of this server's self-service roles. Ask a server admin."
            : "To change your Twitch channel yourself, you need one of this server's self-service roles. Ask a server admin."));
    }

    if (streamer !== null) {
        const errors = new FormErrors();
        const remove = h("button", { type: "button", class: "danger", disabled }, "Remove my Twitch channel");
        remove.addEventListener("click", () => {
            if (!window.confirm("Remove your Twitch channel from this server? MorganaBot will stop posting when you go live.")) return;
            void busy(remove, async () => {
                try {
                    await api<unknown>("DELETE", guildPath(context.guild) + "/me/streamer");
                    await context.accessChanged();
                } catch (error) {
                    errors.show(error);
                }
            });
        });
        section.append(h("div", { class: "remove" }, remove, errors.general));
    }
    return section;
}

/** A check made before sending, shown like the API's error for that field. */
function localError(field: string, message: string): ApiFailure {
    return new ApiFailure(400, "invalid", message, field);
}

// --- live message ---

function messageSection(context: PageContext, data: OwnStreamer, streamer: Streamer, redraw: Redraw, savedMessage: string): HTMLElement {
    const section = h("section", { class: "card" }, h("h2", null, "Live message"));
    const saved = streamer.template ?? data.serverTemplate;

    if (!data.canCustomizeMessage) {
        section.append(
            h("p", { class: "hint" }, "None of your roles let you write your own live message. Ask a server admin if you'd like to."),
            h("p", null, streamer.template === null ? "Your posts use the server-wide message:" : "Your posts use this message:"),
            h("pre", { class: "template" }, saved));
        return section;
    }

    const disabled = data.twitchDisabledMessage !== null;
    const roleNames = new Map<string, string>();
    for (const role of data.allowedMentions.roles) {
        if (role.name !== null) roleNames.set(role.id, role.name);
    }
    const rules: MentionRules = {
        all: data.allowedMentions.all,
        everyone: data.allowedMentions.everyone,
        users: data.allowedMentions.users,
        roles: data.allowedMentions.roles.map((role) => role.id),
    };

    const errors = new FormErrors();
    const textarea = h("textarea", { id: "template", rows: 6, disabled });
    textarea.value = saved;
    const fieldError = errors.field("template", textarea);
    const length = h("p", { class: "hint", "aria-live": "polite" });
    const mentionNotes = h("div", { "aria-live": "polite" });
    const preview = h("div", { class: "preview-text" });
    const status = h("p", { class: "saved", role: "status" }, savedMessage);

    const myName = streamer.discordUser?.displayName ?? context.me.name ?? "you";
    const update = (): void => {
        const text = textarea.value;
        const count = exampleLength(text);
        length.textContent = count + " / " + data.maxLength + " characters with a long stream title";
        length.classList.toggle("error-text", count > data.maxLength);
        replace(mentionNotes, mentionFeedback(checkMentions(text, saved, rules, context.me.userId), roleNames));
        replace(preview, previewFor(text, streamer, myName, roleNames));
    };
    textarea.addEventListener("input", () => {
        status.textContent = "";
        update();
    });

    const insert = (text: string): void => {
        textarea.setRangeText(text, textarea.selectionStart, textarea.selectionEnd, "end");
        textarea.focus();
        update();
    };

    const variables = h("ul", { class: "chips" }, data.variables.map((name) =>
        h("li", null, h("button", {
            type: "button", class: "chip", disabled, title: VARIABLE_HELP[name] ?? name,
            onclick: () => insert("${" + name + "}"),
        }, "${" + name + "}"), " ", h("span", { class: "muted" }, VARIABLE_HELP[name] ?? ""))));

    const mentionList = h("ul", { class: "chips" });
    const drawMentions = (extraRoles: RoleInfo[]): void => {
        replace(mentionList, mentionChoices(data, disabled, extraRoles, insert));
    };
    drawMentions([]);
    if (data.allowedMentions.all) {
        // Admins can mention any role; the list comes from the admin roles endpoint.
        void api<RolesResponse>("GET", guildPath(context.guild) + "/roles").then((response) => {
            const roles = response.roles.filter((role) => !role.everyone);
            for (const role of roles) roleNames.set(role.id, role.name);
            drawMentions(roles);
            update();
        }).catch(() => undefined);
    }

    const save = h("button", { type: "submit", class: "primary", disabled }, "Save");
    const reset = h("button", { type: "button", disabled }, "Undo changes");
    reset.addEventListener("click", () => {
        textarea.value = saved;
        errors.clear();
        update();
    });
    const useServer = streamer.template !== null
        ? h("button", { type: "button", disabled }, "Use the server-wide message instead")
        : null;

    const put = (template: string | null, button: HTMLButtonElement): void => {
        errors.clear();
        void busy(button, async () => {
            try {
                const next = await api<OwnStreamer>("PUT", guildPath(context.guild) + "/me/streamer/template", { template });
                redraw(next, template === null ? "Now using the server-wide message." : "Saved.");
            } catch (error) {
                errors.show(error);
            }
        });
    };
    useServer?.addEventListener("click", () => {
        if (!window.confirm("Delete your own message and use the server-wide one?")) return;
        put(null, useServer);
    });

    const form = h("form", {
        class: "stack", onsubmit: (event) => {
            event.preventDefault();
            if (textarea.value.trim().length === 0) {
                errors.show(localError("template", "The live message can't be empty."));
                return;
            }
            put(textarea.value, save);
        },
    },
        h("p", { class: "hint" }, streamer.template === null
            ? "You're using the server-wide message. Edit it below and save to make your own."
            : "This is your own message. It's posted instead of the server-wide one."),
        h("label", { for: "template" }, "Message"),
        textarea,
        fieldError,
        length,
        mentionNotes,
        h("div", { class: "row" }, save, reset, useServer),
        errors.general,
        status,
        h("h3", null, "Variables"),
        h("p", { class: "hint" }, "Click one to insert it. They're filled in when you go live."),
        variables,
        h("h3", null, "Mentions"),
        mentionList,
        h("h3", null, "Preview"),
        previewBox(preview),
        h("p", { class: "hint" }, "Example stream title and game. Discord formatting isn't shown."));
    section.append(form);
    update();
    return section;
}

/** The mention buttons: allowed ones insert the mention, the rest are greyed out with the reason. */
function mentionChoices(data: OwnStreamer, disabled: boolean, adminRoles: RoleInfo[], insert: (text: string) => void): Child[] {
    const allowed = data.allowedMentions;
    const items: Child[] = [];
    const choice = (label: string, text: string, ok: boolean, reason: string): HTMLElement =>
        h("li", { class: ok ? undefined : "unavailable" },
            h("button", {
                type: "button", class: "chip mention-chip", disabled: disabled || !ok,
                title: ok ? "Insert " + label : reason, "aria-disabled": ok ? undefined : "true",
                onclick: () => insert(text),
            }, label),
            ok ? null : [" ", h("span", { class: "muted" }, reason)]);

    const everyoneReason = "None of your roles allow @everyone or @here.";
    items.push(choice("@everyone", "@everyone", allowed.everyone, everyoneReason));
    items.push(choice("@here", "@here", allowed.everyone, everyoneReason));

    if (allowed.all) {
        for (const role of adminRoles) items.push(choice("@" + role.name, "<@&" + role.id + ">", true, ""));
    } else {
        for (const role of allowed.roles) {
            items.push(role.exists
                ? choice("@" + (role.name ?? role.id), "<@&" + role.id + ">", true, "")
                : choice("@deleted-role", "", false, "This role was deleted."));
        }
        if (allowed.roles.length === 0) {
            items.push(choice("@role", "", false, "None of your roles let you mention other roles."));
        }
    }
    items.push(allowed.users
        ? h("li", null, h("span", { class: "muted" }, "To mention a member, write <@their user ID>."))
        : choice("@member", "", false, "Only server admins can mention other members. ${discord_name} mentions you."));
    return items;
}

function mentionName(key: MentionKey, roleNames: Map<string, string>): string {
    switch (key.kind) {
        case "everyone":
            return "@everyone";
        case "here":
            return "@here";
        case "role":
            return "@" + (roleNames.get(key.id) ?? "role " + key.id);
        case "user":
            return "member mention <@" + key.id + ">";
    }
}

function refusalReason(key: MentionKey): string {
    switch (key.kind) {
        case "everyone":
        case "here":
            return "none of your roles allow @everyone or @here";
        case "role":
            return "none of your roles let you mention this role";
        case "user":
            return "only server admins can mention other members";
    }
}

function mentionFeedback(check: ReturnType<typeof checkMentions>, roleNames: Map<string, string>): Child[] {
    const result: Child[] = [];
    if (check.refused.length > 0) {
        result.push(h("div", { class: "notice error" },
            h("p", null, "These mentions aren't allowed, so saving will be refused. Remove them first:"),
            h("ul", null, check.refused.map((key) => h("li", null, mentionName(key, roleNames) + ": " + refusalReason(key))))));
    }
    if (check.kept.length > 0) {
        result.push(h("div", { class: "notice info" },
            h("p", null, "Kept from your saved message. You can keep these, but if you remove them you can't add them back:"),
            h("ul", null, check.kept.map((key) => h("li", null, mentionName(key, roleNames))))));
    }
    return result;
}

/** The message as it would look, with example Twitch values. */
function previewFor(template: string, streamer: Streamer, myName: string, roleNames: Map<string, string>): Child[] {
    const login = streamer.twitchLogin ?? "your_channel";
    return previewNodes(template, {
        display: streamer.twitchDisplayName ?? login,
        login,
        discordId: streamer.discordUserId,
        discordName: myName,
        roleNames,
    });
}

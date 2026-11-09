// The "Streamers" page for server admins: the Problems panel, adding a streamer, and the streamer table with edit,
// delete, "Go back to role-based", per-row Diagnose, and a status badge from the latest Diagnose result.

import type { ChannelRef, DiagnoseResult, Settings, Streamer, StreamersResponse } from "./api.js";
import {
    badge,
    channelLabel,
    channelPicker,
    channelProblemBadge,
    checkList,
    GuildRefs,
    levelBadge,
    memberLabel,
    memberPicker,
    uid,
} from "./admin-ui.js";
import { streamerResult } from "./diagnose.js";
import { busy, errorMessage, FormErrors, formatTime, h, replace, type Child } from "./dom.js";
import { api } from "./http.js";
import { guildPath, type Page, type PageContext } from "./page.js";
import { ProblemsPanel } from "./problems.js";
import { localError, type AdminContext } from "./settings.js";
import { templateEditor } from "./template-editor.js";

export const streamersPage: Page = {
    id: "streamers",
    label: "Streamers",
    available: (guild) => guild.access === "admin",
    render: renderStreamersPage,
};

interface StreamersContext extends AdminContext {
    settings: Settings;
    /** Reloads the list (after adding, editing or deleting) and runs Diagnose again. */
    reload(message?: string): Promise<void>;
}

async function renderStreamersPage(root: HTMLElement, context: PageContext): Promise<void> {
    const path = guildPath(context.guild);
    const panel = new ProblemsPanel(context.guild);
    const [list, settings, refs] = await Promise.all([
        api<StreamersResponse>("GET", path + "/streamers"),
        api<Settings>("GET", path + "/settings"),
        GuildRefs.load(context.guild),
    ]);
    const tableSlot = h("section", { class: "card", "aria-labelledby": "streamers-title" });
    const admin: StreamersContext = {
        guild: context.guild, refs, panel, settings,
        twitchOff: list.twitchDisabledMessage !== null,
        reload: async (message?: string) => {
            const next = await api<StreamersResponse>("GET", path + "/streamers");
            drawTable(admin, tableSlot, next, message ?? "");
            void panel.rediagnose();
        },
    };
    replace(root,
        h("h1", null, "Streamers"),
        list.twitchDisabledMessage !== null ? h("p", { class: "notice warn", role: "status" }, list.twitchDisabledMessage,
            " Streamers can't be added or changed until it's turned on.") : null,
        panel.element,
        addSection(admin),
        tableSlot);
    drawTable(admin, tableSlot, list, "");
    await panel.load();
}

// --- adding ---

function addSection(admin: StreamersContext, open = false): HTMLElement {
    const disabled = admin.twitchOff;
    const errors = new FormErrors();
    const name = h("input", { id: "add-twitch", type: "text", autocomplete: "off", maxlength: 50, placeholder: "twitch_name", disabled });
    const member = memberPicker(admin.guild, { id: "add-member", initial: null, initialId: null, disabled });
    const channel = channelPicker(admin.refs, { id: "add-channel", value: null, empty: "Server default", disabled });
    const add = h("button", { type: "submit", class: "primary", disabled }, "Add streamer");
    const form = h("form", {
        class: "stack", onsubmit: (event) => {
            event.preventDefault();
            errors.clear();
            const twitchName = name.value.trim();
            if (twitchName.length === 0) {
                errors.show(localError("twitchName", "Enter the streamer's Twitch name."));
                return;
            }
            void busy(add, async () => {
                try {
                    await api<Streamer>("POST", guildPath(admin.guild) + "/streamers", {
                        twitchName, discordUserId: member.value(), channelId: channel.select.value || null,
                    });
                    section.replaceWith(addSection(admin, true));
                    await admin.reload("Added " + twitchName + ".");
                } catch (error) {
                    errors.show(error);
                }
            });
        },
    },
        h("label", { for: "add-twitch" }, "Twitch name"),
        name,
        errors.field("twitchName", name),
        h("label", { for: "add-member" }, "Discord user (optional)"),
        member.element,
        errors.field("discordUserId", member.input),
        h("p", { class: "hint" }, "Linking their Discord account lets them have the live role, ${discord_name} mention them, and open their own My stream page."),
        h("label", { for: "add-channel" }, "Channel"),
        h("div", { class: "row" }, channel.select, channel.note),
        errors.field("channelId", channel.select),
        h("div", { class: "row" }, add),
        errors.general);
    const section = h("section", { class: "card" },
        h("details", { class: "add-streamer", open }, h("summary", null, h("h2", { class: "inline" }, "Add a streamer")), form));
    return section;
}

// --- the table ---

function twitchName(streamer: Streamer): Child {
    const label = streamer.twitchDisplayName ?? streamer.twitchLogin;
    if (label === null) return "Twitch account " + streamer.twitchId;
    if (streamer.twitchUrl === null) return label;
    return h("a", { href: streamer.twitchUrl, target: "_blank", rel: "noopener noreferrer" }, label);
}

function displayName(streamer: Streamer): string {
    return streamer.twitchDisplayName ?? streamer.twitchLogin ?? "Twitch account " + streamer.twitchId;
}

function sourceText(streamer: Streamer): string {
    switch (streamer.channelSource) {
        case "role":
            return streamer.channelOverride === null ? "server default, from their role" : "from their role";
        case "override":
            return streamer.selfService ? "pinned by an admin" : "own channel";
        default:
            return streamer.selfService && streamer.channelOverridePinned ? "server default, pinned by an admin" : "server default";
    }
}

function channelCell(streamer: Streamer): Child[] {
    if (streamer.channel === null) return [h("span", { class: "muted" }, "No channel"), " ", badge("error", "Nowhere to post")];
    return [h("span", { class: "channel" }, channelLabel(streamer.channel)), " ", channelProblemBadge(streamer.channel),
        h("div", { class: "muted small" }, sourceText(streamer))];
}

function drawTable(admin: StreamersContext, section: HTMLElement, data: StreamersResponse, message: string): void {
    const body = h("tbody");
    const heading = h("h2", { id: "streamers-title" }, "Streamers (" + data.streamers.length + ")");
    if (data.streamers.length === 0) {
        replace(section, heading, h("p", { class: "muted" }, "No streamers yet."));
        return;
    }
    for (const streamer of data.streamers) body.append(...streamerRows(admin, streamer));
    replace(section,
        heading,
        data.twitchLookupFailed ? h("p", { class: "notice warn" }, "Couldn't look up Twitch names right now, so streamers are shown by Twitch ID.") : null,
        message !== "" ? h("p", { class: "saved", role: "status" }, message) : null,
        h("div", { class: "table-wrap" }, h("table", { class: "streamers" },
            h("thead", null, h("tr", null,
                h("th", { scope: "col" }, "Twitch"),
                h("th", { scope: "col" }, "Discord user"),
                h("th", { scope: "col" }, "Posts to"),
                h("th", { scope: "col" }, "Message"),
                h("th", { scope: "col" }, "Last stream"),
                h("th", { scope: "col" }, "Check"),
                h("th", { scope: "col" }, h("span", { class: "visually-hidden" }, "Actions")))),
            body)));
}

/** A streamer's row, plus a hidden row below it for editing or Diagnose results. */
function streamerRows(admin: StreamersContext, streamer: Streamer): HTMLElement[] {
    const disabled = admin.twitchOff;
    const status = h("td", { class: "status-cell" });
    const detail = h("td", { colspan: 7 });
    const detailRow = h("tr", { class: "detail-row", hidden: true }, detail);
    const drawStatus = (result: DiagnoseResult | null): void => {
        const mine = streamerResult(result, streamer.twitchId);
        replace(status, mine === null ? h("span", { class: "muted" }, "–") : levelBadge(mine.level));
    };
    admin.panel.subscribe(drawStatus, status);

    let open: "edit" | "diagnose" | null = null;
    const close = (): void => {
        open = null;
        detailRow.hidden = true;
        replace(detail);
    };

    const edit = h("button", { type: "button", disabled }, "Edit");
    edit.addEventListener("click", () => {
        if (open === "edit") {
            close();
            return;
        }
        open = "edit";
        replace(detail, editForm(admin, streamer, close));
        detailRow.hidden = false;
    });
    const diagnose = h("button", { type: "button" }, "Diagnose");
    diagnose.addEventListener("click", () => {
        if (open === "diagnose") {
            close();
            return;
        }
        open = "diagnose";
        detailRow.hidden = false;
        replace(detail, h("p", { class: "loading" }, "Running Diagnose…"));
        void busy(diagnose, async () => {
            try {
                const result = await admin.panel.diagnoseStreamer(streamer.twitchId);
                if (open !== "diagnose") return;
                replace(detail, h("div", { class: "stack" },
                    h("h3", null, "Diagnose: " + displayName(streamer)),
                    result === null ? h("p", { class: "muted" }, "Nothing to check.") : [
                        h("p", null, levelBadge(result.level), " ", result.channel !== "" ? "Posts to " + result.channel : ""),
                        checkList(result.checks)],
                    h("button", { type: "button", class: "link-button", onclick: close }, "Close")));
            } catch (error) {
                if (open === "diagnose") replace(detail, h("p", { class: "notice error" }, errorMessage(error)));
            }
        });
    });
    const remove = h("button", { type: "button", class: "danger", disabled }, "Delete");
    remove.addEventListener("click", () => {
        if (!window.confirm("Delete " + displayName(streamer) + "? MorganaBot will stop posting when they go live.")) return;
        void busy(remove, async () => {
            try {
                await api<unknown>("DELETE", guildPath(admin.guild) + "/streamers/" + encodeURIComponent(streamer.twitchId));
                await admin.reload("Deleted " + displayName(streamer) + ".");
            } catch (error) {
                window.alert("Couldn't delete: " + errorMessage(error));
            }
        });
    });

    const badges: Child[] = [];
    if (streamer.live) badges.push(badge("live", "Live", "Since " + formatTime(streamer.liveSince)));
    if (streamer.selfService) badges.push(badge("info", "Self-added"));
    if (streamer.paused) badges.push(badge("warn", "Paused since " + formatTime(streamer.pausedSince), "No self-service role since then, so MorganaBot doesn't post for them."));
    if (streamer.selfService && streamer.channelOverridePinned) badges.push(badge("", "Pinned", "An admin set their channel by hand, so it doesn't follow their roles."));

    const actions: Child[] = [edit, diagnose, remove];
    if (streamer.selfService && streamer.channelOverridePinned) {
        const unpin = h("button", { type: "button", disabled, title: "Their channel follows their highest self-service role again." }, "Go back to role-based");
        unpin.addEventListener("click", () => {
            void busy(unpin, async () => {
                try {
                    await api<Streamer>("PATCH", guildPath(admin.guild) + "/streamers/" + encodeURIComponent(streamer.twitchId), { channelOverridePinned: false });
                    await admin.reload(displayName(streamer) + "'s channel follows their roles again.");
                } catch (error) {
                    window.alert(errorMessage(error));
                }
            });
        });
        actions.unshift(unpin);
    }

    const user = streamer.discordUser !== null ? memberLabel(streamer.discordUser)
        : streamer.discordUserId !== null ? "User " + streamer.discordUserId + " (not in the server?)" : null;
    const row = h("tr", null,
        h("th", { scope: "row" }, h("div", null, twitchName(streamer)), badges.length > 0 ? h("div", { class: "badges" }, badges) : null),
        h("td", null, user ?? h("span", { class: "muted" }, "Not linked")),
        h("td", null, channelCell(streamer)),
        h("td", null, streamer.template !== null ? "Own message" : h("span", { class: "muted" }, "Server-wide")),
        h("td", null, streamer.live ? "Live now" : streamer.lastStreamEnded !== null ? formatTime(streamer.lastStreamEnded) : h("span", { class: "muted" }, "None recorded")),
        status,
        h("td", { class: "actions" }, h("div", { class: "row" }, actions)));
    return [row, detailRow];
}

// --- editing ---

const ROLE_BASED = "role-based";

function editForm(admin: StreamersContext, streamer: Streamer, close: () => void): HTMLElement {
    const errors = new FormErrors();
    const member = memberPicker(admin.guild, { id: uid("edit-member"), initial: streamer.discordUser, initialId: streamer.discordUserId });
    const followsRoles = streamer.selfService && !streamer.channelOverridePinned;
    const current = followsRoles ? ROLE_BASED : streamer.channelOverride?.id ?? null;
    const channel = channelPicker(admin.refs, {
        id: uid("edit-channel"), value: current, empty: "Server default", missing: streamer.channelOverride as ChannelRef | null,
        extra: streamer.selfService ? [[ROLE_BASED, "Follow their roles (self-service)"]] : [],
    });
    const settings = admin.settings;
    const ownMessage = h("input", { id: uid("own-message"), type: "checkbox" });
    ownMessage.checked = streamer.template !== null;
    const login = streamer.twitchLogin ?? "their_channel";
    const editor = templateEditor(admin.refs, {
        label: "Their message",
        value: streamer.template ?? settings.liveMessageTemplate ?? settings.defaultLiveMessageTemplate,
        variables: settings.variables,
        maxLength: settings.maxLength,
        disabled: false,
        preview: {
            display: streamer.twitchDisplayName ?? login, login,
            discordId: streamer.discordUserId,
            discordName: streamer.discordUser?.displayName ?? "streamer",
        },
    });
    const templateError = errors.field("template", editor.textarea);
    editor.textarea.after(templateError);
    editor.element.hidden = !ownMessage.checked;
    ownMessage.addEventListener("change", () => {
        editor.element.hidden = !ownMessage.checked;
    });

    const save = h("button", { type: "submit", class: "primary" }, "Save");
    const cancel = h("button", { type: "button", onclick: close }, "Cancel");
    const form = h("form", {
        class: "stack edit-streamer", onsubmit: (event) => {
            event.preventDefault();
            errors.clear();
            const patch: Record<string, unknown> = {};
            if (member.value() !== streamer.discordUserId) patch["discordUserId"] = member.value();
            const picked = channel.select.value;
            if (picked !== (current ?? "")) {
                if (picked === ROLE_BASED) patch["channelOverridePinned"] = false;
                else patch["channelId"] = picked || null;
            }
            const template = ownMessage.checked ? editor.textarea.value : null;
            if (template !== null && template.trim().length === 0) {
                errors.show(localError("template", "The message can't be empty. Untick \"Own message\" to use the server-wide one."));
                return;
            }
            if (template !== null && !editor.fits()) {
                errors.show(localError("template", "The message is too long: with a long stream title it would be over " + settings.maxLength + " characters."));
                return;
            }
            if (template !== streamer.template) patch["template"] = template;
            if (Object.keys(patch).length === 0) {
                close();
                return;
            }
            void busy(save, async () => {
                try {
                    await api<Streamer>("PATCH", guildPath(admin.guild) + "/streamers/" + encodeURIComponent(streamer.twitchId), patch);
                    await admin.reload("Saved " + displayName(streamer) + ".");
                } catch (error) {
                    errors.show(error);
                }
            });
        },
    },
        h("h3", null, "Edit " + displayName(streamer)),
        h("label", { for: member.input.id }, "Discord user"),
        member.element,
        errors.field("discordUserId", member.input),
        h("label", { for: channel.select.id }, "Channel"),
        h("div", { class: "row" }, channel.select, channel.note),
        errors.field("channelId", channel.select),
        errors.field("channelOverridePinned", channel.select),
        streamer.selfService ? h("p", { class: "hint" }, "They added themselves, so their channel follows their highest self-service role. "
            + "Picking a channel here pins it; \"Follow their roles\" unpins it.") : null,
        h("div", { class: "checkbox" }, ownMessage, h("label", { for: ownMessage.id }, "Own message (instead of the server-wide one)")),
        editor.element,
        streamer.template !== null && streamer.templateSetBy !== null ? h("p", { class: "hint" }, "Their message was last saved by "
            + (streamer.templateSetBy === streamer.discordUserId ? "them" : "user " + streamer.templateSetBy) + ".") : null,
        h("div", { class: "row" }, save, cancel),
        errors.general);
    return form;
}

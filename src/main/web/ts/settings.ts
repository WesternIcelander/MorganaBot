// The "Settings" page for server admins: the Problems panel, live notification settings, the server-wide live
// message, self-service roles, message permissions and admin roles.

import type { AdminRoles, GuildSummary, LeaveResult, MessageRules, RoleInfo, SelfServiceConfig, Settings } from "./api.js";
import {
    badge,
    channelPicker,
    GuildRefs,
    inlineIssues,
    roleLabel,
    rolePicker,
    roleProblemBadge,
    uid,
    wholeNumber,
} from "./admin-ui.js";
import { duration } from "./audit-text.js";
import { serverIssues, type ServerCheckGroup } from "./diagnose.js";
import { busy, FormErrors, h, replace, type Child } from "./dom.js";
import { api, ApiFailure } from "./http.js";
import { guildPath, type Page, type PageContext } from "./page.js";
import { ProblemsPanel } from "./problems.js";
import { confirmNameMatches } from "./super-text.js";
import { templateEditor } from "./template-editor.js";

export const settingsPage: Page = {
    id: "settings",
    label: "Settings",
    available: (guild) => guild.access === "admin",
    render: renderSettingsPage,
};

/** What every section needs. */
export interface AdminContext {
    guild: GuildSummary;
    refs: GuildRefs;
    panel: ProblemsPanel;
    /** Twitch is off for this server, so Twitch settings can't be changed. */
    twitchOff: boolean;
}

async function renderSettingsPage(root: HTMLElement, context: PageContext): Promise<void> {
    const path = guildPath(context.guild);
    const panel = new ProblemsPanel(context.guild);
    const [settings, adminRoles, rules, selfService, refs] = await Promise.all([
        api<Settings>("GET", path + "/settings"),
        api<AdminRoles>("GET", path + "/admin-roles"),
        api<MessageRules>("GET", path + "/message-rules"),
        api<SelfServiceConfig>("GET", path + "/self-service"),
        GuildRefs.load(context.guild),
    ]);
    const admin: AdminContext = { guild: context.guild, refs, panel, twitchOff: settings.twitchDisabledMessage !== null };
    replace(root,
        h("h1", null, "Settings"),
        settings.enabledTwitchTracking !== undefined ? twitchSection(context, settings, () => renderSettingsPage(root, context)) : null,
        settings.twitchDisabledMessage !== null ? h("p", { class: "notice warn", role: "status" }, settings.twitchDisabledMessage,
            " Twitch settings can't be changed until it's turned on.") : null,
        panel.element,
        notificationSection(admin, settings),
        serverTemplateSection(admin, settings),
        selfServiceSection(admin, selfService),
        messageRulesSection(admin, rules),
        accessSection(admin, adminRoles),
        settings.enabledTwitchTracking !== undefined ? removeSection(context) : null);
    await panel.load();
}

/**
 * The Twitch on/off switch (`enabledTwitchTracking`), only sent to super admin sessions. Turning it on or off changes
 * what every other section may do, so the page is drawn again afterwards.
 */
function twitchSection(context: PageContext, settings: Settings, redraw: () => Promise<void>): HTMLElement {
    const enabled = settings.enabledTwitchTracking === true;
    const errors = new FormErrors();
    const toggle = h("button", { type: "button", class: enabled ? "danger" : "primary" },
        enabled ? "Turn Twitch announcements off" : "Turn Twitch announcements on");
    toggle.addEventListener("click", () => {
        errors.clear();
        void busy(toggle, async () => {
            try {
                await api<Settings>("PATCH", guildPath(context.guild) + "/settings", { enabledTwitchTracking: !enabled });
            } catch (error) {
                errors.show(error);
                return;
            }
            context.serverChanged();
            await redraw();
        });
    });
    return h("section", { class: "card super-only", "aria-labelledby": "twitch-title" },
        h("div", { class: "card-head" },
            h("h2", { id: "twitch-title" }, "Twitch announcements"),
            h("span", { class: "badge super" }, "Super admin")),
        h("p", null, "Twitch announcements are ", h("strong", null, enabled ? "on" : "off"), " for this server."),
        h("p", { class: "hint" }, enabled
            ? "Turning them off stops the server's admins from changing Twitch settings. Their streamers and settings are kept."
            : "While off, the server's admins see a \"not enabled\" message and can't change Twitch settings. Only bot admins can turn this on."),
        h("p", { class: "hint" }, "The change is recorded in the server's audit log as made by a bot admin."),
        h("div", { class: "row" }, toggle),
        errors.field("enabledTwitchTracking", toggle),
        errors.general);
}

/**
 * "Remove bot from server" (super admins only; settings with `enabledTwitchTracking` are only sent to them). The
 * button only works once the server's name is typed exactly. Blocking is on by default, since without it anyone with
 * an invite link could add the bot back.
 */
function removeSection(context: PageContext): HTMLElement {
    const name = context.guild.name;
    const errors = new FormErrors();
    const confirmInput = h("input", { id: "remove-confirm", type: "text", autocomplete: "off" });
    const reason = h("textarea", { id: "remove-reason", rows: 2, maxlength: 500 });
    const block = h("input", { id: "remove-block", type: "checkbox", checked: true });
    const submit = h("button", { type: "submit", class: "danger", disabled: true }, "Remove bot from server");
    confirmInput.addEventListener("input", () => {
        submit.disabled = !confirmNameMatches(confirmInput.value, name);
    });
    const form = h("form", { class: "stack", novalidate: true },
        h("div", null,
            h("label", { for: "remove-confirm" }, "Type the server's name to confirm: ", h("strong", null, name)),
            confirmInput,
            errors.field("confirmName", confirmInput)),
        h("div", null,
            h("label", { for: "remove-reason" }, "Reason (optional, only bot admins see it)"),
            reason,
            errors.field("reason", reason)),
        h("div", { class: "checkbox" }, block, h("label", { for: "remove-block" }, "Block from re-adding")),
        h("p", { class: "hint" }, "Without a block, anyone with an invite link can add the bot back. The server's settings are kept either way, so unblocking and re-inviting restores them."),
        h("div", { class: "row" }, submit),
        errors.general);
    const section = h("section", { class: "card super-only danger-zone", "aria-labelledby": "remove-title" },
        h("div", { class: "card-head" },
            h("h2", { id: "remove-title" }, "Remove bot from server"),
            h("span", { class: "badge super" }, "Super admin")),
        h("p", null, "The bot leaves this server straight away. Its live posts stop and its Twitch subscriptions are dropped."),
        form);
    form.addEventListener("submit", (event) => {
        event.preventDefault();
        errors.clear();
        if (!confirmNameMatches(confirmInput.value, name)) {
            errors.show(localError("confirmName", "Type the server's name exactly as shown."));
            return;
        }
        void busy(submit, async () => {
            let result: LeaveResult;
            try {
                result = await api<LeaveResult>("POST", "/admin/guilds/" + encodeURIComponent(context.guild.id) + "/leave",
                    { confirmName: confirmInput.value, reason: reason.value, block: block.checked });
            } catch (error) {
                errors.show(error);
                return;
            }
            replace(section,
                h("h2", null, "Removed"),
                h("p", { class: "saved", role: "status" }, "The bot left " + result.name + (result.blocked ? " and blocked it from adding the bot back." : ".")));
            context.serverRemoved(result.blocked);
        }).finally(() => {
            submit.disabled = !confirmNameMatches(confirmInput.value, name);
        });
    });
    return section;
}

/** A place for Diagnose's warnings about one setting, kept up to date. */
function issuesFor(admin: AdminContext, group: ServerCheckGroup): HTMLElement {
    const element = h("div", { class: "inline-issues", hidden: true });
    admin.panel.subscribe((result) => inlineIssues(element, serverIssues(result, group)), element);
    return element;
}

/** A check made before sending, shown like the API's error for that field. */
export function localError(field: string, message: string): ApiFailure {
    return new ApiFailure(400, "invalid", message, field);
}

function savedLine(text: string): HTMLElement {
    return h("p", { class: "saved", role: "status" }, text);
}

// --- live notifications ---

function notificationSection(admin: AdminContext, initial: Settings): HTMLElement {
    const section = h("section", { class: "card", "aria-labelledby": "notifications-title" });
    const channelIssues = issuesFor(admin, "liveChannel");
    const roleIssues = issuesFor(admin, "liveRole");
    const deleteIssues = issuesFor(admin, "deleteOnOffline");
    const draw = (data: Settings, saved: string): void => {
        const disabled = admin.twitchOff;
        const errors = new FormErrors();
        const channel = channelPicker(admin.refs, {
            id: "live-channel", value: data.liveChannel?.id ?? null, empty: "None", missing: data.liveChannel, disabled,
        });
        const role = rolePicker(admin.refs, {
            id: "live-role", value: data.liveRole?.id ?? null, empty: "None", missing: data.liveRole, disabled,
            disabledReason: (r) => r.managed ? "belongs to a bot or integration" : null,
            note: (r) => r.botCanAssign ? null : "MorganaBot can't give this role" + (r.botCantAssignReason !== null ? ": " + r.botCantAssignReason : ""),
        });
        const deleteBox = h("input", { id: "delete-on-offline", type: "checkbox", disabled });
        deleteBox.checked = data.deleteOnOffline;
        const cooldown = h("input", {
            id: "cooldown", type: "number", min: 0, max: 604800, step: 1, inputmode: "numeric", disabled,
            value: String(data.notificationCooldownSeconds),
        });
        const cooldownText = h("span", { class: "muted" });
        const showCooldown = (): void => {
            const value = wholeNumber(cooldown);
            cooldownText.textContent = value === null ? "" : "= " + duration(value * 1000);
        };
        cooldown.addEventListener("input", showCooldown);
        showCooldown();
        const status = savedLine(saved);
        const save = h("button", { type: "submit", class: "primary", disabled }, "Save");
        const form = h("form", {
            class: "stack", novalidate: true, onsubmit: (event) => {
                event.preventDefault();
                errors.clear();
                status.textContent = "";
                const patch: Record<string, unknown> = {};
                if (channel.select.value !== (data.liveChannel?.id ?? "")) patch["liveChannelId"] = channel.select.value || null;
                if (role.select.value !== (data.liveRole?.id ?? "")) patch["liveRoleId"] = role.select.value || null;
                if (deleteBox.checked !== data.deleteOnOffline) patch["deleteOnOffline"] = deleteBox.checked;
                const seconds = wholeNumber(cooldown);
                if (seconds === null || seconds > 604800) {
                    errors.show(localError("notificationCooldownSeconds", "Enter a whole number of seconds from 0 (no cooldown) to 604800 (a week)."));
                    return;
                }
                if (seconds !== data.notificationCooldownSeconds) patch["notificationCooldownSeconds"] = seconds;
                if (Object.keys(patch).length === 0) {
                    status.textContent = "Nothing changed.";
                    return;
                }
                void busy(save, async () => {
                    try {
                        draw(await api<Settings>("PATCH", guildPath(admin.guild) + "/settings", patch), "Saved.");
                        void admin.panel.rediagnose();
                    } catch (error) {
                        errors.show(error);
                    }
                });
            },
        },
            h("label", { for: "live-channel" }, "Live channel"),
            h("div", { class: "row" }, channel.select, channel.note),
            errors.field("liveChannelId", channel.select),
            h("p", { class: "hint" }, "Where live posts go, unless a streamer has their own channel."),
            channelIssues,
            h("label", { for: "live-role" }, "Live role"),
            h("div", { class: "row" }, role.select, role.note),
            errors.field("liveRoleId", role.select),
            h("p", { class: "hint" }, "Given to streamers with a linked Discord account while they're live, and taken away when they stop."),
            roleIssues,
            h("div", { class: "checkbox" }, deleteBox, h("label", { for: "delete-on-offline" }, "Delete live posts when the stream ends")),
            errors.field("deleteOnOffline", deleteBox),
            deleteIssues,
            h("label", { for: "cooldown" }, "Notification cooldown (seconds)"),
            h("div", { class: "row" }, cooldown, cooldownText),
            errors.field("notificationCooldownSeconds", cooldown),
            h("p", { class: "hint" }, "If a streamer goes live again within this time after their stream ended (e.g. after a crash), "
                + "MorganaBot doesn't announce it again. With delete-on-offline on, it reposts without a notification. 0 turns it off."),
            h("div", { class: "row" }, save),
            errors.general,
            status);
        replace(section, h("h2", { id: "notifications-title" }, "Live notifications"), form);
    };
    draw(initial, "");
    return section;
}

// --- server-wide live message ---

function serverTemplateSection(admin: AdminContext, initial: Settings): HTMLElement {
    const section = h("section", { class: "card", "aria-labelledby": "template-title" });
    const draw = (data: Settings, saved: string): void => {
        const disabled = admin.twitchOff;
        const errors = new FormErrors();
        const editor = templateEditor(admin.refs, {
            label: "Message",
            value: data.liveMessageTemplate ?? data.defaultLiveMessageTemplate,
            variables: data.variables,
            maxLength: data.maxLength,
            disabled,
            preview: { display: "ExampleStreamer", login: "examplestreamer", discordId: "0", discordName: "ExampleStreamer" },
        });
        const fieldError = errors.field("liveMessageTemplate", editor.textarea);
        editor.textarea.after(fieldError);
        const status = savedLine(saved);
        const save = h("button", { type: "submit", class: "primary", disabled }, "Save");
        const undo = h("button", { type: "button", disabled }, "Undo changes");
        undo.addEventListener("click", () => {
            editor.textarea.value = data.liveMessageTemplate ?? data.defaultLiveMessageTemplate;
            errors.clear();
            editor.update();
        });
        const useDefault = data.liveMessageTemplate !== null ? h("button", { type: "button", disabled }, "Go back to the default message") : null;
        const send = (template: string | null, button: HTMLButtonElement, message: string): void => {
            errors.clear();
            status.textContent = "";
            void busy(button, async () => {
                try {
                    draw(await api<Settings>("PATCH", guildPath(admin.guild) + "/settings", { liveMessageTemplate: template }), message);
                    void admin.panel.rediagnose();
                } catch (error) {
                    errors.show(error);
                }
            });
        };
        useDefault?.addEventListener("click", () => {
            if (!window.confirm("Replace the server-wide message with MorganaBot's default?")) return;
            send(null, useDefault, "Now using the default message.");
        });
        const form = h("form", {
            class: "stack", novalidate: true, onsubmit: (event) => {
                event.preventDefault();
                const text = editor.textarea.value;
                if (text.trim().length === 0) {
                    errors.show(localError("liveMessageTemplate", "The live message can't be empty."));
                    return;
                }
                if (!editor.fits()) {
                    errors.show(localError("liveMessageTemplate", "The message is too long: with a long stream title it would be over " + data.maxLength + " characters."));
                    return;
                }
                send(text, save, "Saved.");
            },
        },
            h("p", { class: "hint" }, data.liveMessageTemplate === null
                ? "Posted when a streamer goes live, unless they have their own message. You're using MorganaBot's default message."
                : "Posted when a streamer goes live, unless they have their own message."),
            editor.element,
            h("div", { class: "row" }, save, undo, useDefault),
            errors.general,
            status);
        replace(section, h("h2", { id: "template-title" }, "Server-wide live message"), form);
    };
    draw(initial, "");
    return section;
}

// --- self-service ---

interface SelfServiceRow {
    roleId: string;
    channelId: string | null;
}

function selfServiceSection(admin: AdminContext, initial: SelfServiceConfig): HTMLElement {
    const section = h("section", { class: "card", "aria-labelledby": "self-service-title" });
    const issues = issuesFor(admin, "selfService");
    const draw = (data: SelfServiceConfig, saved: string): void => {
        const disabled = admin.twitchOff;
        const refs = admin.refs;
        const rows: SelfServiceRow[] = data.roles.map((entry) => ({ roleId: entry.role.id, channelId: entry.channel?.id ?? null }));
        const missing = new Map(data.roles.map((entry) => [entry.role.id, entry] as const));
        const errors = new FormErrors();
        const list = h("ol", { class: "role-list" });
        const dirty = h("p", { class: "hint unsaved", hidden: true }, "You have unsaved changes.");
        const listError = errors.field("roles", list);
        const markDirty = (): void => {
            dirty.hidden = false;
            status.textContent = "";
        };
        const addSlot = h("div", { class: "add-row" });
        const drawList = (): void => {
            // Highest in Discord's role order first: that's the order that decides which role wins.
            rows.sort((a, b) => refs.rolePosition(b.roleId) - refs.rolePosition(a.roleId));
            replace(list, rows.length === 0 ? h("li", { class: "muted empty" }, "No self-service roles. Members can't add themselves.") : null,
                rows.map((row, index) => {
                    const known = missing.get(row.roleId);
                    const role = refs.role(row.roleId);
                    const picker = channelPicker(refs, {
                        id: uid("ss-channel"), value: row.channelId, empty: "Server default", missing: known?.channel ?? null, disabled,
                    });
                    picker.select.addEventListener("change", () => {
                        row.channelId = picker.select.value || null;
                        markDirty();
                    });
                    const remove = h("button", { type: "button", class: "link-button danger-link", disabled }, "Remove");
                    remove.addEventListener("click", () => {
                        rows.splice(rows.indexOf(row), 1);
                        markDirty();
                        drawList();
                    });
                    const label = role !== null ? roleLabel(role) : roleLabel(known?.role ?? { id: row.roleId, name: null, exists: false });
                    return h("li", null,
                        h("span", { class: "rank", title: "Precedence" }, String(index + 1)),
                        h("span", { class: "role" }, label),
                        role === null ? badge("error", "Role deleted") : null,
                        h("label", { class: "visually-hidden", for: picker.select.id }, "Channel for " + label),
                        h("span", { class: "muted" }, "posts to"),
                        picker.select, picker.note, remove);
                }));
            drawAdd();
        };
        const drawAdd = (): void => {
            const taken = new Set(rows.map((row) => row.roleId));
            const role = rolePicker(refs, {
                id: uid("ss-add-role"), value: null, empty: "Choose a role…", disabled,
                filter: (r: RoleInfo) => !r.everyone && !r.managed && !taken.has(r.id),
            });
            const channel = channelPicker(refs, { id: uid("ss-add-channel"), value: null, empty: "Server default", disabled });
            const add = h("button", { type: "button", disabled }, "Add role");
            add.addEventListener("click", () => {
                if (role.select.value === "") {
                    role.select.focus();
                    return;
                }
                rows.push({ roleId: role.select.value, channelId: channel.select.value || null });
                markDirty();
                drawList();
            });
            replace(addSlot,
                h("label", { class: "visually-hidden", for: role.select.id }, "Role to add"),
                role.select,
                h("label", { class: "visually-hidden", for: channel.select.id }, "Its channel"),
                h("span", { class: "muted" }, "posts to"),
                channel.select, channel.note, add);
        };
        const days = h("input", {
            id: "remove-after-days", type: "number", min: 0, max: data.maxRemoveAfterDays, step: 1, inputmode: "numeric", disabled,
            value: String(data.removeAfterDays),
        });
        days.addEventListener("input", markDirty);
        const status = savedLine(saved);
        const save = h("button", { type: "submit", class: "primary", disabled }, "Save");
        const form = h("form", {
            class: "stack", novalidate: true, onsubmit: (event) => {
                event.preventDefault();
                errors.clear();
                const value = wholeNumber(days);
                if (value === null || value > data.maxRemoveAfterDays) {
                    errors.show(localError("removeAfterDays", "Enter a whole number of days from 0 (never) to " + data.maxRemoveAfterDays + "."));
                    return;
                }
                void busy(save, async () => {
                    try {
                        const next = await api<SelfServiceConfig>("PUT", guildPath(admin.guild) + "/self-service", {
                            roles: rows.map((row) => ({ roleId: row.roleId, channelId: row.channelId })),
                            removeAfterDays: value,
                        });
                        draw(next, "Saved.");
                        void admin.panel.rediagnose();
                    } catch (error) {
                        errors.show(error);
                    }
                });
            },
        },
            h("p", { class: "hint" }, "Members with one of these roles can add their own Twitch channel with /settwitchchannel "
                + "or on their My stream page. Each role can send their posts to its own channel."),
            h("p", { class: "hint" }, "If a member has several of these roles, the one highest in Discord's role order wins "
                + "(Server Settings → Roles), even if it has no channel of its own: then they post to the server default. "
                + "The list below is in that order."),
            list,
            listError,
            addSlot,
            issues,
            h("label", { for: "remove-after-days" }, "Remove self-added streamers after"),
            h("div", { class: "row" }, days, h("span", { class: "muted" }, "days without a self-service role (0 = never)")),
            errors.field("removeAfterDays", days),
            h("p", { class: "hint" }, "Losing every self-service role pauses their live posts straight away. They're removed after this many days."),
            h("div", { class: "row" }, save),
            dirty,
            errors.general,
            status);
        drawList();
        replace(section, h("h2", { id: "self-service-title" }, "Self-service"), form);
    };
    draw(initial, "");
    return section;
}

// --- message permissions ---

interface RuleRow {
    roleId: string;
    allowEveryone: boolean;
    mentionRoles: string[];
}

function messageRulesSection(admin: AdminContext, initial: MessageRules): HTMLElement {
    const section = h("section", { class: "card", "aria-labelledby": "rules-title" });
    const issues = issuesFor(admin, "messageRules");
    const draw = (data: MessageRules, saved: string): void => {
        const disabled = admin.twitchOff;
        const refs = admin.refs;
        const rows: RuleRow[] = data.rules.map((rule) => ({
            roleId: rule.role.id,
            allowEveryone: rule.allowEveryone,
            mentionRoles: rule.allowedMentionRoles.map((role) => role.id),
        }));
        const known = new Map<string, string>();
        for (const rule of data.rules) {
            known.set(rule.role.id, roleLabel(rule.role));
            for (const role of rule.allowedMentionRoles) known.set(role.id, roleLabel(role));
        }
        const labelOf = (id: string): string => {
            const role = refs.role(id);
            return role !== null ? roleLabel(role) : known.get(id) ?? roleLabel({ id, name: null, exists: false });
        };
        const errors = new FormErrors();
        const list = h("ul", { class: "rule-list" });
        const listError = errors.field("rules", list);
        const dirty = h("p", { class: "hint unsaved", hidden: true }, "You have unsaved changes.");
        const status = savedLine(saved);
        const markDirty = (): void => {
            dirty.hidden = false;
            status.textContent = "";
        };
        const addSlot = h("div", { class: "add-row" });
        const drawList = (): void => {
            rows.sort((a, b) => refs.rolePosition(b.roleId) - refs.rolePosition(a.roleId));
            replace(list, rows.length === 0 ? h("li", { class: "muted empty" }, "No rules. Only admins can write live messages.") : null,
                rows.map((row) => ruleItem(row)));
            drawAdd();
        };
        const ruleItem = (row: RuleRow): HTMLElement => {
            const everyoneId = uid("rule-everyone");
            const everyone = h("input", { id: everyoneId, type: "checkbox", disabled });
            everyone.checked = row.allowEveryone;
            everyone.addEventListener("change", () => {
                row.allowEveryone = everyone.checked;
                markDirty();
            });
            const count = h("span", null);
            const showCount = (): void => {
                count.textContent = row.mentionRoles.length === 0 ? "none" : row.mentionRoles.map(labelOf).join(", ");
            };
            showCount();
            const choices: Child[] = [];
            const mentionable = refs.roles.filter((role) => !role.everyone);
            const ids = new Set(mentionable.map((role) => role.id));
            const options = [...mentionable.map((role) => role.id), ...row.mentionRoles.filter((id) => !ids.has(id))];
            for (const id of options) {
                const boxId = uid("rule-mention");
                const box = h("input", { id: boxId, type: "checkbox", disabled });
                box.checked = row.mentionRoles.includes(id);
                box.addEventListener("change", () => {
                    if (box.checked) {
                        if (!row.mentionRoles.includes(id)) row.mentionRoles.push(id);
                    } else {
                        row.mentionRoles = row.mentionRoles.filter((x) => x !== id);
                    }
                    showCount();
                    markDirty();
                });
                choices.push(h("li", { class: "checkbox" }, box, h("label", { for: boxId }, labelOf(id), refs.role(id) === null ? " (deleted)" : "")));
            }
            const remove = h("button", { type: "button", class: "link-button danger-link", disabled }, "Remove rule");
            remove.addEventListener("click", () => {
                rows.splice(rows.indexOf(row), 1);
                markDirty();
                drawList();
            });
            const role = refs.role(row.roleId);
            return h("li", { class: "rule" },
                h("div", { class: "row" },
                    h("span", { class: "role" }, labelOf(row.roleId)),
                    role === null ? badge("error", "Role deleted") : null,
                    remove),
                h("div", { class: "checkbox" }, everyone, h("label", { for: everyoneId }, "Can use @everyone and @here")),
                h("details", { class: "mention-roles" },
                    h("summary", null, "Roles they can mention: ", count),
                    h("ul", { class: "checkbox-list" }, choices)));
        };
        const drawAdd = (): void => {
            const taken = new Set(rows.map((row) => row.roleId));
            const role = rolePicker(refs, {
                id: uid("rule-add"), value: null, empty: "Choose a role…", disabled,
                filter: (r) => !r.managed && !taken.has(r.id),
            });
            const add = h("button", { type: "button", disabled }, "Add rule");
            add.addEventListener("click", () => {
                if (role.select.value === "") {
                    role.select.focus();
                    return;
                }
                rows.push({ roleId: role.select.value, allowEveryone: false, mentionRoles: [] });
                markDirty();
                drawList();
            });
            replace(addSlot, h("label", { class: "visually-hidden", for: role.select.id }, "Role for a new rule"), role.select, add);
        };
        const save = h("button", { type: "submit", class: "primary", disabled }, "Save");
        const form = h("form", {
            class: "stack", novalidate: true, onsubmit: (event) => {
                event.preventDefault();
                errors.clear();
                void busy(save, async () => {
                    try {
                        const next = await api<MessageRules>("PUT", guildPath(admin.guild) + "/message-rules", {
                            rules: rows.map((row) => ({ roleId: row.roleId, allowEveryone: row.allowEveryone, allowedMentionRoles: row.mentionRoles })),
                        });
                        draw(next, "Saved.");
                        void admin.panel.rediagnose();
                    } catch (error) {
                        errors.show(error);
                    }
                });
            },
        },
            h("p", { class: "hint" }, "Streamers who aren't admins can write their own live message if they have one of these roles. "
                + "What they can mention is everything their matching rules allow together. Admins can always write any message."),
            h("p", { class: "hint" }, "Mentions are checked when they save: they can keep mentions that were already in their saved message, "
                + "but can't add new ones their rules don't allow. Pick @everyone to let every member write their own message."),
            list,
            listError,
            addSlot,
            issues,
            h("div", { class: "row" }, save),
            dirty,
            errors.general,
            status);
        drawList();
        replace(section, h("h2", { id: "rules-title" }, "Message permissions"), form);
    };
    draw(initial, "");
    return section;
}

// --- admin roles ---

function accessSection(admin: AdminContext, initial: AdminRoles): HTMLElement {
    const section = h("section", { class: "card", "aria-labelledby": "access-title" });
    const intro = h("p", { class: "hint" }, "Members with Discord's Administrator permission (and the server owner) can always open this "
        + "dashboard. Members with one of these roles can too.");
    const draw = (data: AdminRoles, saved: string): void => {
        const refs = admin.refs;
        if (!data.canEdit) {
            replace(section, h("h2", { id: "access-title" }, "Access"), intro,
                data.roles.length === 0
                    ? h("p", { class: "muted" }, "No admin roles.")
                    : h("ul", { class: "plain-list" }, data.roles.map((role) => h("li", null, h("span", { class: "role" }, roleLabel(role)), " ", roleProblemBadge(role)))),
                h("p", { class: "notice info" }, data.readOnlyReason ?? "You can't change the admin roles."));
            return;
        }
        const ids = data.roles.map((role) => role.id);
        const known = new Map(data.roles.map((role) => [role.id, role] as const));
        const errors = new FormErrors();
        const list = h("ul", { class: "plain-list" });
        const listError = errors.field("roles", list);
        const status = savedLine(saved);
        const dirty = h("p", { class: "hint unsaved", hidden: true }, "You have unsaved changes.");
        const addSlot = h("div", { class: "add-row" });
        const drawList = (): void => {
            ids.sort((a, b) => refs.rolePosition(b) - refs.rolePosition(a));
            replace(list, ids.length === 0 ? h("li", { class: "muted" }, "No admin roles.") : null, ids.map((id) => {
                const remove = h("button", { type: "button", class: "link-button danger-link" }, "Remove");
                remove.addEventListener("click", () => {
                    ids.splice(ids.indexOf(id), 1);
                    dirty.hidden = false;
                    status.textContent = "";
                    drawList();
                });
                const role = refs.role(id);
                return h("li", null, h("span", { class: "role" }, role !== null ? roleLabel(role) : roleLabel(known.get(id) ?? null)),
                    role === null ? [" ", badge("error", "Role deleted")] : null, " ", remove);
            }));
            const taken = new Set(ids);
            const picker = rolePicker(refs, {
                id: uid("admin-add"), value: null, empty: "Choose a role…",
                filter: (r) => !r.everyone && !r.managed && !taken.has(r.id),
            });
            const add = h("button", { type: "button" }, "Add role");
            add.addEventListener("click", () => {
                if (picker.select.value === "") {
                    picker.select.focus();
                    return;
                }
                ids.push(picker.select.value);
                dirty.hidden = false;
                status.textContent = "";
                drawList();
            });
            replace(addSlot, h("label", { class: "visually-hidden", for: picker.select.id }, "Admin role to add"), picker.select, add);
        };
        const save = h("button", { type: "submit", class: "primary" }, "Save");
        const form = h("form", {
            class: "stack", novalidate: true, onsubmit: (event) => {
                event.preventDefault();
                errors.clear();
                void busy(save, async () => {
                    try {
                        draw(await api<AdminRoles>("PUT", guildPath(admin.guild) + "/admin-roles", { roles: ids }), "Saved.");
                    } catch (error) {
                        errors.show(error);
                    }
                });
            },
        },
            intro,
            list,
            listError,
            addSlot,
            h("p", { class: "hint" }, "Only members with Discord's Administrator permission can change this list, so admins "
                + "through a role can't add or remove other admins."),
            h("div", { class: "row" }, save),
            dirty,
            errors.general,
            status);
        drawList();
        replace(section, h("h2", { id: "access-title" }, "Access"), form);
    };
    draw(initial, "");
    return section;
}

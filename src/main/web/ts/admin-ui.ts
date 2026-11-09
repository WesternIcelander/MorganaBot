// Building blocks shared by the server admin pages: the server's channels and roles, channel/role/member pickers,
// badges, and Diagnose check lists.

import type {
    ChannelInfo,
    ChannelRef,
    ChannelsResponse,
    Check,
    CheckLevel,
    GuildSummary,
    MemberInfo,
    MembersResponse,
    RoleInfo,
    RoleRef,
    RolesResponse,
} from "./api.js";
import { boldParts, levelSymbol, levelText } from "./diagnose.js";
import { errorMessage, h, replace, type Child } from "./dom.js";
import { api } from "./http.js";
import { guildPath } from "./page.js";

/** The server's channels and roles (roles highest first), loaded once per page. */
export class GuildRefs {
    readonly channels: ChannelInfo[];
    readonly roles: RoleInfo[];
    private readonly channelById = new Map<string, ChannelInfo>();
    private readonly roleById = new Map<string, RoleInfo>();

    constructor(channels: ChannelInfo[], roles: RoleInfo[]) {
        this.channels = channels;
        this.roles = roles;
        for (const channel of channels) this.channelById.set(channel.id, channel);
        for (const role of roles) this.roleById.set(role.id, role);
    }

    static async load(guild: GuildSummary): Promise<GuildRefs> {
        const [channels, roles] = await Promise.all([
            api<ChannelsResponse>("GET", guildPath(guild) + "/channels"),
            api<RolesResponse>("GET", guildPath(guild) + "/roles"),
        ]);
        return new GuildRefs(channels.channels, roles.roles);
    }

    channel(id: string | null): ChannelInfo | null {
        return id === null ? null : this.channelById.get(id) ?? null;
    }

    role(id: string | null): RoleInfo | null {
        return id === null ? null : this.roleById.get(id) ?? null;
    }

    /** Role names by ID, for previews. */
    roleNames(): Map<string, string> {
        const names = new Map<string, string>();
        for (const role of this.roles) names.set(role.id, role.name);
        return names;
    }

    /** Position in Discord's role order (higher is higher); deleted roles sort last. */
    rolePosition(id: string): number {
        return this.role(id)?.position ?? Number.NEGATIVE_INFINITY;
    }
}

// --- labels and badges ---

export function channelLabel(channel: ChannelRef | ChannelInfo | null, empty = "none"): string {
    if (channel === null) return empty;
    if (channel.name === null) return "deleted channel (" + channel.id + ")";
    return "#" + channel.name;
}

export function roleLabel(role: RoleRef | RoleInfo | null, empty = "none"): string {
    if (role === null) return empty;
    if (role.name === null) return "deleted role (" + role.id + ")";
    if ("everyone" in role && role.everyone) return "@everyone";
    return role.name.startsWith("@") ? role.name : "@" + role.name;
}

export function badge(kind: "" | "warn" | "error" | "live" | "ok" | "info", text: string, title?: string): HTMLElement {
    return h("span", { class: kind === "" ? "badge" : "badge " + kind, title }, text);
}

/** "Channel deleted" / "Can't post here" for a channel the bot can't use, or nothing. */
export function channelProblemBadge(channel: ChannelRef | ChannelInfo | null): HTMLElement | null {
    if (channel === null) return null;
    if ("exists" in channel && !channel.exists) return badge("error", "Channel deleted");
    if (!channel.botCanPost) return badge("error", "Can't post here", "MorganaBot is missing View Channel or Send Messages there.");
    return null;
}

/** A role that's gone, as a badge. */
export function roleProblemBadge(role: RoleRef | null): HTMLElement | null {
    if (role === null || role.exists) return null;
    return badge("error", "Role deleted");
}

export function levelBadge(level: CheckLevel): HTMLElement {
    const kind = level === "OK" ? "ok" : level === "WARNING" ? "warn" : "error";
    return h("span", { class: "badge " + kind, title: levelText(level) }, levelSymbol(level) + " " + levelText(level));
}

/** Text with Diagnose's `*bold*` parts in bold. */
export function richText(text: string): Child[] {
    return boldParts(text).map((part) => part.bold ? h("strong", null, part.text) : part.text);
}

/** A list of Diagnose checks: symbol, message and fix hint. */
export function checkList(checks: readonly Check[]): HTMLElement {
    return h("ul", { class: "checks" }, checks.map((check) => h("li", { class: "check " + check.level.toLowerCase() },
        h("span", { class: "check-symbol", "aria-label": levelText(check.level) }, levelSymbol(check.level)),
        h("div", null,
            h("div", null, richText(check.message)),
            check.fix !== null ? h("div", { class: "hint" }, "Fix: ", richText(check.fix)) : null))));
}

/** Warnings and errors next to a setting (nothing when there are none). */
export function inlineIssues(target: HTMLElement, checks: readonly Check[]): void {
    replace(target, checks.length === 0 ? null : checkList(checks));
    target.hidden = checks.length === 0;
}

// --- pickers ---

let pickerId = 0;

/** A unique element ID. */
export function uid(prefix: string): string {
    pickerId++;
    return prefix + "-" + pickerId;
}

export interface ChannelPickerOptions {
    id: string;
    /** The selected channel ID, or null for the empty option. */
    value: string | null;
    /** Label of the empty option (e.g. "None" or "Server default"). Omit for no empty option. */
    empty?: string;
    disabled?: boolean;
    /** Extra options before the channels: [value, label]. */
    extra?: [string, string][];
    /** A selected channel that isn't in the list any more. */
    missing?: ChannelRef | null;
}

/** A channel dropdown, with a note next to it that says when the bot can't post in the chosen channel. */
export function channelPicker(refs: GuildRefs, options: ChannelPickerOptions): { select: HTMLSelectElement; note: HTMLElement } {
    const select = h("select", { id: options.id, disabled: options.disabled });
    for (const [value, label] of options.extra ?? []) select.append(h("option", { value }, label));
    if (options.empty !== undefined) select.append(h("option", { value: "" }, options.empty));
    let group: HTMLOptGroupElement | null = null;
    let groupName: string | null = null;
    for (const channel of refs.channels) {
        if (channel.category !== groupName || group === null) {
            groupName = channel.category;
            group = document.createElement("optgroup");
            group.label = channel.category ?? "No category";
            select.append(group);
        }
        const suffix = (channel.type === "announcement" ? " (announcement)" : "") + (channel.botCanPost ? "" : " – can't post");
        group.append(h("option", { value: channel.id }, "#" + channel.name + suffix));
    }
    if (options.value !== null && refs.channel(options.value) === null && !(options.extra ?? []).some(([v]) => v === options.value)) {
        select.append(h("option", { value: options.value }, channelLabel(options.missing ?? { id: options.value, name: null, exists: false, botCanPost: false })));
    }
    select.value = options.value ?? "";
    const note = h("span", { class: "picker-note", "aria-live": "polite" });
    const update = (): void => {
        const value = select.value;
        if (value === "" || (options.extra ?? []).some(([v]) => v === value)) {
            replace(note);
            return;
        }
        const channel = refs.channel(value);
        replace(note, channel === null
            ? badge("error", "Channel deleted")
            : channelProblemBadge(channel));
    };
    select.addEventListener("change", update);
    update();
    return { select, note };
}

export interface RolePickerOptions {
    id: string;
    value: string | null;
    empty?: string;
    disabled?: boolean;
    /** Which roles to offer (default: every role but @everyone). */
    filter?: (role: RoleInfo) => boolean;
    /** Why a role is offered but disabled, or null. */
    disabledReason?: (role: RoleInfo) => string | null;
    /** A note to show next to a role (e.g. "the bot can't assign this"), or null. */
    note?: (role: RoleInfo) => string | null;
    missing?: RoleRef | null;
}

/** A role dropdown (highest first). */
export function rolePicker(refs: GuildRefs, options: RolePickerOptions): { select: HTMLSelectElement; note: HTMLElement } {
    const select = h("select", { id: options.id, disabled: options.disabled });
    if (options.empty !== undefined) select.append(h("option", { value: "" }, options.empty));
    const filter = options.filter ?? ((role: RoleInfo) => !role.everyone);
    for (const role of refs.roles) {
        if (!filter(role)) continue;
        const reason = options.disabledReason?.(role) ?? null;
        const option = h("option", { value: role.id }, roleLabel(role) + (reason !== null ? " – " + reason : ""));
        option.disabled = reason !== null && role.id !== options.value;
        select.append(option);
    }
    if (options.value !== null && refs.role(options.value) === null) {
        select.append(h("option", { value: options.value }, roleLabel(options.missing ?? { id: options.value, name: null, exists: false })));
    }
    select.value = options.value ?? "";
    const note = h("span", { class: "picker-note", "aria-live": "polite" });
    const update = (): void => {
        const role = refs.role(select.value === "" ? null : select.value);
        if (select.value !== "" && role === null) {
            replace(note, badge("error", "Role deleted"));
            return;
        }
        const text = role !== null ? options.note?.(role) ?? null : null;
        replace(note, text !== null ? badge("warn", text) : null);
    };
    select.addEventListener("change", update);
    update();
    return { select, note };
}

/** A member's name as the dashboard shows it, e.g. "Alice (@alice)". */
export function memberLabel(member: MemberInfo): string {
    return member.displayName !== member.username ? member.displayName + " (@" + member.username + ")" : "@" + member.username;
}

const SNOWFLAKE = /^\d{15,20}$/;

/**
 * Picks a Discord member: search the server's members by name, or paste a user ID. `value()` is the chosen user ID,
 * or null for nobody.
 */
export function memberPicker(guild: GuildSummary, options: { id: string; initial: MemberInfo | null; initialId: string | null; disabled?: boolean }):
    { element: HTMLElement; input: HTMLInputElement; value(): string | null } {
    let chosen: string | null = options.initialId;
    const current = h("span", { class: "member-current" });
    const clear = h("button", { type: "button", class: "link-button", disabled: options.disabled }, "Unlink");
    const input = h("input", {
        id: options.id, type: "text", autocomplete: "off", placeholder: "Search by name, or paste a user ID",
        disabled: options.disabled, maxlength: 100,
    });
    const results = h("ul", { class: "member-results", hidden: true });
    const drawCurrent = (label: string | null): void => {
        replace(current, chosen === null ? h("span", { class: "muted" }, "Nobody linked") : label ?? "User " + chosen);
        clear.hidden = chosen === null;
    };
    drawCurrent(options.initial !== null ? memberLabel(options.initial) : null);
    clear.addEventListener("click", () => {
        chosen = null;
        drawCurrent(null);
    });
    const pick = (id: string, label: string): void => {
        chosen = id;
        drawCurrent(label);
        input.value = "";
        results.hidden = true;
        replace(results);
    };
    let timer: number | undefined;
    let run = 0;
    input.addEventListener("input", () => {
        window.clearTimeout(timer);
        const q = input.value.trim();
        if (q.length === 0) {
            results.hidden = true;
            return;
        }
        timer = window.setTimeout(() => {
            const mine = ++run;
            const items: Child[] = [];
            if (SNOWFLAKE.test(q)) {
                items.push(h("li", null, h("button", { type: "button", class: "link-button", onclick: () => pick(q, "User " + q) }, "Use user ID " + q)));
            }
            void api<MembersResponse>("GET", guildPath(guild) + "/members?q=" + encodeURIComponent(q)).then((response) => {
                if (mine !== run) return;
                for (const member of response.members) {
                    items.push(h("li", null, h("button", {
                        type: "button", class: "link-button", onclick: () => pick(member.id, memberLabel(member)),
                    }, memberLabel(member) + (member.bot ? " [bot]" : ""))));
                }
                if (items.length === 0) items.push(h("li", { class: "muted" }, "No members found."));
                replace(results, items);
                results.hidden = false;
            }).catch((error: unknown) => {
                if (mine !== run) return;
                replace(results, items, h("li", { class: "error-text" }, errorMessage(error)));
                results.hidden = false;
            });
        }, 250);
    });
    const element = h("div", { class: "member-picker" }, h("div", { class: "row" }, current, clear), input, results);
    return { element, input, value: () => chosen };
}

/** A whole number from an input, or null if it isn't one. */
export function wholeNumber(input: HTMLInputElement): number | null {
    const text = input.value.trim();
    if (!/^\d+$/.test(text)) return null;
    const value = Number(text);
    return Number.isSafeInteger(value) ? value : null;
}

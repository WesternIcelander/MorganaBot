// The "Audit log" page for server admins: who changed what and when, newest first, filterable by setting and by who
// made the change, paged with "Load more".

import type { AuditEntry, AuditPage } from "./api.js";
import {
    actorsOf, actorText, attemptsText, AUDIT_TYPES, changeText, factText, isTemplate, SUPER_ADMIN_AUDIT_TYPES, typeLabel, valueText,
} from "./audit-text.js";
import { badge } from "./admin-ui.js";
import { busy, errorMessage, formatTime, h, replace, type Child } from "./dom.js";
import { api } from "./http.js";
import { guildPath, type Page, type PageContext } from "./page.js";

export const auditPage: Page = {
    id: "audit",
    label: "Audit log",
    available: (guild) => guild.access === "admin",
    render: renderAuditPage,
};

async function renderAuditPage(root: HTMLElement, context: PageContext): Promise<void> {
    const path = guildPath(context.guild) + "/audit";
    const typeSelect = auditTypeSelect("audit-type", "All settings", context.me.superAdmin);
    const userSelect = h("select", { id: "audit-user" }, h("option", { value: "" }, "Anyone"));
    const people = new Map<string, string>();
    const list = h("ol", { class: "audit-list" });
    const more = h("button", { type: "button", hidden: true }, "Load more");
    const message = h("p", { class: "muted", "aria-live": "polite" });
    let nextBefore: string | null = null;
    let generation = 0;

    const query = (before: string | null): string => {
        const params = new URLSearchParams();
        if (typeSelect.value !== "") params.set("type", typeSelect.value);
        if (userSelect.value !== "") params.set("user", userSelect.value);
        if (before !== null) params.set("before", before);
        const text = params.toString();
        return path + (text.length > 0 ? "?" + text : "");
    };

    const addPeople = (entries: AuditEntry[]): void => {
        let added = false;
        for (const [id, label] of actorsOf(entries)) {
            if (!people.has(id)) {
                people.set(id, label);
                added = true;
            }
        }
        if (!added) return;
        const selected = userSelect.value;
        replace(userSelect, h("option", { value: "" }, "Anyone"),
            [...people.entries()].sort((a, b) => a[1].localeCompare(b[1])).map(([id, label]) => h("option", { value: id }, label)));
        userSelect.value = selected;
    };

    const show = (page: AuditPage, append: boolean): void => {
        if (!append) replace(list);
        for (const entry of page.entries) list.append(entryItem(entry));
        addPeople(page.entries);
        nextBefore = page.nextBefore;
        more.hidden = nextBefore === null;
        message.textContent = list.childElementCount === 0
            ? (typeSelect.value !== "" || userSelect.value !== "" ? "No changes match these filters." : "No changes recorded yet.")
            : "";
    };

    const reload = async (): Promise<void> => {
        const mine = ++generation;
        message.textContent = "Loading…";
        try {
            const page = await api<AuditPage>("GET", query(null));
            if (mine === generation) show(page, false);
        } catch (error) {
            if (mine === generation) message.textContent = errorMessage(error);
        }
    };
    typeSelect.addEventListener("change", () => void reload());
    userSelect.addEventListener("change", () => void reload());
    more.addEventListener("click", () => {
        const mine = generation;
        void busy(more, async () => {
            try {
                const page = await api<AuditPage>("GET", query(nextBefore));
                if (mine === generation) show(page, true);
            } catch (error) {
                message.textContent = errorMessage(error);
            }
        });
    });

    const first = await api<AuditPage>("GET", query(null));
    replace(root,
        h("h1", null, "Audit log"),
        h("section", { class: "card" },
            h("p", { class: "hint" }, "Every change to this server's settings and streamers, from commands, this dashboard and the bot itself. "
                + "Names are as they were at the time. The newest 1000 changes are kept."),
            h("div", { class: "row filters" },
                h("label", { for: "audit-type" }, "Setting"), typeSelect,
                h("label", { for: "audit-user" }, "Changed by"), userSelect),
            list,
            message,
            more));
    show(first, false);
}

/** The "Setting"/"Event" filter: every entry type, plus the super-admin-only ones in their own group if asked. */
export function auditTypeSelect(id: string, all: string, superAdmin: boolean): HTMLSelectElement {
    return h("select", { id },
        h("option", { value: "" }, all),
        superAdmin
            ? [h("optgroup", { label: "Settings" }, Object.entries(AUDIT_TYPES).map(([value, label]) => h("option", { value }, label))),
                h("optgroup", { label: "Bot admins only" }, Object.entries(SUPER_ADMIN_AUDIT_TYPES).map(([value, label]) => h("option", { value }, label)))]
            : Object.entries(AUDIT_TYPES).map(([value, label]) => h("option", { value }, label)));
}

/**
 * One entry as a list item. `server` (the merged super admin view) is shown before the time.
 */
export function entryItem(entry: AuditEntry, server: Child = null): HTMLElement {
    const about: Child[] = [];
    if ((entry.streamer ?? null) !== null) about.push(" · ", valueText(entry.streamer));
    // The merged view already names the server on the next line.
    const subject = entry.subject ?? null;
    if (subject !== null && !(server !== null && subject.kind === "server")) about.push(" · ", valueText(subject));
    const changes: Child[] = [];
    for (const change of entry.changes ?? []) {
        if (isTemplate(change)) {
            changes.push(h("li", null, "Message changed:",
                h("div", { class: "template-change" },
                    h("div", null, h("span", { class: "muted" }, "Before"), templateBlock(valueText(change.old ?? null, change.field, entry.type), (change.old ?? null) === null)),
                    h("div", null, h("span", { class: "muted" }, "After"), templateBlock(valueText(change.new ?? null, change.field, entry.type), (change.new ?? null) === null)))));
        } else {
            changes.push(h("li", null, changeText(change, entry.type)));
        }
    }
    const facts = entry.facts ?? [];
    const attempts = attemptsText(entry);
    return h("li", { class: "audit-entry" + (entry.actor?.source === "superAdmin" ? " by-bot-admin" : "") },
        h("div", { class: "audit-head" },
            h("strong", null, typeLabel(entry.type)),
            about,
            entry.visibility === "superAdmin" ? [" ", badge("warn", "Bot admins only")] : null),
        h("div", { class: "muted small" }, server !== null ? [server, " · "] : null, formatTime(entry.time), " · ", actorText(entry.actor)),
        attempts !== null
            ? h("div", { class: "small" }, attempts + ", first " + formatTime(entry.firstTime ?? entry.time) + ", last " + formatTime(entry.time))
            : null,
        changes.length > 0 ? h("ul", { class: "changes" }, changes) : null,
        facts.length > 0 ? h("ul", { class: "changes" }, facts.map((fact) => h("li", null, factText(fact)))) : null,
        (entry.note ?? null) !== null ? h("p", { class: "hint" }, entry.note) : null);
}

function templateBlock(text: string, empty: boolean): HTMLElement {
    return empty ? h("p", { class: "muted" }, "(" + text + ")") : h("pre", { class: "template" }, text);
}

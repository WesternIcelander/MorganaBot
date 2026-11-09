// The super admin "Audit log" page: every server's audit entries merged (including super-admin-only ones and servers
// the bot has left), newest first, filterable by server and event type, paged with "Load more". It reads the audit
// files, so it works while Discord is down.

import type { AdminAuditItem, AdminAuditPage, AdminAuditServer } from "./api.js";
import { auditTypeSelect, entryItem } from "./audit.js";
import { serverLabel } from "./audit-text.js";
import { busy, errorMessage, h, replace } from "./dom.js";
import { api } from "./http.js";

/** Draws the merged Audit log page into `root`. `openGuild` opens a server the bot is in. */
export async function renderAdminAuditPage(root: HTMLElement, openGuild: (id: string, name: string | null) => void): Promise<void> {
    const serverSelect = h("select", { id: "admin-audit-server" }, h("option", { value: "" }, "All servers"));
    const typeSelect = auditTypeSelect("admin-audit-type", "All events", true);
    const list = h("ol", { class: "audit-list" });
    const more = h("button", { type: "button", hidden: true }, "Load more");
    const message = h("p", { class: "muted", "aria-live": "polite" });
    let servers: AdminAuditServer[] = [];
    let nextBefore: string | null = null;
    let generation = 0;

    const query = (before: string | null): string => {
        const params = new URLSearchParams();
        if (serverSelect.value !== "") params.set("guild", serverSelect.value);
        if (typeSelect.value !== "") params.set("type", typeSelect.value);
        if (before !== null) params.set("before", before);
        const text = params.toString();
        return "/admin/audit" + (text.length > 0 ? "?" + text : "");
    };

    const drawServers = (list: AdminAuditServer[]): void => {
        servers = list;
        const selected = serverSelect.value;
        replace(serverSelect, h("option", { value: "" }, "All servers"),
            list.map((server) => h("option", { value: server.id },
                serverLabel(server.id, server.name) + (server.inServer === false ? " (bot not in it)" : ""))));
        serverSelect.value = list.some((s) => s.id === selected) ? selected : "";
    };

    const serverLink = (item: AdminAuditItem): HTMLElement => {
        const label = serverLabel(item.guildId, item.guildName);
        const known = servers.find((s) => s.id === item.guildId);
        if (known?.inServer !== true) return h("span", null, label);
        const link = h("a", { href: "?guild=" + encodeURIComponent(item.guildId) }, label);
        link.addEventListener("click", (event) => {
            event.preventDefault();
            openGuild(item.guildId, item.guildName);
        });
        return link;
    };

    const show = (page: AdminAuditPage, append: boolean): void => {
        drawServers(page.servers);
        if (!append) replace(list);
        for (const item of page.entries) list.append(entryItem(item.entry, serverLink(item)));
        nextBefore = page.nextBefore;
        more.hidden = nextBefore === null;
        message.textContent = list.childElementCount === 0
            ? (serverSelect.value !== "" || typeSelect.value !== "" ? "No entries match these filters." : "No entries recorded yet.")
            : "";
    };

    const reload = async (): Promise<void> => {
        const mine = ++generation;
        message.textContent = "Loading…";
        try {
            const page = await api<AdminAuditPage>("GET", query(null));
            if (mine === generation) show(page, false);
        } catch (error) {
            if (mine === generation) message.textContent = errorMessage(error);
        }
    };
    serverSelect.addEventListener("change", () => void reload());
    typeSelect.addEventListener("change", () => void reload());
    more.addEventListener("click", () => {
        const mine = generation;
        void busy(more, async () => {
            try {
                const page = await api<AdminAuditPage>("GET", query(nextBefore));
                if (mine === generation) show(page, true);
            } catch (error) {
                message.textContent = errorMessage(error);
            }
        });
    });

    const first = await api<AdminAuditPage>("GET", query(null));
    replace(root,
        h("h1", null, "Audit log"),
        h("section", { class: "card" },
            h("p", { class: "hint" }, "Every server's audit log in one list, including servers the bot has left, plus entries only bot admins see: "
                + "removing and blocking servers, and blocked servers that tried to add the bot back. "
                + "Repeated attempts within an hour are combined. Each server keeps its newest 1000 entries."),
            h("div", { class: "row filters" },
                h("label", { for: "admin-audit-server" }, "Server"), serverSelect,
                h("label", { for: "admin-audit-type" }, "Event"), typeSelect),
            list,
            message,
            more));
    show(first, false);
}

// The super admin sidebar: the "System" item (with a red dot when any status card is red), "Blocked servers", and the
// server switcher
// (every server the bot is in, searchable by name or ID, filterable by "Twitch on" and "has problems").

import type { AdminGuild, AdminGuilds, SystemStatus } from "./api.js";
import { errorMessage, h, replace } from "./dom.js";
import { api, ApiFailure } from "./http.js";
import { filterGuilds, guildSummary, type GuildFilter } from "./super-text.js";

/** How often the red dot is checked while no System page is open. */
const STATUS_POLL_MS = 60_000;

export interface SidebarHandlers {
    openSystem(): void;
    openBlocked(): void;
    openGuild(guild: AdminGuild): void;
}

export type SidebarSelection = { kind: "system" } | { kind: "blocked" } | { kind: "guild"; id: string } | null;

export class Sidebar {
    readonly element: HTMLElement;
    private readonly handlers: SidebarHandlers;
    private readonly systemLink: HTMLAnchorElement;
    private readonly blockedLink: HTMLAnchorElement;
    private readonly dot: HTMLElement;
    private readonly list: HTMLElement;
    private readonly count: HTMLElement;
    private readonly search: HTMLInputElement;
    private readonly twitchOnly: HTMLInputElement;
    private readonly problemsOnly: HTMLInputElement;
    private guilds: AdminGuild[] = [];
    private discordAvailable = true;
    private loaded = false;
    private selection: SidebarSelection = null;
    private timer: number | null = null;

    constructor(element: HTMLElement, handlers: SidebarHandlers) {
        this.element = element;
        this.handlers = handlers;
        this.dot = h("span", { class: "red-dot", hidden: true, title: "A status card is red" },
            h("span", { class: "visually-hidden" }, " (something is failing)"));
        this.systemLink = h("a", { href: "?view=system", class: "side-link" }, "System", this.dot);
        this.systemLink.addEventListener("click", (event) => {
            event.preventDefault();
            this.handlers.openSystem();
        });
        this.blockedLink = h("a", { href: "?view=blocked", class: "side-link" }, "Blocked servers");
        this.blockedLink.addEventListener("click", (event) => {
            event.preventDefault();
            this.handlers.openBlocked();
        });
        this.search = h("input", { id: "server-search", type: "text", placeholder: "Name or ID", autocomplete: "off" });
        this.twitchOnly = h("input", { id: "filter-twitch", type: "checkbox" });
        this.problemsOnly = h("input", { id: "filter-problems", type: "checkbox" });
        for (const input of [this.search, this.twitchOnly, this.problemsOnly]) {
            input.addEventListener("input", () => this.drawList());
        }
        this.list = h("ul", { class: "server-list" });
        this.count = h("p", { class: "muted small", "aria-live": "polite" });
        const refresh = h("button", { type: "button", class: "link-button small" }, "Reload list");
        refresh.addEventListener("click", () => void this.loadGuilds());
        replace(element,
            h("ul", { class: "side-nav" }, h("li", null, this.systemLink), h("li", null, this.blockedLink)),
            h("h2", { class: "side-title" }, "Servers"),
            h("label", { for: "server-search", class: "visually-hidden" }, "Search servers"),
            this.search,
            h("div", { class: "side-filters" },
                h("div", { class: "checkbox" }, this.twitchOnly, h("label", { for: "filter-twitch" }, "Twitch enabled")),
                h("div", { class: "checkbox" }, this.problemsOnly, h("label", { for: "filter-problems" }, "Has problems"))),
            h("div", { class: "row" }, this.count, refresh),
            this.list);
    }

    show(): void {
        this.element.hidden = false;
        document.body.classList.add("with-sidebar");
    }

    hide(): void {
        this.stopPolling();
        this.element.hidden = true;
        document.body.classList.remove("with-sidebar");
    }

    /** The server summary for an ID, once the list is loaded. */
    guild(id: string): AdminGuild | undefined {
        return this.guilds.find((g) => g.id === id);
    }

    select(selection: SidebarSelection): void {
        this.selection = selection;
        if (selection?.kind === "system") this.systemLink.setAttribute("aria-current", "page");
        else this.systemLink.removeAttribute("aria-current");
        if (selection?.kind === "blocked") this.blockedLink.setAttribute("aria-current", "page");
        else this.blockedLink.removeAttribute("aria-current");
        for (const link of this.list.querySelectorAll<HTMLAnchorElement>("a[data-id]")) {
            if (selection?.kind === "guild" && link.dataset["id"] === selection.id) link.setAttribute("aria-current", "page");
            else link.removeAttribute("aria-current");
        }
    }

    /** Shows the red dot on "System" when any status card is red. */
    setStatus(status: SystemStatus): void {
        this.dot.hidden = !status.anyRed;
        this.systemLink.title = status.anyRed ? "A status card is red" : "";
    }

    async loadGuilds(): Promise<void> {
        if (!this.loaded) replace(this.list, h("li", { class: "loading" }, "Loading…"));
        try {
            const result = await api<AdminGuilds>("GET", "/admin/guilds");
            this.guilds = result.guilds;
            this.discordAvailable = result.discordAvailable;
            this.loaded = true;
            this.drawList();
        } catch (error) {
            if (error instanceof ApiFailure && error.sessionExpired) return;
            replace(this.list, h("li", { class: "error-text small" }, "Couldn't load the servers: " + errorMessage(error)));
        }
    }

    /** Checks the status now and then every minute, for the red dot. */
    startPolling(): void {
        this.stopPolling();
        void this.checkStatus();
        this.timer = window.setInterval(() => void this.checkStatus(), STATUS_POLL_MS);
    }

    stopPolling(): void {
        if (this.timer !== null) window.clearInterval(this.timer);
        this.timer = null;
    }

    private async checkStatus(): Promise<void> {
        try {
            this.setStatus(await api<SystemStatus>("GET", "/admin/system"));
        } catch (error) {
            if (error instanceof ApiFailure && error.sessionExpired) this.stopPolling();
            // Otherwise keep the last known state; the next poll tries again.
        }
    }

    private filter(): GuildFilter {
        return { query: this.search.value, twitchOnly: this.twitchOnly.checked, problemsOnly: this.problemsOnly.checked };
    }

    private drawList(): void {
        if (!this.loaded) return;
        if (!this.discordAvailable) {
            this.count.textContent = "";
            replace(this.list, h("li", { class: "muted small" }, "Discord is unavailable, so the server list is empty."));
            return;
        }
        const shown = filterGuilds(this.guilds, this.filter());
        this.count.textContent = shown.length === this.guilds.length
            ? this.guilds.length + (this.guilds.length === 1 ? " server" : " servers")
            : shown.length + " of " + this.guilds.length + " servers";
        if (shown.length === 0) {
            replace(this.list, h("li", { class: "muted small" }, this.guilds.length === 0 ? "The bot isn't in any server." : "No server matches."));
            return;
        }
        replace(this.list, shown.map((guild) => this.item(guild)));
        this.select(this.selection);
    }

    private item(guild: AdminGuild): HTMLElement {
        const icon = guild.iconUrl !== null
            ? h("img", { class: "server-icon", src: guild.iconUrl, alt: "" })
            : h("span", { class: "server-icon placeholder", "aria-hidden": "true" }, initials(guild.name));
        const link = h("a", { href: "?guild=" + encodeURIComponent(guild.id), class: "server-item" },
            icon,
            h("span", { class: "server-text" },
                h("span", { class: "server-title" }, guild.name,
                    guild.problems > 0 || guild.error !== undefined ? h("span", { class: "problem-dot", title: "Has problems" },
                        h("span", { class: "visually-hidden" }, " (has problems)")) : null),
                h("span", { class: "server-id" }, guild.id),
                h("span", { class: "server-summary" }, guildSummary(guild)),
                guild.error !== undefined ? h("span", { class: "error-text small" }, guild.error) : null));
        link.dataset["id"] = guild.id;
        link.addEventListener("click", (event) => {
            event.preventDefault();
            this.handlers.openGuild(guild);
        });
        return h("li", null, link);
    }
}

function initials(name: string): string {
    const words = name.trim().split(/\s+/).filter((w) => w.length > 0);
    return words.slice(0, 2).map((w) => Array.from(w)[0] ?? "").join("").toUpperCase() || "?";
}

// The super admin "System" page: status cards for Discord, Twitch API, EventSub and Storage, general info (version,
// uptime, counts) and the system log. It only uses /admin/… routes, so it works while Discord is down.

import type { HealthCard, SystemLogEntry, SystemLogPage, SystemStatus } from "./api.js";
import { busy, errorMessage, formatTime, h, replace } from "./dom.js";
import { api, ApiFailure, seg } from "./http.js";
import { detailText, logQuery, relative, severityLabel, stateClass, stateLabel, uptimeText } from "./super-text.js";

/** How often the status cards refresh while the page is open. */
const REFRESH_MS = 30_000;

/**
 * Draws the System page into `root`. `onStatus` is told about every status loaded (for the sidebar's red dot). The
 * page refreshes its cards until `root` is taken off the page.
 */
export async function renderSystemPage(root: HTMLElement, onStatus: (status: SystemStatus) => void): Promise<void> {
    const status = await api<SystemStatus>("GET", "/admin/system");
    onStatus(status);

    const statusArea = h("div");
    const refreshed = h("span", { class: "muted small", "aria-live": "polite" });
    const refresh = h("button", { type: "button" }, "Refresh");
    let shown = status;
    let loadedAt = Date.now();
    const drawStatus = (): void => {
        replace(statusArea, generalSection(shown), cardsSection(shown));
        refreshed.textContent = "Updated " + new Date(loadedAt).toLocaleTimeString();
    };
    const reload = async (): Promise<void> => {
        try {
            shown = await api<SystemStatus>("GET", "/admin/system");
            loadedAt = Date.now();
            onStatus(shown);
            drawStatus();
        } catch (error) {
            if (error instanceof ApiFailure && error.sessionExpired) {
                window.clearInterval(timer);
                return;
            }
            refreshed.textContent = "Couldn't refresh: " + errorMessage(error);
        }
    };
    refresh.addEventListener("click", () => void busy(refresh, reload));
    const timer = window.setInterval(() => {
        if (!root.isConnected) {
            window.clearInterval(timer);
            return;
        }
        void reload();
    }, REFRESH_MS);
    drawStatus();

    const log = new LogSection(status.componentLabels);
    replace(root,
        h("div", { class: "card-head" }, h("h1", null, "System"), h("div", { class: "row" }, refreshed, refresh)),
        statusArea,
        log.element);
    await log.load();
}

function generalSection(status: SystemStatus): HTMLElement {
    const facts: [string, string][] = [];
    facts.push(["Version", status.version ?? "unknown (not built by Maven)"]);
    if (status.buildTime !== null) facts.push(["Built", status.buildTime]);
    if (status.startedAt !== null) {
        const uptime = status.uptimeSeconds !== null ? " (up " + uptimeText(status.uptimeSeconds) + ")" : "";
        facts.push(["Started", formatTime(status.startedAt) + uptime]);
    }
    facts.push(["Servers", status.servers !== null ? String(status.servers) : "unknown (Discord unavailable)"]);
    facts.push(["Streamers tracked", status.streamersTracked !== null ? String(status.streamersTracked) : "unknown (Discord unavailable)"]);
    return h("section", { class: "card", "aria-labelledby": "general-title" },
        h("h2", { id: "general-title" }, "General"),
        status.discordAvailable ? null
            : h("p", { class: "notice warn" }, "Discord isn't connected, so server pages can't be opened. This page and the log still work."),
        h("dl", { class: "facts" }, facts.map(([label, value]) => [h("dt", null, label), h("dd", null, value)])));
}

function cardsSection(status: SystemStatus): HTMLElement {
    return h("section", { class: "health-cards", "aria-label": "Status" }, status.components.map((card) => healthCard(card)));
}

function healthCard(card: HealthCard): HTMLElement {
    const now = Date.now();
    const cls = stateClass(card.state);
    return h("article", { class: "card health-card " + cls, "aria-labelledby": "card-" + card.id },
        h("div", { class: "card-head" },
            h("h2", { id: "card-" + card.id }, card.label),
            h("span", { class: "badge state " + cls }, stateLabel(card.state))),
        h("p", { class: "health-message" }, card.message),
        h("p", { class: "hint" }, "Since " + formatTime(card.since) + " (" + relative(card.since, now) + ")"),
        card.lastError !== null ? h("div", { class: "last-error" },
            h("p", { class: "small" }, h("strong", null, "Latest error"),
                card.lastErrorTime !== null ? " · " + formatTime(card.lastErrorTime) + " (" + relative(card.lastErrorTime, now) + ")" : ""),
            h("p", { class: "small error-detail" }, card.lastError)) : null,
        card.details.length > 0 ? h("dl", { class: "facts small" }, card.details.map((detail) => [
            h("dt", null, detail.label),
            h("dd", null, detailText(detail, now, (ms) => formatTime(ms))),
        ])) : null);
}

/** The system log: filters, entries newest first, "Load more", dismiss. */
class LogSection {
    readonly element: HTMLElement;
    private readonly list: HTMLElement;
    private readonly footer: HTMLElement;
    private readonly summary: HTMLElement;
    private readonly component: HTMLSelectElement;
    private readonly severity: HTMLSelectElement;
    private readonly labels: Record<string, string>;
    private nextBefore: string | null = null;
    /** Bumped on every reload, so a slow "load more" can't append to a newer list. */
    private generation = 0;

    constructor(labels: Record<string, string>) {
        this.labels = labels;
        this.component = h("select", { id: "log-component" },
            h("option", { value: "" }, "All components"),
            Object.entries(labels).map(([id, label]) => h("option", { value: id }, label)));
        this.severity = h("select", { id: "log-severity" },
            h("option", { value: "" }, "Errors and warnings"),
            h("option", { value: "ERROR" }, "Errors"),
            h("option", { value: "WARNING" }, "Warnings"));
        this.component.addEventListener("change", () => void this.load());
        this.severity.addEventListener("change", () => void this.load());
        this.list = h("ul", { class: "log-list" });
        this.footer = h("div", { class: "row log-footer" });
        this.summary = h("p", { class: "muted small", "aria-live": "polite" });
        this.element = h("section", { class: "card", "aria-labelledby": "log-title" },
            h("h2", { id: "log-title" }, "System log"),
            h("p", { class: "hint" }, "Errors and warnings for the whole bot, most recent first. Repeats are combined into one entry with a count."),
            h("div", { class: "row filters" },
                h("label", { for: "log-component" }, "Component"), this.component,
                h("label", { for: "log-severity" }, "Severity"), this.severity),
            this.summary,
            this.list,
            this.footer);
    }

    async load(): Promise<void> {
        const run = ++this.generation;
        replace(this.list, h("li", { class: "loading" }, "Loading…"));
        replace(this.footer);
        try {
            const page = await api<SystemLogPage>("GET", "/admin/system/log" + logQuery(this.component.value, this.severity.value, null));
            if (run !== this.generation) return;
            replace(this.list);
            this.summary.textContent = page.total === 0 ? "" : plural(page.total, "entry", "entries") + " in the log.";
            if (page.entries.length === 0) {
                replace(this.list, h("li", { class: "empty muted" },
                    this.component.value === "" && this.severity.value === "" ? "Nothing logged. All quiet." : "Nothing matches these filters."));
            }
            this.add(page);
        } catch (error) {
            if (run !== this.generation) return;
            if (error instanceof ApiFailure && error.sessionExpired) return;
            const retry = h("button", { type: "button" }, "Try again");
            retry.addEventListener("click", () => void this.load());
            replace(this.list, h("li", { class: "notice error" }, h("p", null, errorMessage(error)), retry));
        }
    }

    private add(page: SystemLogPage): void {
        for (const entry of page.entries) this.list.append(this.entry(entry));
        this.nextBefore = page.nextBefore;
        replace(this.footer);
        if (this.nextBefore === null) return;
        const more = h("button", { type: "button" }, "Load more");
        more.addEventListener("click", () => {
            const run = this.generation;
            void busy(more, async () => {
                try {
                    const next = await api<SystemLogPage>("GET", "/admin/system/log"
                        + logQuery(this.component.value, this.severity.value, this.nextBefore));
                    if (run !== this.generation) return;
                    this.add(next);
                } catch (error) {
                    if (!(error instanceof ApiFailure && error.sessionExpired)) {
                        this.footer.append(h("span", { class: "error-text" }, errorMessage(error)));
                    }
                }
            });
        });
        this.footer.append(more);
    }

    private entry(entry: SystemLogEntry): HTMLElement {
        const now = Date.now();
        const severity = entry.severity === "ERROR" ? "error" : "warn";
        const dismiss = h("button", { type: "button", class: "small" }, "Dismiss");
        const error = h("span", { class: "error-text small" });
        const item = h("li", { class: "log-entry " + severity },
            h("div", { class: "log-head" },
                h("span", { class: "badge " + severity }, severityLabel(entry.severity)),
                h("span", { class: "log-component" }, this.labels[entry.component] ?? entry.component),
                entry.count > 1 ? h("span", { class: "badge", title: "Happened " + entry.count + " times" }, "×" + entry.count) : null,
                h("span", { class: "spacer" }),
                dismiss),
            h("p", { class: "log-message" }, entry.message),
            h("p", { class: "hint" },
                entry.count > 1
                    ? "First " + formatTime(entry.time) + " · last " + formatTime(entry.lastSeen) + " (" + relative(entry.lastSeen, now) + ")"
                    : formatTime(entry.lastSeen) + " (" + relative(entry.lastSeen, now) + ")"),
            entry.detail !== null ? h("details", { class: "stack-trace" },
                h("summary", null, "Stack trace"),
                h("pre", null, entry.detail)) : null,
            error);
        dismiss.addEventListener("click", () => {
            void busy(dismiss, async () => {
                try {
                    await api<unknown>("DELETE", "/admin/system/log/" + seg(entry.id));
                    item.remove();
                } catch (failure) {
                    if (failure instanceof ApiFailure && failure.status === 404) {
                        item.remove(); // already dismissed (another tab)
                    } else if (!(failure instanceof ApiFailure && failure.sessionExpired)) {
                        error.textContent = errorMessage(failure);
                    }
                }
            });
        });
        return item;
    }
}

function plural(n: number, one: string, many: string): string {
    return n + " " + (n === 1 ? one : many);
}

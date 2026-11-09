// The dashboard shell: who's logged in (GET /me), the server dropdown, the page tabs, logout, and the
// "run /dashboard again" notice when the session has expired. Super admin sessions get the sidebar (System page,
// Blocked servers, Audit log and server switcher) instead of the dropdown.

import type { AdminGuild, GuildSummary, Me } from "./api.js";
import { busy, errorMessage, h, replace } from "./dom.js";
import { api, ApiFailure, setCsrfToken, setSessionExpiredHandler } from "./http.js";
import type { Page, PageContext } from "./page.js";
import { auditPage } from "./audit.js";
import { settingsPage } from "./settings.js";
import { streamersPage } from "./streamers.js";
import { streamPage } from "./stream.js";
import { Sidebar } from "./sidebar.js";
import { renderSystemPage } from "./system.js";
import { renderBlockedPage } from "./blocked.js";
import { renderAdminAuditPage } from "./admin-audit.js";

/** Every page, in tab order. A server opens on the first page it offers (admins: Settings, streamers: My stream). */
const PAGES: Page[] = [settingsPage, streamersPage, auditPage, streamPage];

/** The page a server opens on. */
function defaultPage(guild: GuildSummary | null): string {
    const first = guild === null ? undefined : PAGES.find((page) => page.available(guild));
    return (first ?? PAGES[0]!).id;
}

const LOGIN_HELP = "Run /dashboard in Discord to get a new login link.";

let me: Me | null = null;
let current: GuildSummary | null = null;
/** The page from the URL or the tabs; null for the server's default page. */
let currentPage: string | null = null;
/** A super admin page that isn't about one server (no server selected), or null. */
type View = "system" | "blocked" | "audit";
let view: View | null = null;

const VIEW_TITLES: Record<View, string> = { system: "System", blocked: "Blocked servers", audit: "Audit log" };
let sidebar: Sidebar | null = null;
/** Bumped on every navigation, so a slow page load can't draw over a newer one. */
let generation = 0;

function el<T extends HTMLElement>(id: string): T {
    const element = document.getElementById(id);
    if (element === null) throw new Error("Missing #" + id);
    return element as T;
}

const banner = (): HTMLElement => el("banner");
const content = (): HTMLElement => el("content");
const nav = (): HTMLElement => el("nav");
const account = (): HTMLElement => el("account");
const serverPicker = (): HTMLElement => el("server-picker");

function showBanner(kind: "error" | "warn" | "info", ...text: string[]): void {
    const element = banner();
    element.className = "notice " + kind;
    replace(element, text.join(" "));
    element.hidden = false;
}

function sessionExpired(): void {
    showBanner("error", "Your session has expired.", LOGIN_HELP);
    sidebar?.hide();
    replace(account());
    replace(serverPicker());
    replace(nav());
    // The page stays as it is, so nothing typed is lost (it can be copied), but nothing more can be sent.
    for (const control of content().querySelectorAll<HTMLButtonElement | HTMLInputElement | HTMLTextAreaElement>("button, input, textarea, select")) {
        control.disabled = true;
    }
}

/** A full-page message instead of a server's pages. */
function message(title: string, ...lines: string[]): void {
    replace(nav());
    replace(content(), h("section", { class: "card" }, h("h1", null, title), lines.map((line) => h("p", null, line))));
}

function guildFromUrl(): string | null {
    return new URLSearchParams(window.location.search).get("guild");
}

function pageFromUrl(): string | null {
    return new URLSearchParams(window.location.search).get("page");
}

function viewFromUrl(): View | null {
    const value = new URLSearchParams(window.location.search).get("view");
    return value === "system" || value === "blocked" || value === "audit" ? value : null;
}

function writeUrl(push: boolean): void {
    const params = new URLSearchParams();
    if (view !== null) {
        params.set("view", view);
    } else {
        if (current !== null) params.set("guild", current.id);
        if (currentPage !== null && currentPage !== defaultPage(current)) params.set("page", currentPage);
    }
    const query = params.toString();
    const url = window.location.pathname + (query.length > 0 ? "?" + query : "");
    if (push) window.history.pushState(null, "", url);
    else window.history.replaceState(null, "", url);
}

function drawAccount(user: Me): void {
    const logout = h("button", { type: "button", class: "link-button" }, "Log out");
    logout.addEventListener("click", () => {
        void busy(logout, async () => {
            try {
                await api<unknown>("POST", "/logout");
            } catch (error) {
                if (!(error instanceof ApiFailure && error.sessionExpired)) {
                    showBanner("error", "Couldn't log out: " + errorMessage(error));
                    return;
                }
            }
            me = null;
            setCsrfToken(null);
            sidebar?.hide();
            replace(account());
            replace(serverPicker());
            banner().hidden = true;
            message("Logged out", LOGIN_HELP);
        });
    });
    replace(account(),
        user.superAdmin ? h("span", { class: "badge super" }, "Super admin") : null,
        h("span", { class: "user" }, user.name ?? "User " + user.userId),
        logout);
}

function drawServerPicker(user: Me): void {
    const picker = serverPicker();
    if (user.superAdmin) {
        // The sidebar is the switcher; the header only names what's open.
        const name = current === null ? null : sidebar?.guild(current.id)?.name ?? current.name;
        replace(picker, view !== null ? h("span", { class: "server-name" }, VIEW_TITLES[view])
            : name !== null ? h("span", { class: "server-name" }, name) : null);
        return;
    }
    if (user.guilds.length <= 1) {
        replace(picker, current !== null ? h("span", { class: "server-name" }, current.name) : null);
        return;
    }
    const select = h("select", { id: "server", "aria-label": "Server" });
    for (const guild of user.guilds) {
        const option = h("option", { value: guild.id }, guild.name);
        option.selected = current !== null && guild.id === current.id;
        select.append(option);
    }
    select.addEventListener("change", () => {
        const guild = user.guilds.find((g) => g.id === select.value);
        if (guild === undefined) return;
        current = guild;
        currentPage = null;
        writeUrl(true);
        void openPage();
    });
    replace(picker, h("label", { for: "server", class: "visually-hidden" }, "Server"), select);
}

function drawNav(guild: GuildSummary): void {
    const pages = PAGES.filter((page) => page.available(guild));
    if (pages.length <= 1) {
        replace(nav());
        return;
    }
    replace(nav(), h("ul", null, pages.map((page) => {
        const link = h("a", { href: "?guild=" + encodeURIComponent(guild.id) + "&page=" + encodeURIComponent(page.id) }, page.label);
        if (page.id === (currentPage ?? defaultPage(guild))) link.setAttribute("aria-current", "page");
        link.addEventListener("click", (event) => {
            event.preventDefault();
            currentPage = page.id;
            writeUrl(true);
            void openPage();
        });
        return h("li", null, link);
    })));
}

async function openPage(): Promise<void> {
    if (me === null) return;
    if (view !== null) {
        await openView(view);
        return;
    }
    if (current === null) return;
    const guild = current;
    sidebar?.select({ kind: "guild", id: guild.id });
    const pages = PAGES.filter((page) => page.available(guild));
    const page = pages.find((p) => p.id === currentPage) ?? pages[0]!;
    if (currentPage !== null && currentPage !== page.id) {
        // Not available on this server (e.g. an admin page after losing admin access): open the default instead.
        currentPage = null;
        writeUrl(false);
    }
    drawServerPicker(me);
    drawNav(guild);
    const run = ++generation;
    const root = h("div", { class: "page" }, h("p", { class: "loading" }, "Loading…"));
    replace(content(), root);
    const context: PageContext = {
        me, guild, accessChanged: reloadMe, serverChanged: () => void sidebar?.loadGuilds(), serverRemoved,
    };
    try {
        await page.render(root, context);
    } catch (error) {
        if (run !== generation) return;
        if (error instanceof ApiFailure && error.sessionExpired) return;
        if (error instanceof ApiFailure && error.code === "no_access") {
            replace(root, h("p", { class: "notice error" }, "You don't have access to this server's dashboard."));
        } else if (error instanceof ApiFailure && error.code === "discord_unavailable") {
            replace(root, h("p", { class: "notice warn" }, "Discord is unavailable right now, so this server can't be loaded. Try again in a few minutes."));
        } else {
            const retry = h("button", { type: "button" }, "Try again");
            retry.addEventListener("click", () => void openPage());
            replace(root, h("div", { class: "notice error" }, h("p", null, errorMessage(error)), retry));
        }
    }
}

/** A super admin page that isn't about one server: System, Blocked servers or the merged Audit log. */
async function openView(which: View): Promise<void> {
    if (me === null) return;
    view = which;
    sidebar?.select({ kind: which });
    drawServerPicker(me);
    replace(nav());
    const run = ++generation;
    const root = h("div", { class: "page" }, h("p", { class: "loading" }, "Loading…"));
    replace(content(), root);
    try {
        if (which === "system") await renderSystemPage(root, (status) => sidebar?.setStatus(status));
        else if (which === "blocked") await renderBlockedPage(root);
        else await renderAdminAuditPage(root, openGuildById);
    } catch (error) {
        if (run !== generation) return;
        if (error instanceof ApiFailure && error.sessionExpired) return;
        const retry = h("button", { type: "button" }, "Try again");
        retry.addEventListener("click", () => void openView(which));
        replace(root, h("div", { class: "notice error" }, h("p", null, errorMessage(error)), retry));
    }
}

/** Opens a super admin page from the sidebar. */
function goToView(which: View): void {
    view = which;
    writeUrl(true);
    void openView(which);
}

/**
 * The bot was removed from the open server: it can't be opened any more. The removal message stays up for a moment,
 * then the shell moves to Blocked servers (or System) and reloads the lists.
 */
function serverRemoved(blocked: boolean): void {
    const removedId = current?.id;
    void sidebar?.loadGuilds();
    window.setTimeout(() => {
        if (me === null || current?.id !== removedId || view !== null) return; // moved on already
        me = { ...me, guilds: me.guilds.filter((g) => g.id !== removedId) };
        current = null;
        currentPage = null;
        goToView(blocked ? "blocked" : "system");
    }, 2500);
}

/** A server picked on the Audit log page (only offered for servers the bot is in). */
function openGuildById(id: string, name: string | null): void {
    const listed = sidebar?.guild(id);
    if (listed !== undefined) {
        openFromSidebar(listed);
        return;
    }
    if (me === null) return;
    view = null;
    current = me.guilds.find((g) => g.id === id) ?? { id, name: name ?? "Server " + id, iconUrl: null, access: "admin" };
    currentPage = null;
    writeUrl(true);
    void openPage();
}

/** A server picked in the sidebar. Super admins can open every server, even one /me didn't list yet. */
function openFromSidebar(guild: AdminGuild): void {
    if (me === null) return;
    view = null;
    current = me.guilds.find((g) => g.id === guild.id) ?? { id: guild.id, name: guild.name, iconUrl: guild.iconUrl, access: "admin" };
    currentPage = null;
    writeUrl(true);
    void openPage();
}

/** Sets up the sidebar for a super admin session (once), or hides it. */
function setUpSidebar(user: Me): void {
    if (!user.superAdmin) {
        sidebar?.hide();
        return;
    }
    if (sidebar === null) {
        sidebar = new Sidebar(el("sidebar"), {
            openSystem: () => goToView("system"),
            openBlocked: () => goToView("blocked"),
            openAudit: () => goToView("audit"),
            openGuild: openFromSidebar,
        });
    }
    sidebar.show();
    sidebar.startPolling();
    void sidebar.loadGuilds().then(() => {
        // Names a server opened by ID before the list was loaded.
        if (me !== null) drawServerPicker(me);
    });
}

/** Loads /me and opens the server from the URL (or the first one). */
async function reloadMe(): Promise<void> {
    let user: Me;
    try {
        user = await api<Me>("GET", "/me");
    } catch (error) {
        if (error instanceof ApiFailure && error.sessionExpired) {
            // Opened without a session: the page says so, the banner would only repeat it.
            if (me === null) banner().hidden = true;
            message("Not logged in", LOGIN_HELP);
            return;
        }
        const retry = h("button", { type: "button" }, "Try again");
        retry.addEventListener("click", () => void reloadMe());
        replace(content(), h("div", { class: "notice error" }, h("p", null, errorMessage(error)), retry));
        return;
    }
    me = user;
    setCsrfToken(user.csrfToken);
    drawAccount(user);
    setUpSidebar(user);
    if (!user.discordAvailable) {
        showBanner("warn", "Discord is unavailable right now, so server pages may not load.");
    } else {
        banner().hidden = true;
    }
    if (!user.superAdmin) view = null;
    const wanted = current?.id ?? guildFromUrl();
    current = user.guilds.find((g) => g.id === wanted) ?? null;
    if (user.superAdmin) {
        if (current === null && wanted !== null && view === null) {
            // A server /me doesn't list (e.g. the bot just joined): super admins can still open it.
            current = { id: wanted, name: "Server " + wanted, iconUrl: null, access: "admin" };
        }
        if (current === null && view === null) current = user.guilds[0] ?? null;
        // Super admins land on the System page when there's no server to show, e.g. while Discord is down.
        if (view === null && (current === null || !user.discordAvailable)) view = "system";
        writeUrl(false);
        await openPage();
        return;
    }
    if (current === null) current = user.guilds[0] ?? null;
    if (current === null) {
        drawServerPicker(user);
        message("No servers", "You don't have access to any server's dashboard right now.",
            "Server admins can open the full dashboard, and streamers can open their own entry.");
        return;
    }
    writeUrl(false);
    await openPage();
}

function start(): void {
    setSessionExpiredHandler(sessionExpired);
    const page = pageFromUrl();
    if (page !== null && PAGES.some((p) => p.id === page)) currentPage = page;
    view = viewFromUrl();
    window.addEventListener("popstate", () => {
        if (me === null) return;
        view = me.superAdmin ? viewFromUrl() : null;
        if (view !== null) {
            void openPage();
            return;
        }
        const id = guildFromUrl();
        const listed = id === null ? undefined : sidebar?.guild(id);
        current = me.guilds.find((g) => g.id === id)
            ?? (me.superAdmin && listed !== undefined ? { id: listed.id, name: listed.name, iconUrl: listed.iconUrl, access: "admin" } : undefined)
            ?? me.guilds[0] ?? null;
        if (current === null && me.superAdmin) {
            view = "system";
            void openPage();
            return;
        }
        const p = pageFromUrl();
        currentPage = p !== null && PAGES.some((x) => x.id === p) ? p : null;
        void openPage();
    });
    void reloadMe();
}

start();

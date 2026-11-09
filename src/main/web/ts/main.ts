// The dashboard shell: who's logged in (GET /me), the server dropdown, the page tabs, logout, and the
// "run /dashboard again" notice when the session has expired.

import type { GuildSummary, Me } from "./api.js";
import { busy, errorMessage, h, replace } from "./dom.js";
import { api, ApiFailure, setCsrfToken, setSessionExpiredHandler } from "./http.js";
import type { Page, PageContext } from "./page.js";
import { streamPage } from "./stream.js";

/** Every page, in tab order. Server admin pages are added here as they're built. */
const PAGES: Page[] = [streamPage];

const LOGIN_HELP = "Run /dashboard in Discord to get a new login link.";

let me: Me | null = null;
let current: GuildSummary | null = null;
let currentPage: string = PAGES[0]!.id;
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

function writeUrl(push: boolean): void {
    const params = new URLSearchParams();
    if (current !== null) params.set("guild", current.id);
    if (currentPage !== PAGES[0]!.id) params.set("page", currentPage);
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
        currentPage = PAGES[0]!.id;
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
        if (page.id === currentPage) link.setAttribute("aria-current", "page");
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
    if (me === null || current === null) return;
    const guild = current;
    const pages = PAGES.filter((page) => page.available(guild));
    const page = pages.find((p) => p.id === currentPage) ?? pages[0]!;
    currentPage = page.id;
    drawServerPicker(me);
    drawNav(guild);
    const run = ++generation;
    const root = h("div", { class: "page" }, h("p", { class: "loading" }, "Loading…"));
    replace(content(), root);
    const context: PageContext = { me, guild, accessChanged: reloadMe };
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
    if (!user.discordAvailable) {
        showBanner("warn", "Discord is unavailable right now, so server pages may not load.");
    } else {
        banner().hidden = true;
    }
    const wanted = current?.id ?? guildFromUrl();
    current = user.guilds.find((g) => g.id === wanted) ?? user.guilds[0] ?? null;
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
    window.addEventListener("popstate", () => {
        if (me === null) return;
        const id = guildFromUrl();
        current = me.guilds.find((g) => g.id === id) ?? me.guilds[0] ?? null;
        const p = pageFromUrl();
        currentPage = p !== null && PAGES.some((x) => x.id === p) ? p : PAGES[0]!.id;
        void openPage();
    });
    void reloadMe();
}

start();

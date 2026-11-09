// The super admin "Blocked servers" page: every server blocked from adding the bot back (name at the time of the
// block, ID, when, by whom, reason), with an Unblock button. It reads the servers' files, so it works while Discord is
// down.

import type { BlockedServer, BlockedServers } from "./api.js";
import { busy, errorMessage, formatTime, h, replace } from "./dom.js";
import { api, ApiFailure, seg } from "./http.js";
import { blockedByText, blockedName, relative } from "./super-text.js";

/** Draws the Blocked servers page into `root`. */
export async function renderBlockedPage(root: HTMLElement): Promise<void> {
    const result = await api<BlockedServers>("GET", "/admin/blocked");
    const list = h("ul", { class: "blocked-list" });
    const summary = h("p", { class: "muted small", "aria-live": "polite" });
    const update = (): void => {
        const count = list.querySelectorAll("li.blocked-entry").length;
        summary.textContent = count === 0 ? "" : count + (count === 1 ? " blocked server." : " blocked servers.");
        if (count === 0) replace(list, h("li", { class: "empty muted" }, "No servers are blocked."));
    };
    for (const server of result.blocked) list.append(entry(server, update));
    update();
    replace(root,
        h("h1", null, "Blocked servers"),
        h("section", { class: "card", "aria-labelledby": "blocked-title" },
            h("h2", { id: "blocked-title", class: "visually-hidden" }, "Blocked servers"),
            h("p", { class: "hint" }, "The bot leaves these servers straight away whenever someone adds it back. "
                + "Unblocking doesn't add the bot back: the server has to invite it again, and its old settings are kept."),
            summary,
            list));
}

function entry(server: BlockedServer, changed: () => void): HTMLElement {
    const now = Date.now();
    const unblock = h("button", { type: "button" }, "Unblock");
    const error = h("p", { class: "error-text small", role: "alert", hidden: true });
    const facts: [string, string][] = [["Server ID", server.id]];
    if (server.blockedAt !== null) facts.push(["Blocked", formatTime(server.blockedAt) + " (" + relative(server.blockedAt, now) + ")"]);
    facts.push(["By", blockedByText(server)]);
    facts.push(["Reason", server.reason ?? "No reason given"]);
    const item = h("li", { class: "blocked-entry" },
        h("div", { class: "card-head" },
            h("h3", null, blockedName(server)),
            unblock),
        server.inServer === true
            ? h("p", { class: "notice warn" }, "The bot is still in this server. It leaves when it next starts or is added again.")
            : null,
        h("dl", { class: "facts small" }, facts.map(([label, value]) => [h("dt", null, label), h("dd", null, value)])),
        error);
    unblock.addEventListener("click", () => {
        if (!window.confirm("Unblock " + blockedName(server) + "? Anyone with an invite link can then add the bot back.")) return;
        error.hidden = true;
        void busy(unblock, async () => {
            try {
                await api<unknown>("DELETE", "/admin/blocked/" + seg(server.id));
            } catch (failure) {
                if (failure instanceof ApiFailure && failure.sessionExpired) return;
                if (!(failure instanceof ApiFailure && failure.status === 404)) {
                    error.textContent = errorMessage(failure);
                    error.hidden = false;
                    return;
                }
                // Already unblocked (another tab): drop it as well.
            }
            item.remove();
            changed();
        });
    });
    return item;
}

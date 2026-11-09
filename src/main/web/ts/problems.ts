// The Problems panel at the top of the server admin pages: current Diagnose errors and warnings, recorded failures,
// and a banner when Twitch is failing for the whole bot. Diagnose runs when the page loads (GET …/problems) and again
// after every save (POST …/diagnose); pages subscribe to show results next to their settings and streamer rows.

import type { DiagnoseResult, GuildSummary, Problem, ProblemsResponse, StreamerResult } from "./api.js";
import { checkList, levelBadge } from "./admin-ui.js";
import { actionLabel, hasAnything, issues, sortProblems, targetLabel } from "./diagnose.js";
import { busy, errorMessage, formatTime, h, replace, type Child } from "./dom.js";
import { api, ApiFailure } from "./http.js";
import { guildPath } from "./page.js";

type Listener = (result: DiagnoseResult | null) => void;

export class ProblemsPanel {
    readonly element: HTMLElement;
    private readonly guild: GuildSummary;
    private diagnose: DiagnoseResult | null = null;
    private problems: Problem[] = [];
    private twitchMessage: string | null = null;
    private error: string | null = null;
    private running = false;
    /** Whether the admin opened the minimized all-passed view, so a re-run after a save doesn't close it again. */
    private passedOpen = false;
    private listeners: { listener: Listener; owner: HTMLElement | null }[] = [];
    /** Bumped on every Diagnose run, so a slow one can't replace a newer result. */
    private run = 0;

    constructor(guild: GuildSummary) {
        this.guild = guild;
        this.element = h("section", { class: "card problems", "aria-labelledby": "problems-title", "aria-live": "polite" });
    }

    get result(): DiagnoseResult | null {
        return this.diagnose;
    }

    /**
     * Called with every new Diagnose result (and once straight away). With an `owner`, the listener is dropped once
     * that element is no longer on the page (e.g. a redrawn table row).
     */
    subscribe(listener: Listener, owner?: HTMLElement): void {
        this.listeners.push({ listener, owner: owner ?? null });
        listener(this.diagnose);
    }

    /** Loads recorded problems and a fresh Diagnose. Failures are shown in the panel, not thrown. */
    async load(): Promise<void> {
        const mine = ++this.run;
        try {
            const response = await api<ProblemsResponse>("GET", guildPath(this.guild) + "/problems");
            if (mine !== this.run) return;
            this.problems = response.problems;
            this.twitchMessage = response.twitchTrouble ? response.twitchMessage ?? "Twitch is having problems, posts may be delayed" : null;
            this.error = null;
            this.setResult(response.diagnose);
        } catch (error) {
            if (mine !== this.run) return;
            this.error = "Couldn't load problems: " + errorMessage(error);
            this.draw();
        }
    }

    /** Runs Diagnose again (after a save, or from the button). Failures are shown in the panel, not thrown. */
    async rediagnose(): Promise<void> {
        const mine = ++this.run;
        this.running = true;
        this.draw();
        try {
            const result = await api<DiagnoseResult>("POST", guildPath(this.guild) + "/diagnose");
            if (mine !== this.run) return;
            this.error = null;
            this.setResult(result);
        } catch (error) {
            if (mine !== this.run) return;
            this.error = "Diagnose failed: " + errorMessage(error);
        } finally {
            if (mine === this.run) {
                this.running = false;
                this.draw();
            }
        }
    }

    /** Runs Diagnose for one streamer and merges the result in. */
    async diagnoseStreamer(twitchId: string): Promise<StreamerResult | null> {
        const result = await api<DiagnoseResult>("POST", guildPath(this.guild) + "/diagnose?streamer=" + encodeURIComponent(twitchId));
        const one = result.streamers.find((s) => s.twitchId === twitchId) ?? null;
        if (this.diagnose !== null && one !== null) {
            const streamers = this.diagnose.streamers.map((s) => s.twitchId === twitchId ? one : s);
            this.setResult({ ...this.diagnose, streamers, ...counts(streamers) });
        }
        return one;
    }

    private setResult(result: DiagnoseResult | null): void {
        this.diagnose = result;
        this.draw();
        this.listeners = this.listeners.filter((l) => l.owner === null || l.owner.isConnected);
        for (const { listener } of this.listeners) listener(result);
    }

    private draw(): void {
        const rerun = h("button", { type: "button", disabled: this.running }, this.running ? "Running Diagnose…" : "Run Diagnose");
        rerun.addEventListener("click", () => void this.rediagnose());
        const result = this.diagnose;
        const anything = hasAnything(result, this.problems, this.twitchMessage !== null);
        const head = h("div", { class: "card-head" },
            h("h2", { id: "problems-title" }, anything ? "Problems" : "Health check"),
            rerun);
        const body: Child[] = [];
        if (this.twitchMessage !== null) body.push(h("p", { class: "notice warn" }, this.twitchMessage));
        if (this.error !== null) body.push(h("p", { class: "notice error" }, this.error));
        if (!anything && result !== null && this.error === null) {
            // Everything passed: minimized to one line, details on click.
            const passed = h("details", { class: "health-passed", open: this.passedOpen },
                h("summary", null, levelBadge("OK"), " All checks passed. ", h("span", { class: "muted" }, result.summary)),
                this.diagnoseSection(result));
            passed.addEventListener("toggle", () => { this.passedOpen = passed.open; });
            body.push(passed);
        } else {
            body.push(this.diagnoseSection(result));
        }
        if (this.problems.length > 0) body.push(this.failuresSection());
        this.element.classList.toggle("has-problems", anything);
        replace(this.element, head, body);
    }

    private diagnoseSection(result: DiagnoseResult | null): HTMLElement {
        if (result === null) return h("p", { class: "muted" }, "Diagnose couldn't run. Try again.");
        const section = h("div", { class: "diagnose" },
            h("p", { class: "summary" }, result.summary,
                result.serverLevel !== "OK" ? " Server-wide: " + (result.serverLevel === "ERROR" ? "error." : "warning.") : "",
                " ", h("span", { class: "muted" }, "Checked " + formatTime(result.time) + ".")));
        // Server-wide first, then one group per streamer. Groups with something to report are open.
        const serverIssues = issues(result.server);
        section.append(group("Server-wide", result.serverLevel, result.server, serverIssues.length > 0));
        for (const streamer of result.streamers) {
            section.append(group(streamerName(streamer), streamer.level, streamer.checks, streamer.level !== "OK", streamer.channel));
        }
        return section;
    }

    private failuresSection(): HTMLElement {
        const list = sortProblems(this.problems);
        const dismissAll = h("button", { type: "button" }, "Dismiss all");
        dismissAll.addEventListener("click", () => {
            if (!window.confirm("Dismiss all " + list.length + " recorded failures?")) return;
            void busy(dismissAll, async () => {
                try {
                    await api<unknown>("DELETE", guildPath(this.guild) + "/problems");
                    this.problems = [];
                    this.draw();
                } catch (error) {
                    this.error = "Couldn't dismiss: " + errorMessage(error);
                    this.draw();
                }
            });
        });
        return h("div", { class: "failures" },
            h("div", { class: "card-head" }, h("h3", null, "Recent failures"), dismissAll),
            h("p", { class: "hint" }, "Things MorganaBot tried to do when someone went live, and why they failed. Newest first."),
            h("ul", { class: "failure-list" }, list.map((problem) => this.failureItem(problem))));
    }

    private failureItem(problem: Problem): HTMLElement {
        const dismiss = h("button", { type: "button", class: "link-button" }, "Dismiss");
        dismiss.addEventListener("click", () => {
            void busy(dismiss, async () => {
                try {
                    await api<unknown>("DELETE", guildPath(this.guild) + "/problems/" + encodeURIComponent(problem.id));
                } catch (error) {
                    // Already gone counts as dismissed.
                    if (!(error instanceof ApiFailure && error.status === 404)) {
                        this.error = "Couldn't dismiss: " + errorMessage(error);
                    }
                }
                this.problems = this.problems.filter((p) => p.id !== problem.id);
                this.draw();
            });
        });
        const target = targetLabel(problem.target);
        const streamer = targetLabel(problem.streamer);
        const seen = problem.count > 1
            ? problem.count + " times, last " + formatTime(problem.lastSeen) + " (first " + formatTime(problem.time) + ")"
            : formatTime(problem.lastSeen);
        return h("li", null,
            h("div", null,
                h("strong", null, actionLabel(problem.action)),
                target !== "" ? " – " + target : "",
                streamer !== "" ? " for " + streamer : ""),
            h("div", null, problem.reason),
            h("div", { class: "hint" }, seen, problem.count > 1 ? [" ", h("span", { class: "badge" }, "×" + problem.count)] : null, " ", dismiss));
    }
}

function streamerName(streamer: StreamerResult): string {
    return streamer.twitchLogin ?? "Twitch account " + streamer.twitchId;
}

function group(title: string, level: DiagnoseResult["serverLevel"], checks: StreamerResult["checks"], open: boolean, channel?: string): HTMLElement {
    const details = h("details", { class: "diagnose-group", open },
        h("summary", null, levelBadge(level), " ", h("strong", null, title), channel !== undefined && channel !== "" ? h("span", { class: "muted" }, " → " + channel) : null),
        checks.length > 0 ? checkList(checks) : h("p", { class: "muted" }, "Nothing to check."));
    return details;
}

function counts(streamers: StreamerResult[]): Pick<DiagnoseResult, "streamersOk" | "streamersWithWarnings" | "streamersWithErrors" | "summary"> {
    let ok = 0, warnings = 0, errors = 0;
    for (const s of streamers) {
        if (s.level === "OK") ok++;
        else if (s.level === "WARNING") warnings++;
        else errors++;
    }
    const plural = (n: number, word: string): string => n + " " + word + (n === 1 ? "" : "s");
    return {
        streamersOk: ok,
        streamersWithWarnings: warnings,
        streamersWithErrors: errors,
        summary: plural(streamers.length, "streamer") + " checked: " + ok + " OK, " + plural(warnings, "warning") + ", " + plural(errors, "error"),
    };
}

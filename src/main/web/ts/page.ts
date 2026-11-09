// What the shell hands each page.

import type { GuildSummary, Me } from "./api.js";

export interface PageContext {
    me: Me;
    guild: GuildSummary;
    /**
     * Call after a change that can change what the user may open (e.g. removing their own streamer entry): reloads
     * `/me` and redraws.
     */
    accessChanged(): Promise<void>;
}

export interface Page {
    id: string;
    label: string;
    /** Whether this page is offered on the server. */
    available(guild: GuildSummary): boolean;
    render(root: HTMLElement, context: PageContext): Promise<void>;
}

/** API path of a server's routes. */
export function guildPath(guild: GuildSummary): string {
    return "/guilds/" + encodeURIComponent(guild.id);
}

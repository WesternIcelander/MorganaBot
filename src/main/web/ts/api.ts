// Types for the dashboard API's JSON (io.siggi.morganabot.dashboard.DashboardApi). Keep these in step with the Java.
// Every Discord/Twitch ID and every time (ms since the epoch) is sent as a string, because snowflakes don't fit in a
// JavaScript number.

/** An ID or a time in ms, as a string. */
export type Id = string;

/** `{"error", "code", "field"?}`, the body of every API error. */
export interface ErrorBody {
    error: string;
    code: string;
    field?: string;
}

export type AccessLevel = "admin" | "streamer";

export interface GuildSummary {
    id: Id;
    name: string;
    iconUrl: string | null;
    access: AccessLevel;
}

/** `GET /dashboard/api/me` */
export interface Me {
    userId: Id;
    name: string | null;
    superAdmin: boolean;
    discordAvailable: boolean;
    csrfToken: string;
    guilds: GuildSummary[];
}

export interface RoleRef {
    id: Id;
    name: string | null;
    exists: boolean;
}

export interface ChannelRef {
    id: Id;
    name: string | null;
    exists: boolean;
    botCanPost: boolean;
}

export interface MemberInfo {
    id: Id;
    username: string;
    displayName: string;
    avatarUrl: string | null;
    bot: boolean;
}

export type ChannelSource = "role" | "override" | "default";

/** A streamer entry, as `GET …/streamers` and `GET …/me/streamer` give it. */
export interface Streamer {
    twitchId: Id;
    twitchLogin: string | null;
    twitchDisplayName: string | null;
    twitchUrl: string | null;
    discordUserId: Id | null;
    discordUser: MemberInfo | null;
    channelOverride: ChannelRef | null;
    /** Where posts go: the override, or the server default. */
    channel: ChannelRef | null;
    channelSource: ChannelSource;
    template: string | null;
    templateSetBy: Id | null;
    live: boolean;
    liveSince: Id | null;
    lastStreamEnded: Id | null;
    selfService: boolean;
    addedBy: Id | null;
    paused: boolean;
    pausedSince: Id | null;
    channelOverridePinned: boolean;
}

export interface AllowedMentions {
    /** Admin: any mention. */
    all: boolean;
    /** `@everyone` and `@here`. */
    everyone: boolean;
    /** Mentioning other users (admins only). */
    users: boolean;
    roles: RoleRef[];
}

export interface SelfServiceStatus {
    eligible: boolean;
    role: RoleRef | null;
    channel: ChannelRef | null;
    serverDefault: boolean;
}

/** `GET …/me/streamer`, also returned by `PUT …/me/streamer/template` and `PUT …/me/streamer/twitch`. */
export interface OwnStreamer {
    twitchDisabledMessage: string | null;
    admin: boolean;
    streamer: Streamer | null;
    serverTemplate: string;
    canCustomizeMessage: boolean;
    allowedMentions: AllowedMentions;
    selfService: SelfServiceStatus;
    variables: string[];
    maxLength: number;
}

/** A role as `GET …/roles` gives it (highest first). */
export interface RoleInfo {
    id: Id;
    name: string;
    color: number;
    position: number;
    everyone: boolean;
    managed: boolean;
    mentionable: boolean;
    botCanAssign: boolean;
    botCantAssignReason: string | null;
}

export interface RolesResponse {
    roles: RoleInfo[];
}

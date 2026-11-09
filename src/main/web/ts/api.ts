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

// --- server admin pages ---

/** A text or announcement channel, as `GET …/channels` gives it (in Discord's order). */
export interface ChannelInfo {
    id: Id;
    name: string;
    type: "text" | "announcement";
    category: string | null;
    botCanPost: boolean;
    botCanMentionEveryone: boolean;
}

export interface ChannelsResponse {
    channels: ChannelInfo[];
}

export interface MembersResponse {
    members: MemberInfo[];
}

/** `GET …/settings` (and the answer to `PATCH …/settings`). */
export interface Settings {
    /** Only sent to super admin sessions. */
    enabledTwitchTracking?: boolean;
    twitchDisabledMessage: string | null;
    liveChannel: ChannelRef | null;
    liveRole: RoleRef | null;
    deleteOnOffline: boolean;
    /** Null: the default message is used. */
    liveMessageTemplate: string | null;
    defaultLiveMessageTemplate: string;
    notificationCooldownSeconds: number;
    variables: string[];
    maxLength: number;
}

/** `GET …/admin-roles` */
export interface AdminRoles {
    roles: RoleRef[];
    canEdit: boolean;
    readOnlyReason: string | null;
}

export interface MessageRule {
    role: RoleRef;
    allowEveryone: boolean;
    allowedMentionRoles: RoleRef[];
}

/** `GET …/message-rules` */
export interface MessageRules {
    twitchDisabledMessage: string | null;
    rules: MessageRule[];
}

export interface SelfServiceRoleEntry {
    role: RoleRef;
    /** Null: the server's default live channel. */
    channel: ChannelRef | null;
}

/** `GET …/self-service` (roles highest first, in Discord's role order). */
export interface SelfServiceConfig {
    twitchDisabledMessage: string | null;
    roles: SelfServiceRoleEntry[];
    removeAfterDays: number;
    maxRemoveAfterDays: number;
}

/** `GET …/streamers` */
export interface StreamersResponse {
    twitchDisabledMessage: string | null;
    twitchLookupFailed: boolean;
    streamers: Streamer[];
}

export type CheckLevel = "OK" | "WARNING" | "ERROR";

/** One Diagnose check. */
export interface Check {
    code: string;
    level: CheckLevel;
    message: string;
    fix: string | null;
}

export interface StreamerResult {
    twitchId: Id;
    twitchLogin: string | null;
    discordId: Id;
    channelId: Id;
    channel: string;
    level: CheckLevel;
    checks: Check[];
}

/** `POST …/diagnose` */
export interface DiagnoseResult {
    guildId: Id;
    time: Id;
    twitchEnabled: boolean;
    server: Check[];
    streamers: StreamerResult[];
    serverLevel: CheckLevel;
    streamersOk: number;
    streamersWithWarnings: number;
    streamersWithErrors: number;
    summary: string;
}

export interface ProblemTarget {
    kind: "channel" | "role" | "member" | "streamer" | string;
    id: Id;
    name: string | null;
}

/** A recorded failure. */
export interface Problem {
    id: string;
    time: Id;
    lastSeen: Id;
    count: number;
    action: string;
    target: ProblemTarget | null;
    streamer: ProblemTarget | null;
    reason: string;
}

/** `GET …/problems` */
export interface ProblemsResponse {
    problems: Problem[];
    diagnose: DiagnoseResult | null;
    twitchTrouble: boolean;
    twitchMessage: string | null;
}

export type ActorSource = "command" | "dashboard" | "superAdmin" | "selfService" | "system";

export interface AuditActor {
    userId: Id | null;
    name: string | null;
    source: ActorSource;
    command: string | null;
}

export interface AuditValue {
    kind: "channel" | "role" | "user" | "twitch" | "text" | "boolean" | "number" | string;
    id?: Id | null;
    name?: string | null;
    value?: string | null;
}

/** `old`/`new` as AuditEntry.Change serializes them; null (or missing) means unset. */
export interface AuditChange {
    field: string;
    old: AuditValue | null;
    new: AuditValue | null;
}

export interface AuditEntry {
    time: Id;
    type: string;
    actor: AuditActor | null;
    visibility: "server" | "superAdmin" | string;
    streamer: AuditValue | null;
    subject: AuditValue | null;
    changes: AuditChange[];
    note: string | null;
    /** Facts about the event (e.g. a blocked server's owner); null or missing for most entries. */
    facts?: AuditFact[] | null;
    /** How many times it happened, for combined entries (blocked join attempts); null or missing means once. */
    count?: number | null;
    /** When a combined entry first happened (`time` is the latest). */
    firstTime?: Id | null;
}

/** AuditEntry.Fact */
export interface AuditFact {
    field: string;
    value: AuditValue | null;
}

/** `GET …/audit` */
export interface AuditPage {
    entries: AuditEntry[];
    nextBefore: Id | null;
}

// --- super admin (io.siggi.morganabot.dashboard.AdminApi) ---

/** One entry of `GET /admin/audit`, with the server it belongs to. */
export interface AdminAuditItem {
    guildId: Id;
    /** The server's name (Discord, the block, or its newest entry), or null if unknown. */
    guildName: string | null;
    entry: AuditEntry;
}

/** A server with an audit file, for the filter. */
export interface AdminAuditServer {
    id: Id;
    name: string | null;
    /** Whether the bot is in it; null while Discord is unavailable. */
    inServer: boolean | null;
}

/** `GET /admin/audit?guild=&type=&before=&limit=` */
export interface AdminAuditPage {
    entries: AdminAuditItem[];
    nextBefore: Id | null;
    servers: AdminAuditServer[];
}

/** One server in `GET /admin/guilds`. */
export interface AdminGuild {
    id: Id;
    name: string;
    iconUrl: string | null;
    memberCount: number;
    twitchEnabled: boolean;
    streamers: number;
    /** Recorded failures (the server's Problems list). */
    problems: number;
    /** Set when the server's settings file couldn't be read. */
    error?: string;
}

/** `GET /admin/guilds` */
export interface AdminGuilds {
    discordAvailable: boolean;
    guilds: AdminGuild[];
}

export type HealthState = "UNKNOWN" | "GREEN" | "AMBER" | "RED";

/** A labelled live value on a status card. `time` values are ms since the epoch. */
export interface HealthDetail {
    key: string;
    label: string;
    kind: "time" | "number" | "text";
    value: string;
}

export interface HealthCard {
    id: string;
    label: string;
    state: HealthState;
    since: Id;
    message: string;
    lastError: string | null;
    lastErrorTime: Id | null;
    details: HealthDetail[];
}

/** `GET /admin/system` */
export interface SystemStatus {
    anyRed: boolean;
    discordAvailable: boolean;
    components: HealthCard[];
    startedAt: Id | null;
    uptimeSeconds: number | null;
    version: string | null;
    buildTime: string | null;
    /** Null while Discord isn't connected. */
    servers: number | null;
    streamersTracked: number | null;
    /** Every component that can appear in the log, with its label. */
    componentLabels: Record<string, string>;
}

export type LogSeverity = "WARNING" | "ERROR";

export interface SystemLogEntry {
    id: string;
    component: string;
    severity: LogSeverity;
    message: string;
    /** Stack trace of the latest occurrence, or null. */
    detail: string | null;
    /** First seen. */
    time: Id;
    lastSeen: Id;
    count: number;
}

/** `GET /admin/system/log` */
export interface SystemLogPage {
    entries: SystemLogEntry[];
    /** Pass as `before` for the next page; null on the last page. */
    nextBefore: Id | null;
    total: number;
}

/** `POST /admin/guilds/{id}/leave` */
export interface LeaveResult {
    left: boolean;
    /** The server's name when the bot left. */
    name: string;
    blocked: boolean;
}

/** One server in `GET /admin/blocked`. */
export interface BlockedServer {
    id: Id;
    /** The server's name when it was blocked, or null if unknown (e.g. blocked by hand-editing its file). */
    name: string | null;
    blockedAt: Id | null;
    blockedBy: Id | null;
    blockedByName: string | null;
    reason: string | null;
    /** The bot is still in it (it should have left); null when Discord isn't connected. */
    inServer: boolean | null;
}

/** `GET /admin/blocked` */
export interface BlockedServers {
    discordAvailable: boolean;
    blocked: BlockedServer[];
}

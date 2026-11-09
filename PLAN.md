# Plan

## Done

- [x] Remove unimplemented `/letmein` (Minecraft whitelist) command and `MinecraftHandler` — `35b85f0`

## Web dashboard

Goal: a web UI that can read and change everything about a server. Anything a command can change, the web UI can change too.

**Nobody should need to edit server files by hand.** Every per-server setting must be changeable from the web UI. That includes settings no command can change today: the notification cooldown, a streamer's linked Discord user, and (super admin only) the Twitch on/off switch. If a per-server setting is added later, it gets a place in the UI too.

**`config.json` stays file-only, by the user's choice.** It holds the public URL, the port, the Discord token, the Twitch credentials and `superAdmins`. These are set at install and rarely change, so there's no UI for them. User timezones stay per-user via `/timezone`.

### What the UI covers

Server settings:
- Live notification channel (`/setlivechannel`)
- Live streamer role (`/setstreamerrole`)
- Delete notifications on offline (`/setdeleteonoffline`)
- Server-wide live message template (`/setlivemessage`), with the variable list and a preview
- Notification cooldown (`streamNotificationCooldown`; no command for it today)
- `enabledTwitchTracking` is **super admin only** (see below). Normal sessions never see or send it. If it's off, they see the same "not enabled for this server, contact …" message the commands give, and the Twitch sections are disabled.

Streamers (`/streamerlist`, `/addstreamer`, `/deletestreamer`):
- List: Twitch name and link, linked Discord user, channel override, template override, live now, last stream
- Add by Twitch name, with optional Discord user and channel override
- Delete
- Edit the channel override (`/setchanneloverride`), template override (`/setlivemessage discord-name:`), and linked Discord user (no command for that today)

### Who can use the dashboard

Access is decided by the bot on **every request**, from the member's current roles and permissions. It does **not** depend on who can run `/dashboard`. `/dashboard` only proves who you are.

- **Server admin (full dashboard)** is anyone who:
  - has Discord's **Administrator** permission in that server, or is the server owner, or
  - has one of the server's configured **MorganaBot admin roles** (`adminRoles`).
- **Streamer (own entry only)** is a non-admin who has a streamer entry in that server linked to their Discord account. They see a small page for **their own** entry: their Twitch channel, its status (live, paused, last stream), which channel it posts in, and, if one of their roles allows it, their **own live message** editor. They can also set or remove their own Twitch channel there, through the same self-service rules as `/settwitchchannel` and `/removetwitchchannel`, since every command action has a web equivalent.
- **Everyone else** gets "You don't have access to this server's dashboard".
- **Super admin** is unchanged: full access everywhere.
- **Access changes take effect straight away,** because the check runs on every request. Removing an admin role cuts that person off immediately; they don't keep access until their session expires.
- **Who can edit the admin role list:** only members with Discord's **Administrator** permission (the server owner always has it), plus super admins, who can do anything (user's decision). Someone who's an admin only through a MorganaBot admin role can't change the list, so they can't add more admins (e.g. `@everyone`) or remove other admins. The setting is in the dashboard and in commands (`/addadminrole`, `/removeadminrole`, which are Administrator-only by default and also checked by the bot).
- **The existing admin commands keep their Discord-side defaults** (admin only, adjustable under Integrations), so production servers behave exactly as before. People who are admins only through a MorganaBot admin role use the dashboard, unless the server grants them the commands under Integrations.

### Message customization permissions

Admins choose which non-admin streamers can write their own live message, and which pings those messages may contain.

- **Config (per server):** a list of **message customization rules**, one per role: `{roleId, allowEveryone, allowedMentionRoles: [roleId…]}`.
  - A streamer can edit their own message if they hold **any** rule's role.
  - What they can ping is the **combination of all their matching rules**: if any rule allows `@everyone`/`@here`, they can use it, and the roles they can mention are those of all their matching rules put together. Unlike the self-service channel, there's no "highest role wins" here, because permissions add up naturally.
  - `allowEveryone` covers both `@everyone` and `@here`.
- **Admins** (and super admins) can write any message, for any streamer and for the server-wide template, with any mentions. Mentions still only work if the bot itself has the Discord permission.
- **Checked when saving (user's rule: existing mentions can be kept):** when a non-admin saves their template, each `@everyone`, `@here` and `<@&role>` in the new text must be either:
  - allowed by their current rules, or
  - **already in their previously saved message.**
  
  So they can always edit around a mention they were given, e.g. one an admin put in, or one from a rule they've since lost. They can't *add* one they aren't allowed. If they remove a kept mention and save, it's gone: adding it back later needs a rule that allows it. Mentions are compared by identity (`@everyone`, `@here` and each role ID count separately), not by how many times they appear. The error names exactly which mentions aren't allowed.
  - "Previously saved message" means their own per-streamer template. If they don't have one yet, it's the server-wide template that's currently used for their posts, since the editor starts from that text. Confirmed by the user.
- **Length limit:** templates have a maximum length so the filled-in message fits Discord's 2000-character limit.
- **Mention permissions are only checked when saving, not when posting** (user's decision). Whatever passed the save check pings as written: every mention in the saved template text pings, whoever wrote it, even if the member later loses the rule. Each streamer template stores `templateSetBy` (who last saved it), only so the dashboard and audit can show it.
- **Template text is never altered.** A mention that isn't allowed causes the save to be **rejected**, never silently defused or stripped (user's rule).
- **Twitch data can never ping.** Two layers:
  1. **Defuse Twitch values only.** `${stream_name}`, `${game_name}` and `${streamer_name}` get a zero-width space after every `@` (`@` → `@\u200B`) before they're inserted. `@everyone`, `@here`, `<@&id>` and `<@id>` in a stream title then show as plain text and can't ping.
  2. **`allowed_mentions` = the mentions written in the template text**, found before Twitch values are filled in. Role and user mentions can only ping if the template contains them.
  
  Both layers are needed. Discord's `@everyone` switch covers the whole message, so a template that legitimately contains `@everyone` would otherwise also let an `@everyone` in the stream title ping. This fixes the existing hole where a stream title containing `@everyone` pings everyone if the bot has Mention Everyone.
- **Pinging individual users** (`<@id>`): only admins can add them (user's decision). It's also checked at save time: a non-admin's template can't add user mentions, so they can't use it for harassment pings. As with other mentions, ones already in the previously saved message can be kept. `${discord_name}` (the streamer themself) is always allowed. It isn't configurable for now.
- **Existing per-streamer templates on production** ping exactly as before, since posting doesn't check permissions. The only change is that mentions coming from Twitch data no longer ping.
- If a streamer loses every customization rule, their template is kept and still used, mentions included. They just can't edit it any more; admins still can.

### Self-service streamer adding

Streamers can add themselves with `/settwitchchannel twitch-name:<name>`, as long as they have one of the server's configured self-service roles.

- **Config (per server, set in the dashboard):** a list of self-service roles. Each one has an optional channel override, where empty means "use the server's default live channel".
- **Which role wins:** of the member's roles that are in the list, the one **highest in Discord's role order** (role position, as arranged in Server Settings → Roles) decides the channel. That role wins **even if its channel override is empty**: the member then gets no override, and lower roles' overrides are ignored. The order of the list in the bot's config doesn't matter.
- **Command rules:**
  - `/settwitchchannel` is usable by everyone by default, and the bot checks the role itself. Admins can still restrict who sees it under Integrations. It's guild-only.
  - **No matching role, or Twitch off:** the reply explains why, and nothing changes.
  - **Unknown Twitch name:** the reply says so.
  - **Twitch account already added by someone else in this server:** refused, with "ask an admin".
  - **Member already has an entry, whether added by an admin or by themselves:** their Twitch account is replaced, and their channel override is reset to the one their winning role gives. Their personal message template is kept.
  - New entries are marked `selfService: true` and `addedBy`, so the dashboard can show a "self-added" badge.
  - The reply says which channel their live posts will go to.
- **`/removetwitchchannel`** lets a member remove their own entry, even if they no longer have the role.
- **Changes are audited,** with source "self-service".
- **Diagnose (step 6) covers the rules too:** every self-service role must still exist, and every override channel must exist with the bot able to post there.
- **Live role tracking (self-added streamers only).** The bot has all privileged intents, so it follows role changes as they happen. Admin-added streamers are never affected.
  - **Losing eligibility pauses posts.** That means losing the last self-service role, leaving the server, or an admin removing that role from the self-service list. The bot records `selfServiceLostAt` and stops posting for them (no live post, no live role). It's shown as "Paused: no self-service role since <date>" in the streamer table, with an audit entry from "system".
  - **Getting a self-service role back resumes posts,** clearing `selfServiceLostAt`, also with an audit entry.
  - **Removed after a configurable time.** Once the role has been missing for `selfServiceRemoveAfterDays` days, the entry is deleted. The default is 7; 0 means never. It's per server, set in the dashboard or by command. Removal is checked hourly by the watcher loop and at startup. It's audited as a removal by "system" with the reason, and `triggerUpdate()` drops the Twitch subscription if no other server tracks that streamer.
  - **The channel follows the role.** When the member's winning role changes, their channel override is recalculated automatically. If an admin has set a channel override for a self-added streamer by hand, it's pinned (`channelOverridePinned`) and no longer follows the role. Confirmed by the user. Setting the override to "server default" by hand also pins it, to the default.
    - **Admins can unpin it.** That clears the pin and **immediately recalculates the channel from the member's current roles** (through `reconcileSelfService`), with an audit entry. Admins can unpin in two ways:
      - the dashboard's "Go back to role-based" button
      - a new `role-based: True` option on `/setchanneloverride`, which only works for self-added streamers and can't be combined with `channel`
    - Re-running `/settwitchchannel` also clears the pin.
  - **Catching up after downtime:** role changes made while the bot was offline are caught by a full check of all self-added streamers at startup, and whenever the self-service role list is edited. Repeated events, and a startup check that finds nothing new, don't add duplicate audit entries.
- **No proof of Twitch ownership** (user's decision). If it's abused, the server's admins deal with the member directly.
- **Rate limit:** one `/settwitchchannel` per member per minute, because each call looks up the Twitch account.
- `/settwitchchannel` is visible to everyone by default, and the bot does the role check itself.

### Server audit log

Every server gets an audit log of recent changes, visible to anyone who can open that server's dashboard. It shows who changed what and when.

- **What it records:** every change to that server's settings and streamers: live channel, live role, delete-on-offline, templates, cooldown, adding and removing streamers, channel overrides, linked Discord users, and the Twitch on/off switch. Each entry has:
  - the time
  - who made the change: Discord user ID, plus their name at the time
  - where it came from: a command (`/setlivechannel` etc.), the dashboard, or the dashboard as a super admin
  - what changed, as old → new. Channels, roles and users are shown by ID plus name at the time, so the entry still makes sense if one is later renamed or deleted.
- **Super admin changes are shown to the server too,** labelled "Bot admin" with the name, so server admins can see when the bot owner changed something for them. The Twitch switch shows up as "Twitch announcements enabled/disabled by bot admin".
- **Super-admin-only entries** (removal, blocking, blocked join attempts; see Super admin) are stored in the same file but marked `visibility: superAdmin`, and the server API never returns them to normal sessions.
- **Storage:** `data/servers/<guildId>-audit.jsonl`, one JSON object per line, append-only, written under the per-server lock. It's a new file, so nothing existing changes, and a missing file means no entries. Each server keeps its newest 1000 entries.
- **UI:** an Audit tab on each server's dashboard, newest first, filterable by setting and by who made the change.

### Super admin

Not a separate UI: it's the same dashboard, with extra powers when the session belongs to a super admin. It can view and change settings on **any** server the bot is in, e.g. when a server owner asks for help setting something up. It is also the only place `enabledTwitchTracking` can be turned on or off, apart from editing the JSON by hand.

- **Who:** set in `config.json` as a list of Discord user snowflakes:
  ```json
  "superAdmins": ["260595748465278976"]
  ```
  IDs are written as strings, because snowflakes are too big for JSON numbers to hold exactly in many tools (JavaScript included). Gson reads them into a `List<Long>`, and plain numbers are accepted too. A missing or empty list means there are no super admins. The list is only read at startup, so changing it needs a restart. The hard-coded `<@260595748465278976>` in the "not enabled" messages is replaced with the first super admin from config. If the list is empty, the message leaves out the contact part.
- **Login:** the same `/dashboard` command, from a server or a DM. A super admin's session is a super admin session regardless of where they ran it.
- **Server switcher:** a sidebar on the left of the normal UI, shown only for super admin sessions. It lists every server the bot is in, with name, icon, ID, member count, Twitch on/off, number of streamers, and number of current problems. It's searchable by name or ID, and filterable by "Twitch enabled" and "has problems". Picking a server loads it into the same settings view.
- **Server view:** the same page a server admin sees, plus the `enabledTwitchTracking` toggle and a small "Super admin" badge in the header so it's clear which mode you're in.
- **Remove the bot from a server**, e.g. when a server breaks the acceptable use rules. It's a button on the server view, with a confirmation that requires typing the server's name. There's an optional reason note and a **"Block from re-adding"** checkbox, which is on by default. Without a block, anyone with an invite link could just add the bot back.
  - The block is stored in that server's own file (`blocked`, `blockedAt`, `blockedBy`, `blockedReason`, all new optional fields, so it's additive). If the bot joins a blocked server (`GuildJoinEvent`), or is still in one at startup, it leaves immediately and logs it.
  - The server's settings file is kept after removal, so unblocking and re-inviting restores the old setup.
  - The bot only subscribes to Twitch for servers it's currently in, so removed servers drop out on their own. `triggerUpdate()` is called after leaving so it happens straight away.
  - The super admin UI has a **Blocked servers** list (name at time of block, ID, when, by whom, reason) with an Unblock button. Unblocking doesn't re-add the bot; the server has to invite it again.
- **Bot-wide audit log, visible to super admins only.** It uses the same per-server audit files as the server audit log (see Server audit log), plus super-admin-only entries:
  - removing the bot from a server, and blocking and unblocking servers, with the reason
  - **attempted joins from blocked servers**, and blocked servers left at startup. Each entry records the server's name at the time, its ID, its owner's user ID, and its member count. If the bot happens to have View Audit Log permission, it also records who added the bot, by checking the server's audit log for the bot-add entry before leaving. Otherwise that's shown as "unknown".
  - **Join-attempt floods:** each attempt is still recorded, but repeated attempts from the same blocked server within an hour are combined into one entry with a count and first/last times.
  - **UI:** an "Audit log" page in the super admin sidebar that merges every server's entries (including servers the bot has left), newest first, filterable by server and event type. Super admins also see the super-admin-only entries in each server's Audit tab.
- **Safety:** super admin sessions expire after 8 hours instead of 24. The server checks the super admin flag on every request; hiding the toggle in the UI isn't the security. Every change made from a super admin session goes into the audit log (see Server audit log) and to stdout, with the user, server, setting, and old → new value.

### System health (super admin only)

A **System** page in the super admin panel shows problems with the bot as a whole: things that affect every server, as opposed to one server's settings.

**What it watches:**
- **Discord**
  - connection state (connected, reconnecting, disconnected, since when) and gateway ping
  - **invalid bot token**: close code `AUTHENTICATION_FAILED`, or `InvalidTokenException` at startup
  - **intents not enabled** in the developer portal: `DISALLOWED_INTENTS`. Step 7 needs the members intent, so this matters.
  - rate limiting
  - long disconnects
- **Twitch API**
  - **client ID/secret rejected** when fetching the app token (400/401/403 from `id.twitch.tv`)
  - **token rejected** on API calls (401)
  - **requests refused**: 403, 429 rate limiting (with `Ratelimit-Reset`), and 5xx
  - network errors and timeouts
  - token expiry time, last successful call, last error
- **Twitch EventSub**
  - subscription count, with any subscription Twitch has disabled and its status reason (`webhook_callback_verification_failed`, `notification_failures_exceeded`, `authorization_revoked`, `user_removed`, …). The hourly sync already fetches these statuses but currently just deletes them silently.
  - subscription create/delete failures, including Twitch's subscription cost limit
  - time of the last notification received
  - **signature check failures**: a wrong `notificationSecret`, or someone probing the endpoint
- **Storage:** failed saves. `Data.save()` currently swallows every exception and ignores a failed `renameTo`, so a full disk or a permissions problem silently loses data.
- **Unexpected errors:** every place that currently swallows an exception or only calls `printStackTrace()` (the `TODO: Alert someone` spots in `TwitchStreamerWatcher`, the command handlers, `Listener`), plus a default uncaught-exception handler.
- **General:** uptime, version (build time), number of servers, number of streamers tracked.

**How it's shown:**
- **Status cards** for Discord, Twitch API, EventSub and Storage: green, amber or red, with "since <time>" and the latest error message.
- **System log:** errors and warnings newest first, each with the component, severity, message, count, first/last seen, and an expandable stack trace. Repeats are combined (same component and message → count++), so a Twitch outage doesn't produce thousands of rows. Entries can be dismissed. Filterable by component and severity.
- **A red dot on "System"** in the super admin sidebar whenever any card is red, so it's noticed from any page.

**Alerting:** when a component turns **red**, every super admin gets a Discord DM, and again when it **recovers**. Only state changes are sent, at most one DM per component per 15 minutes. If Discord itself is the broken part, the alert can only go to stdout.

**When Discord is down, the dashboard must still work,** because that's exactly when the System page is needed:
- **Startup no longer dies on a Discord failure.**
  - The web server and Twitch watcher start anyway, and the bot runs in a "Discord unavailable" state.
  - Temporary failures (network, Discord outage) retry with backoff.
  - An invalid token or disallowed intents can only be fixed by editing `config.json` or the developer portal and restarting, so those aren't retried. They're shown as red with a "fix and restart" message.
  - Today an invalid token throws out of `start()`, leaving a process with a web server and nothing else running.
- **Recovery login:** `/dashboard` doesn't work without Discord. So when Discord fails at startup, or the connection is lost because the token is rejected, the bot prints a **single-use super admin login link** to stdout, valid for 15 minutes. While Discord stays down, it prints a fresh one every hour. Anyone who can read the console can already read `config.json`, so this doesn't widen access.
- **Super admin sessions keep working without Discord.** The super admin flag is stored in the session, and the System page doesn't need guild data. Server pages show "Discord unavailable" instead of failing.

**Storage:** the system log is bot-wide, so it's one global file, `data/system-log.jsonl` (append-only, newest 1000 entries). It survives restarts, so you can see what happened before a crash. The status cards are worked out live and not saved. This replaces the planned "bot-wide Twitch status record" in step 4.

### Data compatibility (production is live on the current format)

Every file under `data/` must keep loading after a deploy, with nothing lost and no manual steps.

- **Current plan doesn't change any existing format.**
  - Step 1 only changes how files are cached and locked.
  - Step 3 adds new `data/servers/<id>-audit.jsonl` files. A missing file means no entries.
  - Step 4 adds new `data/servers/<id>-problems.json` files. A missing file means no problems.
  - Step 5 adds a new global `data/system-log.jsonl`. A missing file means an empty log.
  - Step 7 adds optional `selfServiceRoles` and `selfServiceRemoveAfterDays` to server files, and optional `selfService`, `addedBy`, `selfServiceLostAt` and `channelOverridePinned` to streamers. Missing values mean: the feature is off, the expiry is 7 days, the streamer was added by an admin, and they're not paused or pinned.
  - Step 8 adds optional `adminRoles` and `messageCustomizationRules` to server files, and `templateSetBy` to streamers. Missing means no extra admin roles and no non-admin customization, so nothing changes on production.
  - Step 9 adds an optional `superAdmins` key to `config.json`. If it's missing, there are no super admins.
  - Sessions and login tokens live in memory only.
  - `ServerInfo`, `Streamer`, `UserData`, `AuthCache` and the existing `config.json` keys stay exactly as they are.
- **Per-server data stays in per-server files** (`data/servers/<guildId>.json`, `-problems.json`, `-audit.jsonl`). Nothing per-server is ever bundled into a shared file. Only bot-wide state (`config.json`, `auth-cache.json`, `system-log.jsonl`) is global.
- **Additive changes are preferred:** new fields with a safe default when missing. Gson leaves a missing field at its initializer, because these classes have no-arg constructors. Gson also ignores unknown fields, so a rollback to the old jar keeps working with new files.
- **If a step ever needs to rename, move, restructure or change the meaning of stored data:**
  - Add `dataVersion` to that file type, where a missing value means version 0, today's format.
  - Migrate when the file is loaded.
  - Before the first migrated save, write a backup copy (`<name>.json.v<old>.bak`).
  - Add a migration test using a copy of today's format.
  - Write up the change here before doing it.
- **Hand-editing must keep working until the UI replaces it.** Today `enabledTwitchTracking` is turned on by editing the server JSON on production while the bot runs. That works because every access reads the file fresh. Step 1's cache would silently ignore those edits, and then overwrite them on the next save. So the store must notice when the file's modified time changes on disk and reload it before using or saving it. After step 14 hand-editing isn't needed any more, but the reload stays as a cheap safety net.
- **Deploy note:** add `superAdmins` to the production `config.json` before or with the deploy that includes step 9. Otherwise there's no super admin, and the "not enabled, contact …" message loses its contact.
- **Before deploying, test against real data:** run the new build on a copy of the production `data/` directory, not just the dev data. Check that every server, streamer, template and user timezone loads and shows up unchanged, and that saving doesn't drop any fields.

### Design decisions

- **Login flow (user's decision):** `/dashboard` replies (ephemeral) with a **single-use token valid for 30 seconds**, tied to that **user**. The link is kept off screen as much as possible, in case the user is screen sharing:
  - **The URL isn't in the message text.** The reply is "Here's your dashboard login" with a Discord **link button** labelled "Open dashboard". The URL behind it (`{apiEndpoint}/dashboard/login?token=…`) is only shown if Discord asks "Leaving Discord?" before opening it. Ticking "Trust this domain" once skips that dialog afterwards.
  - **The button disappears once it's used or expired.** When the token is redeemed, or after 30 seconds, the bot edits its ephemeral reply (through the interaction hook) to "Logged in ✓" or "Link expired, run /dashboard again", with no button.
  - **The token leaves the address bar straight away.** `GET /dashboard/login?token=` uses up the token and immediately redirects to `/dashboard/` (or the server it was run in), with `Referrer-Policy: no-referrer`, so the token doesn't stay in the address bar or get sent anywhere.

  A session identifies the Discord user. The servers they can open are the ones the bot shares with them where they currently have admin or streamer access (see Who can use the dashboard). The console recovery link (System health) is separate and valid for 15 minutes, since it's only visible to whoever can read the console.
- **`/dashboard` is open to everyone** (default enabled) and works in servers and in DMs. It only proves identity, so making it public grants nothing by itself. Access comes from the per-request check above. This replaces the earlier "admin-only by default" plan, which existed only because the command used to be the access check. A non-admin with no streamer entry who runs it is told they have no access, rather than getting a link to an empty page.
- **Sessions: SiggiHTTP's built-in sessions** (user's decision): `Sessions.create(24h)` in memory, passed to `HTTPServerBuilder.setSessions`, so a bot restart logs everyone out.
  - The session stores the user ID, the super admin flag, the CSRF token, and the login time. Access itself isn't stored.
  - Login calls `request.resetSession()`, so a session ID set before login can't be reused (no session fixation).
  - Super admin sessions are cut off after 8 hours, by checking the stored login time on each request.
  - SiggiHTTP's cookie is `HttpOnly`, plus `Secure` when the proxy sends `X-Forwarded-Proto: https`. It has no `SameSite`, so the CSRF check below is the real protection against cross-site requests and is required on every write.
- **CSRF:** any request that changes something must be JSON and carry a custom header (`X-Morgana-CSRF` holding a per-session token).
- **Frontend: HTML, CSS and TypeScript** (user's decision).
  - **Sources:** `src/main/web/`, containing `index.html`, CSS, `ts/*.ts`, `package.json` and `tsconfig.json`.
  - **Dependencies:** `typescript` is the only npm dependency, as a dev dependency. There's no framework and no bundler.
  - **Compiling:** `tsc` turns each `.ts` file into an ES module, loaded by the page with `<script type="module">`.
  - **Built by Maven:** `frontend-maven-plugin` downloads a pinned Node into `target/`, then runs `npm ci` and `npm run build`. The output goes to `target/classes/web/`, so it ends up in the jar and `mvn package` stays the only build command. The first build needs network access to download Node and npm packages.
  - **Strict typing:** `tsconfig.json` uses `strict: true`, and the API response types live in one `api.ts` that mirrors the Java JSON.
  - **Safety:** everything displayed from users or Twitch is inserted as text (`textContent`), never as HTML.
- **Choosing users, channels and roles:** channels and roles come from the JDA cache. To pick a Discord user, the UI searches the member cache by name (step 7 turns on the members intent and caches all members). Pasting a user ID also works.

### Steps (each finished step = commit on main, then tick here with the hash)

- [x] 0. **Test setup.** Add JUnit 5 (`junit-jupiter`) with `test` scope and `maven-surefire-plugin` to `pom.xml`, create `src/test/java/io/siggi/morganabot/`, and add a first test. That test loads a fixture copy of **today's production `ServerInfo` format** (`src/test/resources/fixtures/server-v0.json`, made up by hand, with streamers, templates and overrides), checks every field, saves, and checks nothing was dropped. `mvn package` must run the tests. Later steps extend this fixture test whenever they add fields. — `5f71aed`
- [x] 1. **Server data store with locking.** Commands, Twitch notifications and the web UI all load, change and save the same `data/servers/<id>.json` with no locking. The web UI makes it more likely that one save overwrites another. Notification handling already has this race. Its async callback saves `lastLiveNotificationId` on the `ServerInfo` it loaded *before* sending, so anything changed in between gets reverted, e.g. a streamer deleted while their live post was sending comes back. That callback must go through `modify()` and find the streamer again by Twitch ID (and do nothing if they were removed). Add a `ServerInfoStore` that keeps one cached `ServerInfo` per guild, with a per-guild lock and a `modify(guildId, fn)` method that saves after the change. Move `bot.getServerInfo` and every caller onto it. The cache reloads a file when its modified time on disk changes, so hand edits made while the bot is running are picked up and not overwritten (see Data compatibility). — `ca50202`
- [x] 2. **Move settings logic out of the command handler.** Create a `ServerSettings` service that does all the validation and changes for each setting, and calls `triggerUpdate` when needed. The command handlers then just parse options and pass them to it. While doing this, fix these known bugs:
  - the `getStreamer` wrong-match bug
  - the `/addstreamer` NullPointerException
  - change `/setchanneloverride`'s `channel` option to `OptionType.CHANNEL` and drop the `<#…>` stripping in `getChannelId`. Existing production overrides are stored as IDs, so they're unaffected.
  - make `/streamerlist` links use the Twitch login, not the display name

  — `23b908e`
- [ ] 3. **Server audit log (core).** Add `AuditLog`, which appends entries to `data/servers/<guildId>-audit.jsonl` under the per-server lock and trims each file to 1000 entries. Entries have a time, type, actor (user ID and name, or "system"), source (command, dashboard, super admin), details (old → new, with IDs plus names), and visibility (`server` or `superAdmin`). Every change in `ServerSettings` (step 2) records an entry, so commands are audited from now on, and the dashboard is automatically audited once it exists. The step 2 service methods take an "actor" parameter so they know who made the change and from where.
- [ ] 4. **Record failed actions per server.** Today every Discord action is fired with `.queue()` and no failure handler, so failures vanish. Add a `ServerProblems` record for each guild, saved to `data/servers/<id>-problems.json` and kept to the newest 100 entries. Each entry has a time, the action, the target (channel, role, member or streamer), and a readable reason, e.g. "Missing permission Send Messages in #live", "Unknown channel (deleted?)", "Role @Live is above the bot's highest role". Add failure callbacks that write an entry to every action in `handleLiveNotification` (send, delete, add role, remove role), and map common JDA errors (`InsufficientPermissionException`, `HierarchyException`, `ErrorResponseException` with `MISSING_PERMISSIONS` / `UNKNOWN_CHANNEL` / `UNKNOWN_MESSAGE` / `UNKNOWN_MEMBER`) to friendly text. Also log the cases that are skipped without anything being attempted: the configured channel is missing or isn't a text channel, the live role was deleted, or no channel is set. Repeated identical failures are combined into one entry with a count and a last-seen time, so a streamer going live 20 times doesn't fill the list. Problems with Twitch itself (token refresh, subscription sync, failed `getChannelInformation`) affect every server, so they're left out of this step; step 5 sends them to the system log. Entries can be dismissed: one at a time, or all at once. Writes to the problem log are locked per server, like the server store. Fix these known bugs in `handleLiveNotification` first, so they don't produce failure entries:
  - accept any `StandardGuildMessageChannel`, so announcement channels work
  - don't try to add or remove the live role for streamers with no linked Discord user (`discordId == 0`)
- [ ] 5. **System health monitoring (backend).**
  - **`SystemHealth`:** live status per component (Discord, Twitch API, EventSub, Storage), plus `SystemLog`, which appends to `data/system-log.jsonl` (combining repeats, newest 1000, thread-safe).
  - **Hook every error path into it:**
    - `TwitchStreamerWatcher`'s token fetch, API calls (including rate-limit headers), subscription sync, and the swallowed exception in `handleNotification`
    - EventSub statuses from the hourly sync (logged with their reason before deleting them)
    - signature failures
    - `Data.save()` failures, which also become exceptions so callers can react
    - command handler exceptions
    - a default uncaught-exception handler
  - **Discord:** a JDA `StatusChangeEvent`/`SessionDisconnectEvent`/`ShutdownEvent` listener that maps close codes (`AUTHENTICATION_FAILED`, `DISALLOWED_INTENTS`, `RATE_LIMITED`, …) to status.
  - **Startup:**
    - Start the web server and Twitch watcher before Discord, and don't let a Discord failure abort startup.
    - Retry temporary failures with backoff, but not an invalid token or disallowed intents.
    - The Twitch watcher's guild loops wait until Discord is ready.
  - **Known bugs fixed here** (step 5 already reworks the watcher's token and error handling):
    - **Token refresh:** keep the old token until the new one is in place, refresh under a lock from any thread (not only the loop thread), refresh early when `twitchAppAccessTokenExpiry` is near, and make the `AuthCache` access thread-safe. This also fixes the expired-token bug.
    - **Online/offline ordering:** handle each streamer's notifications one at a time (a per-Twitch-ID lock), run the cooldown check inside it, and have offline wait for an in-flight send so it can delete the new post.
    - **`triggerUpdate()` after `stop()`:** keep the thread reference, and make `triggerUpdate()` do nothing once stopped.
  - **Alerting (confirmed by the user):** DM super admins when a component turns red or recovers, rate-limited, with stdout as the fallback.
  - **Recovery login:** print a single-use super admin login link to stdout (15 minutes) when Discord fails, and hourly while it stays down. This needs the login token store from step 9, so the stdout link is wired up in that step.
- [ ] 6. **Diagnose: check every streamer's setup without waiting for a stream.** Add `Diagnose.run(guild)`, which works out what *would* happen on each streamer's next go-live and reports anything that would fail. It doesn't post or change anything, and doesn't store results. It runs:
  - on demand from the **Diagnose** button
  - automatically when the dashboard loads
  - after every save, so a newly picked channel the bot can't post in gets flagged straight away
  
  **Server-wide checks:**
  - **Default live channel:** set, exists, and is a type the bot can post to.
  - **Live role:**
    - exists
    - the bot has Manage Roles
    - the role is below the bot's highest role
  - *(Added by step 7: each self-service role still exists, and each override channel passes the channel checks below.)*
  - *(Added by step 8: the roles that message permission rules reference still exist.)*
  - **Twitch on/off:** if Twitch tracking is off, say so first, since nothing will post.

  **Per streamer:**
  - **Which channel they'd post in:** their override, or the server default if it's empty. The result says which one it is, e.g. "#live (server default)" or "#vip-live (from role @VIP)".
  - **Channel checks:** the channel exists, and is a text or announcement channel. Announcement channels are currently skipped silently; see Known bugs. The bot's **effective permissions in that channel**, including channel overrides, must include:
    - View Channel
    - Send Messages
  - **Mentions in their template:** the template is their own, or the server-wide one if they don't have one. Find its mentions with a shared `Mentions.parse(template)` written in this step (step 8 reuses it), then check:
    - **`@everyone`/`@here`:** the bot needs Mention Everyone in that channel. Otherwise it's posted as text but doesn't ping.
    - **Each `<@&role>`:** the role must exist, and must either be marked "Allow anyone to @mention this role" or the bot must have Mention Everyone in that channel (which covers all roles). Otherwise it doesn't ping.
    - **Each `<@user>`:** the user is still in the server.
  - **Live role:** if a live role is set, the streamer's linked Discord user must be in the server, and the role checks above apply. If they have no linked user, the result says "no live role will be given".
  - **Deleting old posts:** if delete-on-offline is on, the bot needs View Channel and Read Message History. It can delete its own messages without Manage Messages.
  - **Template length:** the filled-in message must fit in 2000 characters, using a long example stream title.
  - **Status notes:** the Twitch subscription disabled by Twitch (from step 5's EventSub statuses). *(Step 7 adds: paused because the self-service role was lost, with the date.)*

  **Result per check:** OK, warning (it posts, but something is degraded, e.g. a mention won't ping), or error (nothing will post). Each has a plain-English reason and a fix hint, e.g. "Give MorganaBot *Send Messages* in #live, or pick another channel". Results are grouped by streamer, with server-wide issues first.
- [ ] 7. **Self-service streamer adding (backend and commands).**
  - **Data:** add to `ServerInfo`: `selfServiceRoles: List<{roleId, channelId}>` (missing = empty, which turns the feature off) and `selfServiceRemoveAfterDays` (default 7, 0 = never). Add to `Streamer`: `selfService`, `addedBy`, `selfServiceLostAt` and `channelOverridePinned`. All of them are additive.
  - **Intents:** enable `GUILD_MEMBERS` in `JDABuilder` with `MemberCachePolicy.ALL` and `ChunkingFilter.ALL`. That's fine for a private bot in fewer than 100 servers. JDA only fires role-change events for cached members.
  - **`ServerSettings`:**
    - `resolveSelfServiceChannel(member)`: picks the member's highest configured role by Discord position and returns its override, which may be empty.
    - `selfSetTwitch(member, twitchName)` and `selfRemove(member)`, applying the command rules, with an audit entry for each.
    - `reconcileSelfService(guild, member)`: works out whether the member is still eligible and which channel they should get, then pauses, resumes or updates the channel as needed. It's idempotent: if nothing changed, it writes and audits nothing.
  - **Listeners:** `GuildMemberRoleAddEvent`, `GuildMemberRoleRemoveEvent`, `GuildMemberRemoveEvent` and `GuildMemberJoinEvent` call `reconcileSelfService`, as do the startup check and edits to the self-service role list.
  - **Expiry:** the hourly watcher loop, and startup, remove self-added entries that have been without a role for more than `selfServiceRemoveAfterDays`, then call `triggerUpdate()`.
  - **Go-live:** skip paused (`selfServiceLostAt != 0`) self-added streamers. That's expected behaviour, so it's not recorded as a problem.
  - **Commands:** register `/settwitchchannel` and `/removetwitchchannel` (guild-only, default enabled), with the per-member rate limit. Admin commands (admin-only by default):
    - `/addselfservicerole role: channel:`
    - `/removeselfservicerole role:`
    - `/setselfserviceexpiry days:`
    - a `role-based: True` option on `/setchanneloverride` to unpin a self-added streamer
    
    Every other setting can be changed both by command and in the dashboard, so these can too.
  - **Diagnose:** add the self-service checks to step 6's Diagnose: roles exist, override channels pass the channel checks, the paused note, and which role the channel came from.
- [ ] 8. **Access levels and message permissions (backend).**
  - **Data:** add `adminRoles: [roleId]` and `messageCustomizationRules: [{roleId, allowEveryone, allowedMentionRoles}]` to `ServerInfo`, and `templateSetBy` to `Streamer`. All of them are additive.
  - **`Access.of(member)`** returns `ADMIN`, `STREAMER` or `NONE`, as described in Who can use the dashboard, plus helpers:
    - `canEditAdminRoles(member)`
    - `canCustomizeMessage(member)`
    - `allowedMentions(member)`, the combination of all their matching rules
  - **`ServerSettings`:**
    - setters for admin roles (Discord Administrator or super admin only) and for customization rules, each audited
    - `setOwnTemplate(member, text)`: allows a mention if it's allowed by the member's current rules or present in the previously saved message (own template, or else the server-wide one). The same applies to user mentions (except the streamer themself). It also checks the length limit and records `templateSetBy`. Admin saves aren't mention-checked.
  - **Posting:** build the message with explicit `allowed_mentions`:
    - ping = exactly the mentions written in the template text (no permission check at post time)
    - Twitch-supplied values are defused (`@` → `@\u200B`) before insertion; template text is never altered
    - `${twitch_link}` uses the broadcaster's login (`broadcaster_user_login` from the event), not the display name (known bug)
  - **Commands:**
    - `/addadminrole` and `/removeadminrole` (Administrator-only by default, and checked by the bot)
    - `/addmessagerole role: allow-everyone: mention-roles:` and `/removemessagerole role:` (admin-only by default)
  - **Diagnose:** add a check to step 6's Diagnose that the roles referenced by message permission rules still exist. Save-time mention validation reuses step 6's `Mentions.parse`.
- [ ] 9. **`/dashboard` command and login.** Register the command (default enabled, usable in servers and DMs). Reply with a link only if the user has access to at least one server, or is a super admin. Add `superAdmins` to `Configuration` and `blank-config.json`. Reply with an ephemeral message containing a **link button** (no URL in the text). Add `LoginTokenStore`: single-use tokens tied to a user, valid for **30 seconds**, each remembering its interaction hook. When a token is redeemed or expires, edit the reply to "Logged in ✓" or "Link expired, run /dashboard again" and remove the button. Use SiggiHTTP sessions (`Sessions.create`, `HTTPServerBuilder.setSessions`) holding the user ID, super admin flag, CSRF token and login time; access isn't stored, it's checked per request. Call `resetSession()` at login. Add the stdout recovery login link from step 5 (a super admin token that doesn't need Discord). Add `GET /dashboard/login?token=` (use the token, reset the session, redirect straight away with `Referrer-Policy: no-referrer`) and `POST /dashboard/api/logout`. Thread safety: each request runs on its own thread, so login tokens are used up atomically (`ConcurrentHashMap.remove`, so two tabs can't both redeem one link).
- [ ] 10. **Web plumbing.** Add a request helper that sends JSON responses with consistent errors, finds the session, checks CSRF on writes, and checks access **per request** with `Access.of(member)` (step 8): admin endpoints need `ADMIN`, the own-entry endpoints need at least `STREAMER`, and super admin sessions can access any guild the bot is in. If Discord is unavailable, super admin sessions still work (the flag is in the session), and guild endpoints return a clear "Discord unavailable" error. Set security headers (`Referrer-Policy: no-referrer`, a strict CSP, `X-Frame-Options: DENY`, `Cache-Control: no-store` on API responses). Serve static files from the classpath.
- [ ] 11. **JSON API for streamers and server admins** under `/dashboard/api/guilds/{guildId}/…`:
  - `GET /dashboard/api/me`: who is logged in, whether they're a super admin, and the servers they can open, each with their access level (`admin` or `streamer`)
  - Streamer (own entry): `GET …/me/streamer` (their entry, status and channel, what they may customize, and which mentions they're allowed), `PUT …/me/streamer/template`, `PUT …/me/streamer/twitch` and `DELETE …/me/streamer` (self-service set and remove, same rules as the commands).
  - Admin: `GET`/`PUT …/admin-roles` (`PUT` needs Discord Administrator or super admin), and `GET`/`PUT …/message-rules`.
  - `GET …/settings`, `PATCH …/settings`
  - `GET …/channels` (text channels only), `GET …/roles` (marking roles the bot can't assign because they're above its own role)
  - `GET …/members?q=`
  - `GET …/streamers` (adds Twitch display name and login, fetched in batches), `POST …/streamers`, `PATCH …/streamers/{twitchId}`, `DELETE …/streamers/{twitchId}`
  - `GET …/self-service`, `PUT …/self-service` (the whole role → channel list plus `removeAfterDays`; each role and channel is checked to belong to the guild). `PATCH …/streamers/{twitchId}` with `channelOverridePinned: false` unpins a self-added streamer and recalculates their channel from their roles straight away. Any channel change by an admin pins.
  - `GET …/audit?type=&before=`: this server's audit log, paged and newest first. Super-admin-only entries are left out for normal sessions.
  - `POST …/diagnose`: runs Diagnose now and returns the results. `?streamer={twitchId}` limits it to one streamer.
  - `GET …/problems`: recorded failures (step 4), the latest Diagnose results (step 6), and a one-line "Twitch is having problems, posts may be delayed" flag when the system's Twitch status is red (no details, which are for super admins). `DELETE …/problems/{id}` and `DELETE …/problems` dismiss entries.
  - Super admin endpoints are added in steps 14, 16 and 17, next to their backends.
  - Every channel ID and role ID sent in is checked to belong to that guild.
- [ ] 12. **Frontend: shell and the streamer page.** Set up the TypeScript build first (see Design decisions → Frontend):
  - `src/main/web/` with `package.json` (pinned `typescript`, and a `package-lock.json` committed), and `tsconfig.json` (`strict`, ES module output to `target/classes/web/`)
  - `frontend-maven-plugin` in `pom.xml`, with a pinned Node version
  - `node_modules/` and the downloaded Node added to `.gitignore`
  - the web server serving `/dashboard/` from `web/` on the classpath
  
  `mvn package` must build the frontend too.
  - **Shared shell:** loads `GET /dashboard/api/me`, has a server dropdown when the user can open more than one server, a fetch helper that sends the CSRF header and shows "run /dashboard again" when the session has expired, error display next to the field it belongs to, and escaping (everything is inserted as text).
  - **"My stream" page** for streamer-level access: status, Twitch channel set/remove, and the message editor. The editor lists the variables, shows a live preview, and only offers the mentions they're allowed; disallowed ones are greyed out with the reason.
- [ ] 13. **Frontend: server admin pages.**
  - **Access** section: admin roles, editable only by Discord Administrators and super admins; for everyone else it's read-only with an explanation.
  - **Message permissions:** rules per role, with an allow `@everyone`/`@here` toggle and a multi-select of allowed role mentions.
  - **Settings form.**
  - **Streamer table** with add, edit and delete. It has "self-added", "paused since …" and "pinned" badges, and a "Go back to role-based" button.
  - **Self-service section:** the configured roles **sorted by Discord role order**, so the precedence is visible. Each has a channel picker or "server default", with add and remove buttons, plus the "remove after N days without the role" setting (0 = never). It explains that the highest role wins even with no override.
  - **Audit tab** (step 3's log).
  - Problems:
  - A **Problems** panel at the top whenever there's anything to report. It shows current Diagnose errors and warnings, recent recorded failures (newest first, with count and last seen), and a banner when Twitch is failing for the whole bot (no details).
  - Warnings next to the setting or streamer row they relate to, e.g. a "can't post here" badge on the channel picker.
  - A **Diagnose** button on the Problems panel, and a "Diagnose" action on each streamer row. It shows the results grouped by streamer (✓, warning or error per check, the reason, and a fix hint), with server-wide issues first. A summary line reads like "12 streamers checked: 9 OK, 2 warnings, 1 error".
  - Diagnose also runs automatically on load and after every save, so a newly picked channel the bot can't post in gets flagged straight away. Each streamer row shows a status badge from the latest result.
- [ ] 14. **Super admin API and frontend.**
  - **API (403 for normal sessions):**
    - `GET /dashboard/api/admin/guilds`: all servers with the summary fields from the Super admin section
    - `enabledTwitchTracking` accepted in `PATCH …/settings`
    - `GET /dashboard/api/admin/system`: status cards, uptime, version and counts
    - `GET /dashboard/api/admin/system/log?component=&severity=&before=` and `DELETE /dashboard/api/admin/system/log/{id}`
  - **Frontend:** when `me` says the session is a super admin, show:
    - the server switcher sidebar
    - the "Super admin" badge
    - the Twitch on/off toggle
    - the **System** page: status cards, the system log, and a red dot on the sidebar item when anything is red. It works when Discord is down.
- [ ] 15. **Make `enabledTwitchTracking` actually control the Twitch watcher.** Today the flag only blocks commands. `ensureSubscriptionToEvents` and `handleNotification` ignore it, so a disabled server with streamers still gets subscriptions and live posts. Skip disabled servers in both places, and call `triggerUpdate()` when the flag changes so subscriptions are added or removed straight away.
- [ ] 16. **Remove and block servers (backend, API and UI).** Add the optional block fields to `ServerInfo`. `ServerSettings.leaveGuild(guildId, reason, block, byUser)` saves the block first, then calls `guild.leave()`, then `triggerUpdate()`, and writes the action to the stdout log. Add a `GuildJoinEvent` listener and a startup sweep that leave blocked servers. Finding all blocked servers means scanning `data/servers/*.json` for `blocked: true`; that's cheap at this scale, and it keeps the block in the per-server file.
  - **API (super admin only):** `POST /dashboard/api/admin/guilds/{guildId}/leave` (`{confirmName, reason, block}`), `GET /dashboard/api/admin/blocked`, and `DELETE /dashboard/api/admin/blocked/{guildId}`.
  - **UI:** the "Remove bot from server" button with its type-the-name confirmation, and the Blocked servers list.
- [ ] 17. **Super admin audit entries and pages.** The core log and the per-server tab already exist (steps 3 and 13). This step adds:
  - Super-admin-only entries for leave, block and unblock (from step 16).
  - Super-admin-only entries for blocked-server joins and startup leaves (from step 16's join listener and startup sweep), including the server's name, owner, member count, and the inviter when the audit log permission allows it. Repeated join attempts are combined.
  - Reading all servers' audit files for the merged view.
  - `GET /dashboard/api/admin/audit?guild=&type=&before=` (super admin only, paged, newest first).
  - The super admin "Audit log" page.
  
  Step 16 ships before this one, so its listener gets the audit call added here.
- [ ] 18. **Docs:** update the README with the dashboard, who can access it (Administrator, admin roles, streamers), message permissions, the new commands (`/addadminrole`, `/removeadminrole`, `/addmessagerole`, `/removemessagerole`), the `/dashboard` command, self-service adding (`/settwitchchannel`, `/removetwitchchannel`, `/addselfservicerole`, `/removeselfservicerole`, and the role precedence rule), the requirement that the reverse proxy sends `X-Forwarded-Proto`, and the `superAdmins` config key. Update CLAUDE.md with the new classes.

### Manual test checklist (for the user, not run by agents)

These need real Discord accounts and roles, a Twitch streamer actually going live to the public HTTPS endpoint, and a copy of production data, so an agent can't do them. They're plain bullets, not checkboxes, so `run-plan` doesn't pick them up. Each step's own automated checks are described in CLAUDE.md.

- **Login:** `/dashboard` shows a button, not a URL. After clicking it, the reply changes to "Logged in ✓" with no button. Unused, it changes to "Link expired" after 30 seconds. A second click on a used link and a link older than 30 seconds are both rejected. The address bar shows `/dashboard/` with no token.
- **Basics:** Run `/dashboard`, log in, change every setting, check that the matching command output agrees, and check that a second login link and an expired token are both rejected. Problems and Diagnose:
- Remove the bot's Send Messages permission in the live channel with a channel override only, delete the live role, and move the live role above the bot's role. Check that each one shows up in Diagnose **without anyone going live**, and in the recorded failures after a live event.
- Put a non-mentionable role and `@everyone` in a streamer's template without giving the bot Mention Everyone: Diagnose warns about both. Make the role mentionable: the role warning clears.
- Give one streamer an override channel the bot can't see: only that streamer shows an error.
- Point a streamer at an announcement channel: Diagnose flags it until the Known bugs fix lands, after which it posts. System health:
- Put a wrong Twitch client secret in a copy of the config and check that Twitch API turns red with the reason and a super admin DM arrives. Restore it, and check that recovery is shown and DM'd.
- Use an invalid Discord token: startup doesn't crash, the stdout recovery link works, and the System page shows Discord red with "fix and restart".
- Disable the members intent in the dev portal and check that `DISALLOWED_INTENTS` is shown.
- Send a notification with a bad signature and check that it's counted.
- Make the `data/servers` directory read-only and check that a save failure is logged.
- Fake a 429 from Twitch and check that rate limiting is shown.

Super admin: log in from a DM and from a server, open a server the super admin isn't a member of, turn Twitch on and off and check that subscriptions follow. Data: run the build against a copy of the production `data/` directory and check that everything loads and saves unchanged (see Data compatibility). Hand-edit a server JSON while the bot is running and check that the change is picked up.

Self-service:
- Configure two roles with different overrides. Check that a member with both gets the higher role's channel.
- Move the role order in Discord and check that the result follows.
- Give the higher role an empty override and check that the member gets the server default.
- A member without any self-service role is refused.
- An already-claimed Twitch name is refused.
- Running the command again replaces the member's own entry.
- `/removetwitchchannel` removes it.
- Taking the role away pauses the streamer immediately: go-live posts nothing, and the table shows "Paused". Giving it back resumes them.
- Changing which self-service role is the member's highest moves their channel, unless it's pinned. An admin setting the channel pins it. Unpinning, from both the dashboard and `/setchanneloverride role-based:True`, recalculates the channel from the member's current roles straight away.
- With the expiry set to 0 days, check that the hourly/startup cleanup removes the paused entry, and that 0 really means never.
- Change roles while the bot is stopped, and check that startup catches up.
- Repeated use within a minute is rate-limited.

Access:
- A Discord Administrator gets the full dashboard.
- A member with only a MorganaBot admin role gets the full dashboard but can't edit the admin roles.
- Removing that role cuts them off on their next request.
- A plain streamer sees only "My stream".
- A member with neither gets no link from `/dashboard`.

Message permissions:
- A streamer without a rule can't edit their message.
- With a rule, they can, but saving `@everyone` or an unallowed role mention is rejected.
- Two rules combine.
- Revoke the rule after they've saved a message with an allowed role mention. Check that the next post still pings, and that they can still edit and save the message with that mention kept.
- An admin puts `@everyone` in a streamer's template. The streamer (no `@everyone` rule) edits other text and saves: it's accepted and still pings. They remove it and save, then try to add it back: rejected. The same goes for a user mention.
- A streamer with no template of their own, whose server-wide template contains `@everyone`, can save an edited copy keeping it.
- An existing production template with a role mention keeps pinging after deploy.
- A stream title containing `@everyone`, `@here` or `<@&role>` doesn't ping, even when the template itself contains `@everyone`. The title still reads normally.

Audit: change settings through both commands and the dashboard, and check that each one appears in the server's Audit tab with the right person, source and old → new value. Check that a normal admin doesn't see super-admin-only entries. Remove the dev bot from a test server with block on: check that it leaves, its subscriptions are removed, re-inviting makes it leave again straight away and an attempted-join entry appears in the audit log (with a count after a few retries), and unblocking lets the re-invite stay with the old settings intact. Check that a normal session gets 403 on the admin endpoints and on `enabledTwitchTracking`, and that a user with no access to any server gets no link from `/dashboard` in a DM.

## Known bugs / candidates

Every known bug is assigned to a step. **The agent doing that step ticks the bug here too, with the same main commit hash,** so `run-plan` never picks these up as separate tasks. New bugs found later get assigned to a step too, or get their own.

Race conditions (SiggiHTTP handles each request on its own thread, and JDA command events run on a separate thread, so these overlap in practice):
- [x] Unlocked load-modify-save of server files, and the stale `lastLiveNotificationId` save. Fixed by step 1. — `ca50202`
- [ ] Offline arriving right after online: `lastLiveNotificationId` is only saved once Discord confirms the send, so a quick offline finds nothing to delete and the post stays up (commit `d2546e6` cleans it up at the next go-live). Two notifications for the same streamer can also run the cooldown check at the same time. Fix: process events per streamer one at a time (a per-Twitch-ID lock or a single-threaded queue), and have offline wait for an in-flight send. Fixed by step 5.
- [ ] Token refresh window: `runLoop` clears the token before fetching a new one, and other threads throw "Auth token not available" in the gap, so notifications are dropped. `AuthCache` fields are shared across threads with no synchronization. Fix: keep the old token until the new one is in place, refresh under a lock from any thread, and refresh early when `twitchAppAccessTokenExpiry` is near. This also fixes the expired-token item below. Fixed by step 5.
- [ ] `triggerUpdate()` NullPointerException after `stop()` sets `thread` to null. Fixed by step 5.

Security:
- [ ] Twitch stream titles (and other Twitch values) are inserted into the live message with no mention filtering, so a title containing `@everyone`/`@here` or `<@&role>` pings if the bot has the permission. Fixed by step 8 (defusing Twitch values plus `allowed_mentions`).

Other:

- [x] `getStreamer` (`TwitchCommandHandler`) matches the wrong streamer when only a Twitch name is given: `discordUserId` is 0, so any streamer with `discordId == 0` matches first. `/deletestreamer twitch-name:X` can delete someone else. Fixed by step 2. — `23b908e`
- [x] `/addstreamer` with a nonexistent Twitch name NPEs (`getStreamerInfoByUsername` returns null) → generic error instead of a helpful message. Fixed by step 2. — `23b908e`
- [ ] Expired Twitch app token isn't detected in the notification path: `getAuthToken` never checks `twitchAppAccessTokenExpiry`, and failures in `handleNotification` are swallowed, so live posts silently fail until the hourly loop refreshes the token. Fixed by step 5.
- [ ] Announcement (news) channels are silently skipped: `handleLiveNotification` only accepts `TextChannel`, so a live channel that's an announcement channel never gets posts, and nothing reports it. Fix: accept any `StandardGuildMessageChannel` (text and announcement). Diagnose (step 6) flags it in the meantime. Fixed by step 4.
- [ ] `${twitch_link}` and `/streamerlist` links use display name instead of login name — breaks for non-Latin display names. Fixed by steps 2 (`/streamerlist`) and 8 (`${twitch_link}`). The `/streamerlist` part is done (`23b908e`); `${twitch_link}` is still open.
- [x] `/setchanneloverride`'s `channel` option is `OptionType.STRING` rather than `CHANNEL`; that's why `getChannelId` has the "JDA bug" `<#…>` stripping workaround. Fixed by step 2. — `23b908e`
- [ ] Live role add/remove is attempted for streamers with no linked Discord user (`discordId == 0`). Fixed by step 4.

## Notes

- JDA bumped 6.5.0 → 6.7.0 — `c1bf04e`
- SiggiHTTP bumped 1.10 → 1.11-SNAPSHOT — `b8e9cd8`. This version only adds API (error pages, header timeout, dev mode, `getNextHopAddress`). Its built-in session cookie still has no `SameSite` option. The dashboard uses SiggiHTTP sessions anyway (user's decision) and relies on the CSRF header check. The snapshot isn't published to repo.siggi.io and won't be, so a fresh checkout of `main` only builds where it's installed locally. The user is fine with this; it'll be changed to the 1.11 release later.
- If SiggiHTTP bugs turn up, report them to the `SiggiHTTP` Claude session with SendMessage.

# Plan

## Done

- [x] Remove unimplemented `/letmein` (Minecraft whitelist) command and `MinecraftHandler` — `35b85f0`

## Web dashboard

Goal: a web UI that can read and change everything about a server. Anything a command can change, the web UI can change too.

**Nobody should need to edit server files by hand.** Every per-server setting must be changeable from the web UI. That includes settings no command can change today: the notification cooldown, a streamer's linked Discord user, and (super admin only) the Twitch on/off switch. If a per-server setting is added later, it gets a place in the UI too.

**`config.json` stays file-only, by the user's choice.** It holds the public URL, the port, the Discord token, the Twitch credentials and `superAdmins`. These are set at install and rarely change, so there's no UI for them. User timezones stay per-user via `/timezone`. Access is granted by a new `/dashboard` command, which only server admins can use by default.

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

### Super admin

Not a separate UI: it's the same dashboard, with extra powers when the session belongs to a super admin. It can view and change settings on **any** server the bot is in, e.g. when a server owner asks for help setting something up. It is also the only place `enabledTwitchTracking` can be turned on or off, apart from editing the JSON by hand.

- **Who:** set in `config.json` as a list of Discord user snowflakes:
  ```json
  "superAdmins": ["260595748465278976"]
  ```
  IDs are written as strings, because snowflakes are too big for JSON numbers to hold exactly in many tools (JavaScript included). Gson reads them into a `List<Long>`, and plain numbers are accepted too. A missing or empty list means there are no super admins. The list is only read at startup, so changing it needs a restart. The hard-coded `<@260595748465278976>` in the "not enabled" messages is replaced with the first super admin from config. If the list is empty, the message leaves out the contact part.
- **Login:** the same `/dashboard` command. It's also allowed in DMs with the bot. In a DM, only super admins get a link (everyone else is told to run it in their server). A super admin's link starts a super admin session from either place.
- **Server switcher:** a sidebar on the left of the normal UI, shown only for super admin sessions. It lists every server the bot is in, with name, icon, ID, member count, Twitch on/off, number of streamers, and number of current problems. It's searchable by name or ID, and filterable by "Twitch enabled" and "has problems". Picking a server loads it into the same settings view.
- **Server view:** the same page a server admin sees, plus the `enabledTwitchTracking` toggle and a small "Super admin" badge in the header so it's clear which mode you're in.
- **Remove the bot from a server**, e.g. when a server breaks the acceptable use rules. It's a button on the server view, with a confirmation that requires typing the server's name. There's an optional reason note and a **"Block from re-adding"** checkbox, which is on by default. Without a block, anyone with an invite link could just add the bot back.
  - The block is stored in that server's own file (`blocked`, `blockedAt`, `blockedBy`, `blockedReason`, all new optional fields, so it's additive). If the bot joins a blocked server (`GuildJoinEvent`), or is still in one at startup, it leaves immediately and logs it.
  - The server's settings file is kept after removal, so unblocking and re-inviting restores the old setup.
  - The bot only subscribes to Twitch for servers it's currently in, so removed servers drop out on their own. `triggerUpdate()` is called after leaving so it happens straight away.
  - The super admin UI has a **Blocked servers** list (name at time of block, ID, when, by whom, reason) with an Unblock button. Unblocking doesn't re-add the bot; the server has to invite it again.
- **Audit log, visible to super admins only.** It records:
  - every change made from a super admin session (setting, old → new value)
  - removing the bot from a server, and blocking and unblocking servers, with the reason
  - **attempted joins from blocked servers**, and blocked servers left at startup. Each entry records the server's name at the time, its ID, its owner's user ID, and its member count. If the bot happens to have View Audit Log permission, it also records who added the bot, by checking the server's audit log for the bot-add entry before leaving. Otherwise that's shown as "unknown".
  
  Each entry has a time, a type, the server, who did it (a super admin, or "system" for automatic actions), details, and an optional reason.
  - **Storage:** entries are kept per server in `data/servers/<guildId>-audit.jsonl` (one JSON object per line, append-only), following the per-server-files rule. The bot-wide view merges them.
  - **Join-attempt floods:** each attempt is still recorded, but repeated attempts from the same blocked server within an hour are combined into one entry with a count and first/last times.
  - **Retention:** entries are kept indefinitely, apart from a cap of the newest 1000 per server.
  - **UI:** an "Audit log" page in the super admin sidebar, newest first, filterable by server and event type. Each server's view also has an Audit tab showing just that server's entries.
- **Safety:** super admin sessions expire after 8 hours instead of 24. The server checks the super admin flag on every request; hiding the toggle in the UI isn't the security. Every change made from a super admin session goes into the audit log (below) and to stdout, with the user, server, setting, and old → new value.

### Data compatibility (production is live on the current format)

Every file under `data/` must keep loading after a deploy, with nothing lost and no manual steps.

- **Current plan doesn't change any existing format.**
  - Step 1 only changes how files are cached and locked.
  - Step 3 adds new `data/servers/<id>-problems.json` files. A missing file means no problems.
  - The audit log adds new `data/servers/<id>-audit.jsonl` files. A missing file means no entries.
  - Step 5 adds an optional `superAdmins` key to `config.json`. If it's missing, there are no super admins.
  - Sessions and login tokens live in memory only.
  - `ServerInfo`, `Streamer`, `UserData`, `AuthCache` and the existing `config.json` keys stay exactly as they are.
- **Per-server data stays in per-server files** (`data/servers/<guildId>.json`, `data/servers/<guildId>-problems.json`). Nothing per-server is ever bundled into a shared file. Only bot-wide state (`config.json`, `auth-cache.json`, the Twitch status) is global.
- **Additive changes are preferred:** new fields with a safe default when missing. Gson leaves a missing field at its initializer, because these classes have no-arg constructors. Gson also ignores unknown fields, so a rollback to the old jar keeps working with new files.
- **If a step ever needs to rename, move, restructure or change the meaning of stored data:**
  - Add `dataVersion` to that file type, where a missing value means version 0, today's format.
  - Migrate when the file is loaded.
  - Before the first migrated save, write a backup copy (`<name>.json.v<old>.bak`).
  - Add a migration test using a copy of today's format.
  - Write up the change here before doing it.
- **Hand-editing must keep working until the UI replaces it.** Today `enabledTwitchTracking` is turned on by editing the server JSON on production while the bot runs. After step 9 that's no longer needed, but the reload stays as a cheap safety net. That works now because every access reads the file fresh. Step 1's cache would silently ignore those edits, and then overwrite them on the next save. So the store must notice when the file's modified time changes on disk and reload it before using or saving it.
- **Deploy note:** add `superAdmins` to the production `config.json` before or with the deploy that includes step 5. Otherwise there's no super admin, and the "not enabled, contact …" message loses its contact.
- **Before deploying, test against real data:** run the new build on a copy of the production `data/` directory, not just the dev data. Check that every server, streamer, template and user timezone loads and shows up unchanged, and that saving doesn't drop any fields.

### Design decisions (veto any of these before I start)

- **Login flow:** `/dashboard` replies (ephemeral) with a one-time link, `{apiEndpoint}/dashboard/login?token=…`. The token is random, single-use, expires in 5 minutes, and is tied to that user and guild. Opening it starts a session cookie and redirects to `/dashboard/` without the token in the URL. Each session only has access to the guilds the user ran `/dashboard` in.
- **Permissions:** `/dashboard` is registered with `DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)` and is guild-only. Admins can grant it to other roles later under Server Settings → Integrations. Being able to run the command is the only access check, so access lasts until the session expires, even if the user's command permission is removed. If a later deploy changes the default, any overrides admins have already set stay in place.
- **Sessions:** kept in memory and expire after 24 hours, so a bot restart logs everyone out. I'll set the cookie myself rather than use SiggiHTTP's built-in session cookie, because that one can't set `SameSite`. The cookie is `HttpOnly; SameSite=Lax`, plus `Secure` when `X-Forwarded-Proto: https`.
- **CSRF:** any request that changes something must be JSON and carry a custom header (`X-Morgana-CSRF` holding a per-session token).
- **Frontend:** plain HTML, CSS and JS served from `src/main/resources/web/`, with no build step and no npm. Everything it displays from users or Twitch is inserted as text, never as HTML.
- **Choosing users, channels and roles:** channels and roles come from the JDA cache. To pick a Discord user, the UI searches members by name prefix through JDA (`retrieveMembersByPrefix`), which doesn't need the privileged member intent. Pasting a user ID also works.

### Steps (each finished step = commit on main, then tick here with the hash)

- [ ] 1. **Server data store with locking.** Commands, Twitch notifications and the web UI all load, change and save the same `data/servers/<id>.json` with no locking. The web UI makes it more likely that one save overwrites another. Notification handling already has this race. Its async callback saves `lastLiveNotificationId` on the `ServerInfo` it loaded *before* sending, so anything changed in between gets reverted, e.g. a streamer deleted while their live post was sending comes back. That callback must go through `modify()` and find the streamer again by Twitch ID (and do nothing if they were removed). Add a `ServerInfoStore` that keeps one cached `ServerInfo` per guild, with a per-guild lock and a `modify(guildId, fn)` method that saves after the change. Move `bot.getServerInfo` and every caller onto it. The cache reloads a file when its modified time on disk changes, so hand edits made while the bot is running are picked up and not overwritten (see Data compatibility).
- [ ] 2. **Move settings logic out of the command handler.** Create a `ServerSettings` service that does all the validation and changes for each setting, and calls `triggerUpdate` when needed. The command handlers then just parse options and pass them to it. While doing this, fix the `getStreamer` wrong-match bug and the `/addstreamer` NullPointerException listed below.
- [ ] 3. **Record failed actions per server.** Today every Discord action is fired with `.queue()` and no failure handler, so failures vanish. Add a `ServerProblems` record for each guild, saved to `data/servers/<id>-problems.json` and kept to the newest 100 entries. Each entry has a time, the action, the target (channel, role, member or streamer), and a readable reason, e.g. "Missing permission Send Messages in #live", "Unknown channel (deleted?)", "Role @Live is above the bot's highest role". Add failure callbacks that write an entry to every action in `handleLiveNotification` (send, delete, add role, remove role), and map common JDA errors (`InsufficientPermissionException`, `HierarchyException`, `ErrorResponseException` with `MISSING_PERMISSIONS` / `UNKNOWN_CHANNEL` / `UNKNOWN_MESSAGE` / `UNKNOWN_MEMBER`) to friendly text. Also log the cases that are skipped without anything being attempted: the configured channel is missing or isn't a text channel, the live role was deleted, or no channel is set. Repeated identical failures are combined into one entry with a count and a last-seen time, so a streamer going live 20 times doesn't fill the list. Problems with Twitch itself (token refresh, subscription sync, failed `getChannelInformation`) affect every server, so they go into one bot-wide status record instead (the latest error and when, plus when the last success was). Entries can be dismissed: one at a time, or all at once. Writes to the problem log are locked per server, like the server store.
- [ ] 4. **Check permissions before anything fails.** Add a function that checks the current config against what the bot can actually do right now. For each live channel it uses (default and overrides): does the channel exist, is it a text channel, and does the bot have View Channel and Send Messages there (plus Mention Everyone if the template contains `@everyone`/`@here`)? For the live role: does it exist, does the bot have Manage Roles, and is the role below the bot's highest role? Is each linked Discord user still in the server? This doesn't store anything; it runs when the UI asks for it.
- [ ] 5. **`/dashboard` command and login.** Register the command with admin-only default permissions, usable in servers and in DMs with the bot. In DMs, only super admins get a link. Add `superAdmins` to `Configuration` and `blank-config.json`. Add `LoginTokenStore` (one-time tokens) and `DashboardSessions` (sessions, the guilds each one can access, CSRF token, expiry). Add `GET /dashboard/login?token=` (use the token, create the session, redirect) and `POST /dashboard/api/logout`. Thread safety: each request runs on its own thread, so login tokens are used up atomically (`ConcurrentHashMap.remove`, so two tabs can't both redeem one link), and the session store is a concurrent map.
- [ ] 6. **Web plumbing.** Add a request helper that sends JSON responses with consistent errors, finds the session, checks CSRF on writes, and checks that the session can access the guild (super admin sessions can access any guild the bot is in). Set security headers (`Referrer-Policy: no-referrer`, a strict CSP, `X-Frame-Options: DENY`, `Cache-Control: no-store` on API responses). Serve static files from the classpath.
- [ ] 7. **JSON API** under `/dashboard/api/guilds/{guildId}/…`:
  - `GET /dashboard/api/me`: who is logged in, whether they're a super admin, and the guilds this session can access (name, icon)
  - `GET …/settings`, `PATCH …/settings`
  - `GET …/channels` (text channels only), `GET …/roles` (marking roles the bot can't assign because they're above its own role)
  - `GET …/members?q=`
  - `GET …/streamers` (adds Twitch display name and login, fetched in batches), `POST …/streamers`, `PATCH …/streamers/{twitchId}`, `DELETE …/streamers/{twitchId}`
  - `GET …/problems`: recorded failures (step 3), the live permission check results (step 4), and the bot-wide Twitch status. `DELETE …/problems/{id}` and `DELETE …/problems` dismiss entries.
  - Super admin only: `GET /dashboard/api/admin/guilds` (all servers with the summary fields above), `enabledTwitchTracking` accepted in `PATCH …/settings`, `POST /dashboard/api/admin/guilds/{guildId}/leave` (`{confirmName, reason, block}`), `GET /dashboard/api/admin/blocked`, `DELETE /dashboard/api/admin/blocked/{guildId}`, and `GET /dashboard/api/admin/audit?guild=&type=&before=` (paged, newest first). All return 403 for normal sessions.
  - Every channel ID and role ID sent in is checked to belong to that guild.
- [ ] 8. **Frontend:** a settings form, and a streamer table with add, edit and delete. It shows server-side validation errors next to the field they belong to. It handles an expired session by showing "run /dashboard again". Problems:
  - A **Problems** panel at the top whenever there's anything to report. It shows current permission-check failures, recent recorded failures (newest first, with count and last seen), and a banner when Twitch is failing for the whole bot.
  - Warnings next to the setting or streamer row they relate to, e.g. a "can't post here" badge on the channel picker.
  - Saving a setting runs the permission check immediately, so a newly picked channel the bot can't post in gets flagged straight away.
- [ ] 9. **Super admin additions to the frontend:** when `GET /dashboard/api/me` says the session is a super admin, show the server switcher sidebar (from `GET /dashboard/api/admin/guilds`), the "Super admin" badge, the Twitch on/off toggle, the "Remove bot from server" button with its type-the-name confirmation, and the Blocked servers list. A normal session that has run `/dashboard` in more than one server gets a simple dropdown to switch between those servers instead.
- [ ] 10. **Make `enabledTwitchTracking` actually control the Twitch watcher.** Today the flag only blocks commands. `ensureSubscriptionToEvents` and `handleNotification` ignore it, so a disabled server with streamers still gets subscriptions and live posts. Skip disabled servers in both places, and call `triggerUpdate()` when the flag changes so subscriptions are added or removed straight away.
- [ ] 11. **Remove and block servers (backend).** Add the optional block fields to `ServerInfo`. `ServerSettings.leaveGuild(guildId, reason, block, byUser)` saves the block first, then calls `guild.leave()`, then `triggerUpdate()`, and writes the action to the stdout log. Add a `GuildJoinEvent` listener and a startup sweep that leave blocked servers. Finding all blocked servers means scanning `data/servers/*.json` for `blocked: true`; that's cheap at this scale, and it keeps the block in the per-server file.
- [ ] 12. **Audit log (backend).** Add `AuditLog`, which appends entries to `data/servers/<guildId>-audit.jsonl` under the per-server lock, combines repeated join attempts, trims each file to 1000 entries, and reads all servers' files for the merged view. Record these into it:
  - every super admin change (from step 9's endpoints)
  - leave, block and unblock (from step 11)
  - blocked-server joins and startup leaves (from step 11's join listener and startup sweep), including the server's name, owner, member count, and the inviter when the audit log permission allows it
  
  Step 11 ships before this one, so its listener gets the audit call added here. The frontend's Audit log page and per-server Audit tab are also built in this step.
- [ ] 13. **Docs:** update the README with the dashboard, the `/dashboard` command and its permission, the requirement that the reverse proxy sends `X-Forwarded-Proto`, and the `superAdmins` config key. Update CLAUDE.md with the new classes.
- [ ] 14. **Test end to end with the dev bot:** run `/dashboard`, log in, change every setting, check that the matching command output agrees, and check that a second login link and an expired token are both rejected. Problems: remove the bot's Send Messages permission in the live channel, delete the live role, and move the live role above the bot's role. Check that each one shows up in the permission check and, after a live event, in the recorded failures. Super admin: log in from a DM and from a server, open a server the super admin isn't a member of, turn Twitch on and off and check that subscriptions follow. Data: run the build against a copy of the production `data/` directory and check that everything loads and saves unchanged (see Data compatibility). Hand-edit a server JSON while the bot is running and check that the change is picked up. Remove the dev bot from a test server with block on: check that it leaves, its subscriptions are removed, re-inviting makes it leave again straight away and an attempted-join entry appears in the audit log (with a count after a few retries), and unblocking lets the re-invite stay with the old settings intact. Check that a normal session gets 403 on the admin endpoints and on `enabledTwitchTracking`, and that a non-super-admin running `/dashboard` in a DM gets no link.

## Known bugs / candidates

Race conditions (SiggiHTTP handles each request on its own thread, and JDA command events run on a separate thread, so these overlap in practice):
- [ ] Unlocked load-modify-save of server files, and the stale `lastLiveNotificationId` save. Fixed by step 1.
- [ ] Offline arriving right after online: `lastLiveNotificationId` is only saved once Discord confirms the send, so a quick offline finds nothing to delete and the post stays up (commit `d2546e6` cleans it up at the next go-live). Two notifications for the same streamer can also run the cooldown check at the same time. Fix: process events per streamer one at a time (a per-Twitch-ID lock or a single-threaded queue), and have offline wait for an in-flight send.
- [ ] Token refresh window: `runLoop` clears the token before fetching a new one, and other threads throw "Auth token not available" in the gap, so notifications are dropped. `AuthCache` fields are shared across threads with no synchronization. Fix: keep the old token until the new one is in place, refresh under a lock from any thread, and refresh early when `twitchAppAccessTokenExpiry` is near. This also fixes the expired-token item below.
- [ ] `triggerUpdate()` NullPointerException after `stop()` sets `thread` to null.

Other:

- [ ] `getStreamer` (`TwitchCommandHandler`) matches the wrong streamer when only a Twitch name is given: `discordUserId` is 0, so any streamer with `discordId == 0` matches first. `/deletestreamer twitch-name:X` can delete someone else.
- [ ] `/addstreamer` with a nonexistent Twitch name NPEs (`getStreamerInfoByUsername` returns null) → generic error instead of a helpful message.
- [ ] Expired Twitch app token isn't detected in the notification path: `getAuthToken` never checks `twitchAppAccessTokenExpiry`, and failures in `handleNotification` are swallowed, so live posts silently fail until the hourly loop refreshes the token.
- [ ] `${twitch_link}` and `/streamerlist` links use display name instead of login name — breaks for non-Latin display names.
- [ ] `/setchanneloverride`'s `channel` option is `OptionType.STRING` rather than `CHANNEL`; that's why `getChannelId` has the "JDA bug" `<#…>` stripping workaround.
- [ ] Live role add/remove is attempted for streamers with no linked Discord user (`discordId == 0`).

## Notes

- JDA bumped 6.5.0 → 6.7.0 — `c1bf04e`
- SiggiHTTP bumped 1.10 → 1.11-SNAPSHOT — `b8e9cd8`. This version only adds API (error pages, header timeout, dev mode, `getNextHopAddress`). Its built-in session cookie still has no `SameSite` option, so the dashboard keeps its own cookie. The snapshot isn't published to repo.siggi.io and won't be, so a fresh checkout of `main` only builds where it's installed locally. The user is fine with this; it'll be changed to the 1.11 release later.
- If SiggiHTTP bugs turn up, report them to the `SiggiHTTP` Claude session with SendMessage.

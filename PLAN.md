# Plan

## Done

- [x] Remove unimplemented `/letmein` (Minecraft whitelist) command and `MinecraftHandler` — `35b85f0`

## Web dashboard

Goal: a web UI that can read and change everything about a server. Anything a command can change, the web UI can change too. Access is granted by a new `/dashboard` command, which only server admins can use by default.

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

- **Who:** `config.json` gets `superAdmins: [<discord user id>, …]`. The hard-coded `<@260595748465278976>` in the "not enabled" messages is replaced with the first super admin from config.
- **Login:** the same `/dashboard` command. It's also allowed in DMs with the bot. In a DM, only super admins get a link (everyone else is told to run it in their server). A super admin's link starts a super admin session from either place.
- **Server switcher:** a sidebar on the left of the normal UI, shown only for super admin sessions. It lists every server the bot is in, with name, icon, ID, member count, Twitch on/off, number of streamers, and number of current problems. It's searchable by name or ID, and filterable by "Twitch enabled" and "has problems". Picking a server loads it into the same settings view.
- **Server view:** the same page a server admin sees, plus the `enabledTwitchTracking` toggle and a small "Super admin" badge in the header so it's clear which mode you're in.
- **Safety:** super admin sessions expire after 8 hours instead of 24. The server checks the super admin flag on every request; hiding the toggle in the UI isn't the security. Every change made from a super admin session is written to the bot's stdout log with the user, server, setting, and old → new value.

### Design decisions (veto any of these before I start)

- **Login flow:** `/dashboard` replies (ephemeral) with a one-time link, `{apiEndpoint}/dashboard/login?token=…`. The token is random, single-use, expires in 5 minutes, and is tied to that user and guild. Opening it starts a session cookie and redirects to `/dashboard/` without the token in the URL. Each session only has access to the guilds the user ran `/dashboard` in.
- **Permissions:** `/dashboard` is registered with `DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)` and is guild-only. Admins can grant it to other roles later under Server Settings → Integrations. Being able to run the command is the only access check, so access lasts until the session expires, even if the user's command permission is removed. If a later deploy changes the default, any overrides admins have already set stay in place.
- **Sessions:** kept in memory and expire after 24 hours, so a bot restart logs everyone out. I'll set the cookie myself rather than use SiggiHTTP's built-in session cookie, because that one can't set `SameSite`. The cookie is `HttpOnly; SameSite=Lax`, plus `Secure` when `X-Forwarded-Proto: https`.
- **CSRF:** any request that changes something must be JSON and carry a custom header (`X-Morgana-CSRF` holding a per-session token).
- **Frontend:** plain HTML, CSS and JS served from `src/main/resources/web/`, with no build step and no npm. Everything it displays from users or Twitch is inserted as text, never as HTML.
- **Choosing users, channels and roles:** channels and roles come from the JDA cache. To pick a Discord user, the UI searches members by name prefix through JDA (`retrieveMembersByPrefix`), which doesn't need the privileged member intent. Pasting a user ID also works.

### Steps (each finished step = commit on main, then tick here with the hash)

- [ ] 1. **Server data store with locking.** Commands, Twitch notifications and the web UI all load, change and save the same `data/servers/<id>.json` with no locking. The web UI makes it more likely that one save overwrites another. Notification handling already has this race: it saves `lastLiveNotificationId` from an async callback. Add a `ServerInfoStore` that keeps one cached `ServerInfo` per guild, with a per-guild lock and a `modify(guildId, fn)` method that saves after the change. Move `bot.getServerInfo` and every caller onto it.
- [ ] 2. **Move settings logic out of the command handler.** Create a `ServerSettings` service that does all the validation and changes for each setting, and calls `triggerUpdate` when needed. The command handlers then just parse options and pass them to it. While doing this, fix the `getStreamer` wrong-match bug and the `/addstreamer` NullPointerException listed below.
- [ ] 3. **Record failed actions per server.** Today every Discord action is fired with `.queue()` and no failure handler, so failures vanish. Add a `ServerProblems` record for each guild, saved to `data/servers/<id>-problems.json` and kept to the newest 100 entries. Each entry has a time, the action, the target (channel, role, member or streamer), and a readable reason, e.g. "Missing permission Send Messages in #live", "Unknown channel (deleted?)", "Role @Live is above the bot's highest role". Add failure callbacks that write an entry to every action in `handleLiveNotification` (send, delete, add role, remove role), and map common JDA errors (`InsufficientPermissionException`, `HierarchyException`, `ErrorResponseException` with `MISSING_PERMISSIONS` / `UNKNOWN_CHANNEL` / `UNKNOWN_MESSAGE` / `UNKNOWN_MEMBER`) to friendly text. Also log the cases that are skipped without anything being attempted: the configured channel is missing or isn't a text channel, the live role was deleted, or no channel is set. Repeated identical failures are combined into one entry with a count and a last-seen time, so a streamer going live 20 times doesn't fill the list. Problems with Twitch itself (token refresh, subscription sync, failed `getChannelInformation`) affect every server, so they go into one bot-wide status record instead (the latest error and when, plus when the last success was). Entries can be dismissed: one at a time, or all at once.
- [ ] 4. **Check permissions before anything fails.** Add a function that checks the current config against what the bot can actually do right now. For each live channel it uses (default and overrides): does the channel exist, is it a text channel, and does the bot have View Channel and Send Messages there (plus Mention Everyone if the template contains `@everyone`/`@here`)? For the live role: does it exist, does the bot have Manage Roles, and is the role below the bot's highest role? Is each linked Discord user still in the server? This doesn't store anything; it runs when the UI asks for it.
- [ ] 5. **`/dashboard` command and login.** Register the command with admin-only default permissions, usable in servers and in DMs with the bot. In DMs, only super admins get a link. Add `superAdmins` to `Configuration` and `blank-config.json`. Add `LoginTokenStore` (one-time tokens) and `DashboardSessions` (sessions, the guilds each one can access, CSRF token, expiry). Add `GET /dashboard/login?token=` (use the token, create the session, redirect) and `POST /dashboard/api/logout`.
- [ ] 6. **Web plumbing.** Add a request helper that sends JSON responses with consistent errors, finds the session, checks CSRF on writes, and checks that the session can access the guild (super admin sessions can access any guild the bot is in). Set security headers (`Referrer-Policy: no-referrer`, a strict CSP, `X-Frame-Options: DENY`, `Cache-Control: no-store` on API responses). Serve static files from the classpath.
- [ ] 7. **JSON API** under `/dashboard/api/guilds/{guildId}/…`:
  - `GET /dashboard/api/me`: who is logged in, whether they're a super admin, and the guilds this session can access (name, icon)
  - `GET …/settings`, `PATCH …/settings`
  - `GET …/channels` (text channels only), `GET …/roles` (marking roles the bot can't assign because they're above its own role)
  - `GET …/members?q=`
  - `GET …/streamers` (adds Twitch display name and login, fetched in batches), `POST …/streamers`, `PATCH …/streamers/{twitchId}`, `DELETE …/streamers/{twitchId}`
  - `GET …/problems`: recorded failures (step 3), the live permission check results (step 4), and the bot-wide Twitch status. `DELETE …/problems/{id}` and `DELETE …/problems` dismiss entries.
  - Super admin only: `GET /dashboard/api/admin/guilds` (all servers with the summary fields above), and `enabledTwitchTracking` accepted in `PATCH …/settings`. Both return 403 for normal sessions.
  - Every channel ID and role ID sent in is checked to belong to that guild.
- [ ] 8. **Frontend:** a settings form, and a streamer table with add, edit and delete. It shows server-side validation errors next to the field they belong to. It handles an expired session by showing "run /dashboard again". Problems:
  - A **Problems** panel at the top whenever there's anything to report. It shows current permission-check failures, recent recorded failures (newest first, with count and last seen), and a banner when Twitch is failing for the whole bot.
  - Warnings next to the setting or streamer row they relate to, e.g. a "can't post here" badge on the channel picker.
  - Saving a setting runs the permission check immediately, so a newly picked channel the bot can't post in gets flagged straight away.
- [ ] 9. **Super admin additions to the frontend:** when `GET /dashboard/api/me` says the session is a super admin, show the server switcher sidebar (from `GET /dashboard/api/admin/guilds`), the "Super admin" badge, and the Twitch on/off toggle. A normal session that has run `/dashboard` in more than one server gets a simple dropdown to switch between those servers instead.
- [ ] 10. **Make `enabledTwitchTracking` actually control the Twitch watcher.** Today the flag only blocks commands. `ensureSubscriptionToEvents` and `handleNotification` ignore it, so a disabled server with streamers still gets subscriptions and live posts. Skip disabled servers in both places, and call `triggerUpdate()` when the flag changes so subscriptions are added or removed straight away.
- [ ] 11. **Docs:** update the README with the dashboard, the `/dashboard` command and its permission, the requirement that the reverse proxy sends `X-Forwarded-Proto`, and the `superAdmins` config key. Update CLAUDE.md with the new classes.
- [ ] 12. **Test end to end with the dev bot:** run `/dashboard`, log in, change every setting, check that the matching command output agrees, and check that a second login link and an expired token are both rejected. Problems: remove the bot's Send Messages permission in the live channel, delete the live role, and move the live role above the bot's role. Check that each one shows up in the permission check and, after a live event, in the recorded failures. Super admin: log in from a DM and from a server, open a server the super admin isn't a member of, turn Twitch on and off and check that subscriptions follow. Check that a normal session gets 403 on the admin endpoints and on `enabledTwitchTracking`, and that a non-super-admin running `/dashboard` in a DM gets no link.

## Known bugs / candidates

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

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
- `enabledTwitchTracking` is **read-only**: it stays an owner-only switch set by editing the JSON. If it's off, the UI says so and the Twitch sections are disabled, the same as the commands.

Streamers (`/streamerlist`, `/addstreamer`, `/deletestreamer`):
- List: Twitch name and link, linked Discord user, channel override, template override, live now, last stream
- Add by Twitch name, with optional Discord user and channel override
- Delete
- Edit the channel override (`/setchanneloverride`), template override (`/setlivemessage discord-name:`), and linked Discord user (no command for that today)

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
- [ ] 3. **`/dashboard` command and login.** Register the command with admin-only default permissions and guild-only context. Add `LoginTokenStore` (one-time tokens) and `DashboardSessions` (sessions, the guilds each one can access, CSRF token, expiry). Add `GET /dashboard/login?token=` (use the token, create the session, redirect) and `POST /dashboard/api/logout`.
- [ ] 4. **Web plumbing.** Add a request helper that sends JSON responses with consistent errors, finds the session, checks CSRF on writes, and checks that the session can access the guild. Set security headers (`Referrer-Policy: no-referrer`, a strict CSP, `X-Frame-Options: DENY`, `Cache-Control: no-store` on API responses). Serve static files from the classpath.
- [ ] 5. **JSON API** under `/dashboard/api/guilds/{guildId}/…`:
  - `GET /guilds`: the guilds this session can access (name, icon)
  - `GET …/settings`, `PATCH …/settings`
  - `GET …/channels` (text channels only), `GET …/roles` (marking roles the bot can't assign because they're above its own role)
  - `GET …/members?q=`
  - `GET …/streamers` (adds Twitch display name and login, fetched in batches), `POST …/streamers`, `PATCH …/streamers/{twitchId}`, `DELETE …/streamers/{twitchId}`
  - Every channel ID and role ID sent in is checked to belong to that guild.
- [ ] 6. **Frontend:** a guild picker if the session has more than one guild, a settings form, and a streamer table with add, edit and delete. It shows server-side validation errors next to the field they belong to. It handles an expired session by showing "run /dashboard again".
- [ ] 7. **Docs:** update the README with the dashboard, the `/dashboard` command and its permission, and the requirement that the reverse proxy sends `X-Forwarded-Proto`. Update CLAUDE.md with the new classes.
- [ ] 8. **Test end to end with the dev bot:** run `/dashboard`, log in, change every setting, check that the matching command output agrees, and check that a second login link and an expired token are both rejected.

## Known bugs / candidates

- [ ] `getStreamer` (`TwitchCommandHandler`) matches the wrong streamer when only a Twitch name is given: `discordUserId` is 0, so any streamer with `discordId == 0` matches first. `/deletestreamer twitch-name:X` can delete someone else.
- [ ] `/addstreamer` with a nonexistent Twitch name NPEs (`getStreamerInfoByUsername` returns null) → generic error instead of a helpful message.
- [ ] Expired Twitch app token isn't detected in the notification path: `getAuthToken` never checks `twitchAppAccessTokenExpiry`, and failures in `handleNotification` are swallowed, so live posts silently fail until the hourly loop refreshes the token.
- [ ] `${twitch_link}` and `/streamerlist` links use display name instead of login name — breaks for non-Latin display names.
- [ ] `/setchanneloverride`'s `channel` option is `OptionType.STRING` rather than `CHANNEL`; that's why `getChannelId` has the "JDA bug" `<#…>` stripping workaround.
- [ ] Live role add/remove is attempted for streamers with no linked Discord user (`discordId == 0`).

## Notes

- JDA bumped 6.5.0 → 6.7.0 — `c1bf04e`
- SiggiHTTP bumped 1.10 → 1.11-SNAPSHOT — `b8e9cd8`. This version only adds API (error pages, header timeout, dev mode, `getNextHopAddress`). Its built-in session cookie still has no `SameSite` option, so the dashboard keeps its own cookie. **The snapshot is only in the local `~/.m2`, not on repo.siggi.io, so builds from a fresh checkout of `main` fail until it's published.**
- If SiggiHTTP bugs turn up, report them to the `SiggiHTTP` Claude session with SendMessage.

# MorganaBot

Discord bot (Java 17, Maven, JDA 6) that posts in Discord channels when Twitch streamers go live, and provides `/time` + `/timezone` for generating Discord timestamp codes. See `README.md` for setup.

## Build

- `mvn package` → `target/MorganaBot.jar` (shaded). `mvn -q -o compile` for a quick check.
- Once PLAN step 12 lands, the dashboard frontend is TypeScript in `src/main/web/`. `mvn package` builds it through `frontend-maven-plugin` (pinned Node in `target/`, `npm ci`, `tsc`), so the first build needs network access. Don't add frameworks or bundlers without asking.
- JUnit 5 tests live in `src/test/java/io/siggi/morganabot/` and run during `mvn package`. `ServerInfoFixtureTest` checks the v0 server format fixture (`src/test/resources/fixtures/server-v0.json`).
- The Discord token in `data/config.json` belongs to a **developer** bot account, not the production bot. It's safe to run the bot locally with it for testing.

## Layout (`src/main/java/io/siggi/morganabot/`)

- `MorganaBot` — entry point; loads config, starts web server + JDA, registers **all** slash commands (the full list is replaced on every startup).
- `Listener` — dispatches slash commands by name; timezone autocomplete.
- `TwitchStreamerWatcher` — Twitch EventSub: hourly loop syncs webhook subscriptions to the streamers in all guilds; `/twitch-notification` endpoint verifies HMAC, dedups, and posts/deletes live messages and adds/removes the live role.
- `TwitchCommandHandler` — streamer admin commands (`/addstreamer`, `/deletestreamer`, `/setlivechannel`, `/setstreamerrole`, `/setdeleteonoffline`, `/setlivemessage`, `/setchanneloverride`, `/streamerlist`).
- `TimeHandler` — `/time` parsing (regex) and `/timezone`; zones loaded from `data/timezones.txt`.
- `WebHandler` — raw `ServerSocket` loop feeding SiggiHTTP.
- `config/` — `Data` base class (Gson read; save via temp file + rename). Per-guild `data/servers/<id>.json` (`ServerInfo`), per-user `data/users/<id>.json` (`UserData`), `config.json`, `auth-cache.json`. Server files go through `ServerInfoStore` (`bot.getServerInfoStore()`): one cached `ServerInfo` per guild, per-guild lock, `get()` returns a copy, `modify(guildId, fn)` changes and saves; reloads when the file changes on disk. User data, config and auth cache are still read fresh with no locking.
- `audit/` — `AuditLog` (`bot.getAuditLog()`): per-guild `data/servers/<id>-audit.jsonl`, one `AuditEntry` per line, appended under the `ServerInfoStore` guild lock (`withLock`), trimmed to the newest 1000. Every `ServerSettings` method takes an `Actor` (`Actor.command/dashboard/superAdmin/system`) and records an entry when something actually changed; names for IDs are filled in by `AuditLog.Names` at record time.
- `problems/` — `ServerProblems` (`bot.getServerProblems()`): per-guild `data/servers/<id>-problems.json` of failed or skipped Discord actions (`Problem`: action, target, streamer, reason, count, first/last seen), written under the `ServerInfoStore` guild lock, newest 100 kept, identical failures combined; `dismiss`/`dismissAll`. `FailureReasons` maps JDA errors to readable text. `handleLiveNotification` records every send/delete/add-role/remove-role failure here.
- `health/` — `SystemHealth` (`bot.getSystemHealth()`): live status cards for Discord, Twitch API, EventSub and Storage (green/amber/red, since, latest error, `details`), amber→red escalation via `tick()`, and super admin DM alerts on red/recovery (max one per component per 15 min, stdout fallback; recipients are `config.superAdmins`). `SystemLog`: global `data/system-log.jsonl`, append-only (last line per ID wins, dismissals are tombstones), repeats combined, newest 1000, compacted at 2x. `Health` is the static hook for code without a bot reference (`Data.save()`, uncaught handler, command handlers). `DiscordHealthListener` maps JDA status/close codes. `Data.save()` throws `DataSaveException` on failure.
- `diagnose/` — `Diagnose` (`bot.getDiagnose()`): `run(guild[, twitchId])` returns a `DiagnoseResult` (server-wide `Check`s, then one group per streamer; OK/WARNING/ERROR with message and fix hint). Nothing is posted, changed or stored. Rules are in static `Diagnose.run(guildId, GuildView, ServerInfo, TwitchInfo, …)`, tested with a fake `GuildView`; `JdaGuildView` is the JDA-backed one (member lookups may block on REST). `Mentions.parse(template)` finds `@everyone`/`@here`/role/user mentions; `LiveMessage` picks and fills templates (shared with posting).
- `Access` (`bot.getAccess()`) — dashboard access per request: `ADMIN` (Discord Administrator/owner, an `adminRoles` role, or a super admin), `STREAMER` (linked streamer entry), `NONE`; plus `canEditAdminRoles`, `canCustomizeMessage`, `allowedMentions` (all matching `messageCustomizationRules` combined). `ServerSettings.setOwnTemplate` does the save-time mention check. Posts ping only the mentions written in the template (`LiveMessage.build` + `allowed_mentions`); Twitch values are defused.
- `twitch/` — `TwitchTokenManager` (app token under one lock, refreshed a day early, old token kept until a new one arrives, `invalidate` on 401), `StreamerQueue` (per-Twitch-ID ordering; offline waits for in-flight sends/deletes), `TwitchErrors`, `SubscriptionNotes` (per-streamer EventSub disabled/create-failed notes for Diagnose). `TwitchStreamerWatcher.call()` wraps every Helix request and reports to the Twitch API card.
- `dashboard/` — login: `/dashboard` (`DashboardCommand`, ephemeral link button, only for users with access somewhere or super admins) issues a 30 s single-use token from `LoginTokenStore` (`bot.getLoginTokens()`; in memory, redeemed atomically, `PendingReply` edits the reply on use/expiry). `LoginEndpoints`: `GET /dashboard/login?token=` (resetSession, redirect, `Referrer-Policy: no-referrer`) and `POST /dashboard/api/logout`. Sessions are SiggiHTTP in-memory (24 h); `DashboardSession` holds user ID, super admin flag, CSRF token, login time (super admin cut off after 8 h). `RecoveryLogin` prints a 15 min super admin link to stdout while Discord is down (hourly).
- Startup: web server and Twitch watcher start first; Discord connects on a background thread with backoff (no retry for invalid token/disallowed intents). `bot.getJDA()` is null until Discord is ready; `bot.awaitDiscordReady()` blocks until then.
- `util/` — `EndpointCaller` (HttpURLConnection wrapper), `MapBuilder`, `Util`.

MorganaBot is a **private** bot: it's in fewer than 100 servers, and that's not expected to change. It has all privileged gateway intents. Anyone else who wants it can build and run their own copy.

Twitch features are gated per guild by `enabledTwitchTracking`, which is only set by hand-editing the server JSON.

## Settings policy

Every per-server setting must be changeable from the web dashboard. Nobody should need to edit `data/servers/*.json` by hand. If you add a per-server setting, add it to the dashboard too. `config.json` (URL, port, tokens, Twitch credentials, `superAdmins`) stays file-only.

## Data compatibility

Per-server data lives in per-server files under `data/servers/`, never in a shared file. Production runs on the current `data/` format. Any change to stored data must keep old files loading, with an automatic migration if the change isn't additive. Prefer new optional fields with safe defaults. For renames or restructures, add a `dataVersion`, migrate on load, and write a `.bak` copy before the first migrated save. Server JSON files are hand-edited on production while the bot runs (e.g. `enabledTwitchTracking`), so any caching must notice those edits and reload. Details are in PLAN.md → Data compatibility.

## Repo workflow

- `CLAUDE.md` and `PLAN.md` live in `plan/`, a git worktree on the `plan` branch (separate history). The root-level files are symlinks. `plan/`, `CLAUDE.md`, `PLAN.md` are in `.git/info/exclude`.
- When a PLAN step is done:
  1. Commit the code on `main`.
  2. In `PLAN.md`, tick the step (`- [x]`) and append the main commit hash.
  3. **Also tick every Known bugs entry that says "Fixed by step N" for this step,** with the same hash.
  4. Commit in `plan/`.
  5. Push both: `git push origin main`, then `git -C plan push origin plan` (the first time: `git -C plan push -u origin plan`). The repo is public, and that's fine for the plan too.
- If a step can't be finished, leave it unchecked, add an indented note under it saying why, and commit and push that note.
- Don't sweep unrelated uncommitted changes (e.g. the user's own `pom.xml` edits) into commits. Stage specific files.

## Verifying a step (for agents running PLAN steps)

- `mvn package` must pass, and it runs the JUnit tests (set up in step 0). Add unit tests for the logic each step introduces: parsing, rules and precedence, validation, store and locking behaviour, loading old-format data. Extend the `server-v0.json` fixture test whenever a step adds stored fields.
- You may run the bot locally with the dev token in `data/config.json` (it's a developer bot account, not production). You can also:
  - send signed fake Twitch EventSub notifications to `http://localhost:<apiPort>/twitch-notification`, using `twitch.notificationSecret`
  - read the dev bot's messages back over JDA/REST to check what it posted
  - once step 5/9 exist, log into the dashboard with the console recovery login link
- You can't act as a Discord user: no running slash commands, no changing your own roles, no real Twitch go-live. Those checks are in PLAN.md's **Manual test checklist**, which is the user's job; don't try to tick it.
- Never touch production data. Use the dev `data/` directory or temporary copies.

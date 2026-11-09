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
- `config/` — `Data` base class (Gson read; save via temp file + rename). Per-guild `data/servers/<id>.json` (`ServerInfo`), per-user `data/users/<id>.json` (`UserData`), `config.json`, `auth-cache.json`. Read fresh from disk on each access, no caching/locking.
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

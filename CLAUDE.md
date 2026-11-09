# MorganaBot

Discord bot (Java 17, Maven, JDA 6) that posts in Discord channels when Twitch streamers go live, and provides `/time` + `/timezone` for generating Discord timestamp codes. See `README.md` for setup.

## Build

- `mvn package` → `target/MorganaBot.jar` (shaded). `mvn -q -o compile` for a quick check.
- No tests exist.
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

Twitch features are gated per guild by `enabledTwitchTracking`, which is only set by hand-editing the server JSON.

## Settings policy

Every per-server setting must be changeable from the web dashboard. Nobody should need to edit `data/servers/*.json` by hand. If you add a per-server setting, add it to the dashboard too. `config.json` (URL, port, tokens, Twitch credentials, `superAdmins`) stays file-only.

## Data compatibility

Production runs on the current `data/` format. Any change to stored data must keep old files loading, with an automatic migration if the change isn't additive. Prefer new optional fields with safe defaults. For renames or restructures, add a `dataVersion`, migrate on load, and write a `.bak` copy before the first migrated save. Server JSON files are hand-edited on production while the bot runs (e.g. `enabledTwitchTracking`), so any caching must notice those edits and reload. Details are in PLAN.md → Data compatibility.

## Repo workflow

- `CLAUDE.md` and `PLAN.md` live in `plan/`, a git worktree on the `plan` branch (separate history). The root-level files are symlinks. `plan/`, `CLAUDE.md`, `PLAN.md` are in `.git/info/exclude`.
- When a PLAN item is done: commit the code on `main` first, then tick it off in `PLAN.md` with the main commit hash, and commit that in `plan/`.
- Only `main` is pushed. **Never push `plan`.**
- Don't sweep unrelated uncommitted changes (e.g. the user's own `pom.xml` edits) into commits — stage specific files.

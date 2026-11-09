# MorganaBot

Discord bot (Java 17, Maven, JDA 6) that posts in Discord channels when Twitch streamers go live, and provides `/time` + `/timezone` for generating Discord timestamp codes. See `README.md` for setup.

## Build

- `mvn package` → `target/MorganaBot.jar` (shaded). `mvn -q -o compile` for a quick check.
- No tests exist.

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

## Repo workflow

- `CLAUDE.md` and `PLAN.md` live in `plan/`, a git worktree on the `plan` branch (separate history). The root-level files are symlinks. `plan/`, `CLAUDE.md`, `PLAN.md` are in `.git/info/exclude`.
- When a PLAN item is done: commit the code on `main` first, then tick it off in `PLAN.md` with the main commit hash, and commit that in `plan/`.
- Only `main` is pushed. **Never push `plan`.**
- Don't sweep unrelated uncommitted changes (e.g. the user's own `pom.xml` edits) into commits — stage specific files.

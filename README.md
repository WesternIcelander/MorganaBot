# Morgana Bot

Morgana Bot provides these features to Discord servers:
- A `/time` command that generates a time code that translates into each user's local timezone.
- Post messages in a certain channel when a Twitch streamer goes online.
- A web dashboard for changing every setting, checking each streamer's setup, and seeing what changed and what failed.

## How do I add the bot to my Discord server?

The official bot is currently not public. See **Setting up and running the bot** below for how to set up your own Morgana bot.

## Commands

### For everyone

- `/time time: [timezone:]` generates a Discord time code that shows in each reader's own timezone.
- `/timezone timezone:` sets your timezone for `/time`.
- `/streamerlist` lists the server's streamers.
- `/dashboard` sends you a login button for the web dashboard (see **Web dashboard** below).
- `/settwitchchannel twitch-name:` adds yourself as a streamer, or changes your Twitch channel (see **Self-service streamer adding**).
- `/removetwitchchannel` removes your own streamer entry.

### For server admins

These are only usable by members with Discord's **Administrator** permission by default. Server admins can change who may use them under Server Settings → Integrations.

- `/setlivechannel channel:` sets the channel live posts go to.
- `/setstreamerrole role:` sets a role that's given to streamers while they're live.
- `/setdeleteonoffline delete:` deletes the live post when the stream ends.
- `/setautopublish channel: publish:` sets whether live posts in an announcement channel are published, so servers following that channel get them too (see **Announcement channels**).
- `/setlivemessage message: [discord-name:]` sets the live message, for the whole server or for one streamer. Enter `help` as the message to see the variables: `${game_name}`, `${stream_name}`, `${streamer_name}`, `${twitch_link}` and `${discord_name}`. Use `\n` for a line break.
- `/addstreamer twitch-name: [discord-name:] [post-in-channel:]` and `/deletestreamer [twitch-name:] [discord-name:]` add and remove streamers.
- `/setchanneloverride [twitch-name:] [discord-name:] [channel:] [role-based:]` makes one streamer post in a different channel. Leave `channel` blank to remove the override. `role-based: True` puts a self-added streamer back on the channel from their role.
- `/addselfservicerole role: [channel:]`, `/removeselfservicerole role:` and `/setselfserviceexpiry days:` set up self-service adding.
- `/addadminrole role:` and `/removeadminrole role:` choose which roles get full dashboard access. Only members with Discord's Administrator permission can use these, even if the command is opened up under Integrations.
- `/addmessagerole role: [allow-everyone:] [mention-roles:]` and `/removemessagerole role:` choose which streamers may write their own live message (see **Message permissions**).

Twitch features are switched on per server by the bot's operator (a super admin, see below). Until then, the Twitch commands reply that the feature isn't enabled.

## Web dashboard

Everything the commands can change can also be changed in the web dashboard, plus a few settings that have no command (the notification cooldown, a streamer's linked Discord user). The dashboard also has:
- **Diagnose**, which checks every streamer's setup and tells you what would fail on their next go-live (missing permissions, deleted channels or roles, mentions that won't ping), without anyone having to go live.
- **Problems**, a list of live posts and role changes that actually failed, with the reason.
- **Audit**, a log of every settings change: who made it, from where (command, dashboard, self-service or the bot's operator), and the old and new value.

### Announcement channels

Live posts can go to announcement channels as well as normal text channels. In an announcement channel, MorganaBot publishes each live post by default, so servers following the channel get it too. Admins can turn this off per channel, with `/setautopublish` or in the dashboard's **Announcement channels** list, which shows every announcement channel live posts can go to and who posts there. Reposts within the notification cooldown are never published. Publishing only needs Send Messages in the channel, but Discord limits how many messages a channel can publish per hour; a publish that fails shows up under **Problems**.

### Logging in

Run `/dashboard` in a server or in a DM with the bot. It replies, only visible to you, with an **Open dashboard** button. The link works once and expires after 30 seconds, and the reply changes to "Logged in" or "Link expired" afterwards. If you have no access to any server's dashboard, you don't get a link. A login lasts 24 hours.

### Who can access it

Access is checked on every request, from your current roles. Losing a role takes effect immediately.

- **Server admins** get the full dashboard. That's anyone who:
  - is the server owner or has Discord's **Administrator** permission, or
  - has one of the server's **admin roles** (`/addadminrole`, or the dashboard).
  
  Only members with the Administrator permission can change the admin role list, so someone who's an admin only through an admin role can't add or remove admins. Admin roles don't give access to the admin commands, unless the server grants that under Integrations.
- **Streamers** who aren't admins see a **My stream** page for their own entry only: their Twitch channel, whether it's live or paused, which channel it posts in, and (if a message role allows it) their own live message. They can also set or remove their Twitch channel there, with the same rules as `/settwitchchannel`.
- **Everyone else** has no access.

### Message permissions

Admins can write any live message, for the whole server or for any streamer, with any mentions. Other streamers can only write their own message if they have a **message role** (`/addmessagerole`, or the dashboard). Each message role says whether its members may use `@everyone` and `@here`, and which roles they may mention. A streamer with several message roles gets all of their permissions combined.

- Mentions are checked when a streamer saves their message. A mention they're not allowed to use is rejected (the bot never changes the text), and the error says which one.
- A mention that's already in their saved message (or in the server-wide message, if they don't have their own yet) can be kept, even if they're not allowed to add it. Once they remove it, adding it back needs permission.
- Streamers can't add mentions of individual users. `${discord_name}` (the streamer themselves) is always allowed.
- Whatever was saved pings as written, even if the streamer later loses the message role.
- Text from Twitch (stream title, game name, streamer name) can never ping anyone, even if it contains `@everyone` or a role mention.
- A live message can be at most 2000 characters once filled in.

Mentions only work if the bot itself has the Discord permission (for example **Mention Everyone** for `@everyone`, or a mentionable role).

### Self-service streamer adding

Admins can let members add themselves as streamers, with `/addselfservicerole role: [channel:]` (or the dashboard). A member with one of those roles can then run `/settwitchchannel twitch-name:`.

- **Which channel:** each self-service role can have its own channel. If the member has several self-service roles, the one **highest in the server's role list** (Server Settings → Roles) decides, even if that role has no channel set. In that case they post in the server's default live channel. The order you added the roles in doesn't matter.
- Running `/settwitchchannel` again replaces the member's Twitch channel and resets their channel to the one their role gives. Their own live message is kept. A Twitch channel someone else in the server already added is refused. Members can run it once a minute.
- `/removetwitchchannel` removes their entry, even if they no longer have the role.
- **When their roles change,** their channel follows their highest self-service role. If an admin sets a self-added streamer's channel by hand, it stays put; `/setchanneloverride role-based: True` or the dashboard's "Go back to role-based" button undoes that.
- **If a self-added streamer loses every self-service role** (or leaves the server), their live posts pause straight away and resume if they get a role back. After `/setselfserviceexpiry days:` days without a role (7 by default, 0 means never), their entry is removed.
- Streamers added by an admin are never affected by self-service roles.

### Super admins

Super admins are the bot's operators, set with `superAdmins` in `config.json`. They can open and change every server the bot is in, turn Twitch features on or off per server, remove the bot from a server (and block it from being added back), and see the bot-wide **System** page (Discord and Twitch connection status, storage errors, the system log) and an audit log across all servers. Their changes show up in the server's audit log as "Bot admin". When something turns red on the System page, every super admin gets a DM. Super admin logins last 8 hours.

## Building the bot

Have Apache Maven installed, and then just run `mvn package` and the jar will be at `target/MorganaBot.jar`.

The dashboard frontend is TypeScript, built as part of `mvn package`. Maven downloads its own copy of Node into `target/` for this, so the first build needs network access. You don't need Node installed.

## Building a docker image for the bot

First, [build a Java image](https://github.com/WesternIcelander/DockerJava). Currently, Morgana requires Java 17. The included `Dockerfile` looks for an image called `jdk17` to use as the base.

Then you can run `docker build -t morgana .` and don't forget the . at the end! Place the `data` directory at `/data` inside the docker container, see the next section for information on the `data` directory.

## Setting up and running the bot

1. Create a directory called `data`.
2. Copy the `blank-config.json` into `data` and rename it to `config.json`.
3. Open `config.json` and edit it!
   - `apiEndpoint` is the URL your bot can be reached from the public internet without the trailing slash. The dashboard is at `<apiEndpoint>/dashboard/`.
   - `apiPort` is the port number the bot will listen on.
     - Note that the bot does not handle requests over HTTPS. You will need a reverse proxy in front of the bot for this as the Twitch API does not support sending notifications to endpoints that are not over HTTPS on port 443.
     - The reverse proxy **must send the `X-Forwarded-Proto` header** (`X-Forwarded-Proto: https`). That's how the bot knows the request came in over HTTPS, so it marks the dashboard's login cookie `Secure`. For example, in nginx: `proxy_set_header X-Forwarded-Proto $scheme;`
   - `superAdmins` is a list of Discord user IDs, written as strings, of the people who run the bot (see **Super admins** above), e.g. `"superAdmins": ["260595748465278976"]`. The first one is named as the contact in "this feature is not enabled" replies. If the list is empty, there are no super admins, and nobody can turn on Twitch features for a server from the dashboard. It's only read at startup.
   - Add your Discord bot token. (Create a bot account here: https://discord.com/developers/)
   - Add your Twitch app client ID and client secret, and a notification secret. (Create an app here: https://dev.twitch.tv/console/apps)
4. In the Discord developer console, under **Bot**, turn on the **Server Members Intent**. The bot needs it to follow role changes (for dashboard access and self-service roles), and won't connect without it.
5. Add a file `timezones.txt` in `data` with a list of olson timezones. This is used by the `/timezone` command to tell if a specified timezone is valid or not and for autocompletion.
6. Run the bot! `java -jar target/MorganaBot.jar` or ``docker run -p 8080:8080 -v `pwd`/data:/data morgana``
   - If the bot can't connect to Discord, the dashboard still runs, and the bot prints a super admin login link to the console (valid for 15 minutes, a new one every hour while Discord is down), so you can check the System page.
7. Add the bot to your server by going to the bot in your Discord developer console, and then **OAuth2** and then **URL Generator**.
   - Select the bot scope
   - The bot needs the following permissions on Discord:
     - View Channels
     - Send Messages
     - Read Message History (optional), needed if you want live posts deleted when the stream ends
     - Manage Roles (optional), needed if you want to auto add and remove a certain role for when a streamer goes live and offline
     - Mention Everyone (optional), needed if your go live messages contain @everyone.
   - Copy the Generated URL into your web browser's address bar.
8. Turn on Twitch features for the server: log in to the dashboard as a super admin, pick the server, and switch on Twitch.

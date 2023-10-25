# Morgana Bot

Morgana Bot currently provides two features to Discord servers:
- A `/time` command that generates a time code that translates into each user's local timezone.
- Post messages in a certain channel when a Twitch streamer goes online.

## How do I add the bot to my Discord server?

The official bot is currently not public. See **Setting up and running the bot** below for how to set up your own Morgana bot.

## Building the bot

Have Apache Maven installed, and then just run `mvn package` and the jar will be at `target/MorganaBot.jar`.

## Building a docker image for the bot

First, [build a Java image](https://github.com/WesternIcelander/DockerJava). Currently, Morgana requires Java 17. The included `Dockerfile` looks for an image called `jdk17` to use as the base.

Then you can run `docker build -t morgana .` and don't forget the . at the end! Place the `data` directory at `/data` inside the docker container, see the next section for information on the `data` directory.

## Setting up and running the bot

1. Create a directory called `data`.
2. Copy the `blank-config.json` into `data` and rename it to `config.json`.
3. Open `config.json` and edit it!
   - `apiEndpoint` is the URL your bot can be reached from the public internet without the trailing slash.
   - `apiPort` is the port number the bot will listen on.
     - Note that the bot does not handle requests over HTTPS. You will need a reverse proxy in front of the bot for this as the Twitch API does not support sending notifications to endpoints that are not over HTTPS on port 443.
   - Add your Discord bot token. (Create a bot account here: https://discord.com/developers/)
   - Add your Twitch app client ID and client secret, and a notification secret. (Create an app here: https://dev.twitch.tv/console/apps)
4. Add a file `timezones.txt` in `data` with a list of olson timezones. This is used by the `/timezone` command to tell if a specified timezone is valid or not and for autocompletion.
5. Run the bot! `java -jar target/MorganaBot.jar` or ``docker run -p 8080:8080 -v `pwd`/data:/data morgana``
6. Add the bot to your server by going to the bot in your Discord developer console, and then **OAuth2** and then **URL Generator**.
   - Select the bot scope
   - The bot needs the following permissions on Discord:
     - View Channels
     - Send Messages
     - Manage Roles (optional), needed if you want to auto add and remove a certain role for when a streamer goes live and offline
     - Mention Everyone (optional), needed if your go live messages contain @everyone.
   - Copy the Generated URL into your web browser's address bar.

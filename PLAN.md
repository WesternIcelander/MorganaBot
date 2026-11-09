# Plan

## Done

- [x] Remove unimplemented `/letmein` (Minecraft whitelist) command and `MinecraftHandler` — `35b85f0`

## Known bugs / candidates

- [ ] `getStreamer` (`TwitchCommandHandler`) matches the wrong streamer when only a Twitch name is given: `discordUserId` is 0, so any streamer with `discordId == 0` matches first. `/deletestreamer twitch-name:X` can delete someone else.
- [ ] `/addstreamer` with a nonexistent Twitch name NPEs (`getStreamerInfoByUsername` returns null) → generic error instead of a helpful message.
- [ ] Expired Twitch app token isn't detected in the notification path: `getAuthToken` never checks `twitchAppAccessTokenExpiry`, and failures in `handleNotification` are swallowed, so live posts silently fail until the hourly loop refreshes the token.
- [ ] `${twitch_link}` and `/streamerlist` links use display name instead of login name — breaks for non-Latin display names.
- [ ] `/setchanneloverride`'s `channel` option is `OptionType.STRING` rather than `CHANNEL`; that's why `getChannelId` has the "JDA bug" `<#…>` stripping workaround.
- [ ] Live role add/remove is attempted for streamers with no linked Discord user (`discordId == 0`).

## Notes

- JDA bumped 6.5.0 → 6.7.0 — `c1bf04e`

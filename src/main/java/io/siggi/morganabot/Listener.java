package io.siggi.morganabot;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class Listener extends ListenerAdapter {
    private final MorganaBot bot;

    public Listener(MorganaBot bot) {
        this.bot = bot;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        try {
            switch (event.getName()) {
                case "timezone": {
                    TimeHandler.setTimezone(bot, event);
                }
                break;
                case "time": {
                    TimeHandler.generateTime(bot, event);
                }
                break;
                case "letmein": {
                    MinecraftHandler.minecraftWhitelist(bot, event);
                }
                break;
                case "addstreamer": {
                    TwitchCommandHandler.addStreamer(bot, event);
                }
                break;
                case "deletestreamer": {
                    TwitchCommandHandler.deleteStreamer(bot, event);
                }
                break;
                case "streamerlist": {
                    TwitchCommandHandler.getStreamerList(bot, event);
                }
                break;
                case "setstreamerrole": {
                    TwitchCommandHandler.setStreamerRole(bot, event);
                }
                break;
                case "setlivechannel": {
                    TwitchCommandHandler.setLiveChannel(bot, event);
                }
                break;
                case "setchanneloverride": {
                    TwitchCommandHandler.setChannelOverride(bot, event);
                }
                break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("There was a problem executing that command").setEphemeral(true).queue();
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        String name = event.getName();
        if (name.equals("timezone")) {
            TimeHandler.autocompleteTimezone(event);
        }
    }
}

package io.siggi.morganabot;

import io.siggi.morganabot.health.Health;
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
                case "setdeleteonoffline": {
                    TwitchCommandHandler.setDeleteOnOffline(bot, event);
                }
                break;
                case "setlivemessage": {
                    TwitchCommandHandler.setLiveMessage(bot, event);
                }
                break;
                case "setchanneloverride": {
                    TwitchCommandHandler.setChannelOverride(bot, event);
                }
                break;
                case "settwitchchannel": {
                    TwitchCommandHandler.selfSetTwitch(bot, event);
                }
                break;
                case "removetwitchchannel": {
                    TwitchCommandHandler.selfRemove(bot, event);
                }
                break;
                case "addselfservicerole": {
                    TwitchCommandHandler.addSelfServiceRole(bot, event);
                }
                break;
                case "removeselfservicerole": {
                    TwitchCommandHandler.removeSelfServiceRole(bot, event);
                }
                break;
                case "setselfserviceexpiry": {
                    TwitchCommandHandler.setSelfServiceExpiry(bot, event);
                }
                break;
            }
        } catch (Exception e) {
            Health.unexpected("Command /" + event.getName() + " failed", e);
            event.reply("There was a problem executing that command").setEphemeral(true).queue();
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        String name = event.getName();
        if (name.equals("time") || name.equals("timezone")) {
            TimeHandler.autocompleteTimezone(event);
        }
    }
}

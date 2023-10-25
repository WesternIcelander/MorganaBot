package io.siggi.morganabot;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

public class MinecraftHandler {

    static void minecraftWhitelist(MorganaBot bot, SlashCommandInteractionEvent event) {
        OptionMapping usernameMapping = event.getInteraction().getOption("username");
        String username = usernameMapping == null ? null : usernameMapping.getAsString();
        OptionMapping crackedMapping = event.getInteraction().getOption("cracked");
        boolean cracked = crackedMapping != null && crackedMapping.getAsBoolean();
        event.reply("This command is not yet implemented, check back Soon:tm:!").setEphemeral(true).queue();
    }
}

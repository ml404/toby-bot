package toby.menu;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import toby.command.commands.ICommandContext;

public class MenuContext implements ICommandContext {

    IReplyCallback interaction;

    public MenuContext(IReplyCallback interaction) {
        this.interaction = interaction;
    }

    @Override
    public Guild getGuild() {
        return this.getEvent().getGuild();
    }

    @Override
    public SlashCommandInteractionEvent getEvent() {
        return (SlashCommandInteractionEvent) this.interaction;
    }

    public StringSelectInteractionEvent getStringSelectInteractionEvent(){
        return (StringSelectInteractionEvent) this.interaction;
    }

}

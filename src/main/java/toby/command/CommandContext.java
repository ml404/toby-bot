package toby.command;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import toby.command.commands.ICommandContext;

public class CommandContext implements ICommandContext {
    IReplyCallback interaction;

    public CommandContext(IReplyCallback interaction) {
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

    public ButtonInteractionEvent getButtonInteractionEvent() {
        return (ButtonInteractionEvent) this.interaction;
    }
}

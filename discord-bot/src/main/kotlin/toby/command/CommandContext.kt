package toby.command

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import toby.command.commands.ICommandContext

class CommandContext(var interaction: IReplyCallback) : ICommandContext {
    override val guild: Guild get() = event.guild!!
    override val event: SlashCommandInteractionEvent get() = interaction as SlashCommandInteractionEvent
}

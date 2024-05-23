package toby.menu

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import toby.command.commands.ICommandContext

class MenuContext(var interaction: IReplyCallback) : ICommandContext {
    override val guild: Guild get() = event.guild!!
    override val event: SlashCommandInteractionEvent get() = interaction as SlashCommandInteractionEvent
    val selectEvent: StringSelectInteractionEvent get() = interaction as StringSelectInteractionEvent
}

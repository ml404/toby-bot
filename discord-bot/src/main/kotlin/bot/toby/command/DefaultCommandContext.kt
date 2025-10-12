package bot.toby.command

import core.command.CommandContext
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback

class DefaultCommandContext(var interaction: IReplyCallback) : CommandContext {
    override val guild: Guild get() = event.guild!!
    override val event: SlashCommandInteractionEvent get() = interaction as SlashCommandInteractionEvent
}

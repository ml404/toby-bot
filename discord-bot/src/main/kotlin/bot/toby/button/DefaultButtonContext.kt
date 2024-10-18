package bot.toby.button

import core.button.ButtonContext
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback

class DefaultButtonContext(var interaction: IReplyCallback) : ButtonContext {
    override val guild: Guild get() = event.guild!!
    override val event: ButtonInteractionEvent get() = interaction as ButtonInteractionEvent
}
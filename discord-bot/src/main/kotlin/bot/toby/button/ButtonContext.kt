package bot.toby.button

import bot.toby.button.buttons.IButtonContext
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback

class ButtonContext(var interaction: IReplyCallback) : IButtonContext {
    override val guild: Guild get() = event.guild!!
    override val event: ButtonInteractionEvent get() = interaction as ButtonInteractionEvent
}
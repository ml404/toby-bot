package bot.toby.modal

import core.modal.ModalContext
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback

class DefaultModalContext(private val interaction: IReplyCallback) : ModalContext {
    override val guild: Guild get() = event.guild!!
    override val event: ModalInteractionEvent get() = interaction as ModalInteractionEvent
    override val member: Member? get() = event.member
}

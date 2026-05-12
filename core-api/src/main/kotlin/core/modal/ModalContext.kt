package core.modal

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent

interface ModalContext {
    val guild: Guild
    val event: ModalInteractionEvent
    val member: Member?
}

package core.managers

import core.modal.Modal
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent

interface ModalManager {

    val modals: List<Modal>

    fun getModal(search: String): Modal? = modals.find { it.name.equals(search, true) }

    fun handle(event: ModalInteractionEvent)
}

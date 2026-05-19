package core.managers

import core.modal.Modal
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent

interface ModalManager : NamedRegistry<Modal> {

    val modals: List<Modal>
    override val items: List<Modal> get() = modals

    fun getModal(search: String): Modal? = findByName(search)

    fun handle(event: ModalInteractionEvent)
}

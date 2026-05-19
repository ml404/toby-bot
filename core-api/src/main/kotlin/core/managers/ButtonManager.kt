package core.managers

import core.button.Button
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

interface ButtonManager : NamedRegistry<Button> {

    val buttons: List<Button>
    override val items: List<Button> get() = buttons

    fun getButton(search: String): Button? = findByName(search)

    fun handle(event: ButtonInteractionEvent)
}

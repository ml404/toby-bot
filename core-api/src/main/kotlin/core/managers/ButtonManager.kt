package core.managers

import core.button.Button
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

interface ButtonManager {

    val buttons: List<Button>

    fun getButton(search: String): Button? = buttons.find { it.name.equals(search, true) }

    fun handle(event: ButtonInteractionEvent)
}

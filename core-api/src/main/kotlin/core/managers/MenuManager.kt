package core.managers

import core.menu.Menu
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent

interface MenuManager {

    val menus: List<Menu>

    fun getMenu(search: String): Menu? = menus.find { it.name.equals(search, true) }

    fun handle(event: StringSelectInteractionEvent)
}

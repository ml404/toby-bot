package core.managers

import core.menu.Menu
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent

interface MenuManager : NamedRegistry<Menu> {

    val menus: List<Menu>
    override val items: List<Menu> get() = menus

    fun getMenu(search: String): Menu? = findByName(search)

    fun handle(event: StringSelectInteractionEvent)
}

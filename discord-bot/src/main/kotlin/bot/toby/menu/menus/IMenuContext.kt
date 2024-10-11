package bot.toby.menu.menus

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent

interface IMenuContext {
    val guild: Guild
    val event: StringSelectInteractionEvent

}
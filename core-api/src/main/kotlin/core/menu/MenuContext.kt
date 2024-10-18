package core.menu

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent

interface MenuContext {
    val guild: Guild
    val event: StringSelectInteractionEvent
    val member: Member?
}
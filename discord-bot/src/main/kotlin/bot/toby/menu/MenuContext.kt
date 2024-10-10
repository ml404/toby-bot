package bot.toby.menu

import bot.toby.menu.menus.IMenuContext
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback

class MenuContext(var interaction: IReplyCallback) : IMenuContext {
    override val guild: Guild get() = event.guild!!
    override val event: StringSelectInteractionEvent get() = interaction as StringSelectInteractionEvent
    val member: Member? get() = event.member
}

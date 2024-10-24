package bot.toby.menu

import core.menu.MenuContext
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback

class DefaultMenuContext(var interaction: IReplyCallback) : MenuContext {
    override val guild: Guild get() = event.guild!!
    override val event: StringSelectInteractionEvent get() = interaction as StringSelectInteractionEvent
    override val member: Member? get() = event.member
}

package bot.toby.button.buttons

import bot.toby.button.ButtonContext
import bot.toby.button.IButton
import bot.toby.helpers.DnDHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class InitiativeNextButton @Autowired constructor(private val dndHelper: DnDHelper) : IButton {
    override val name: String
        get() = "init:next"
    override val description: String
        get() = "Move the initiative table onto the next member"

    override fun handle(ctx: ButtonContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val hook = event.hook
        dndHelper.incrementTurnTable(hook, event, deleteDelay)
    }
}
package bot.toby.button.buttons

import bot.toby.helpers.DnDHelper
import core.button.Button
import core.button.ButtonContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class InitiativePreviousButton @Autowired constructor(private val dndHelper: DnDHelper) : Button {
    override val name: String
        get() = "init:prev"
    override val description: String
        get() = "Move the initiative table back a turn"

    override fun handle(ctx: ButtonContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val hook = event.hook
        dndHelper.decrementTurnTable(hook, event, deleteDelay)
    }
}
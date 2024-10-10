package bot.toby.button.buttons

import bot.toby.button.ButtonContext
import bot.toby.button.IButton
import bot.toby.helpers.DnDHelper
import database.dto.UserDto

class InitiativePreviousButton(private val dndHelper: DnDHelper): IButton {
    override val name: String
        get() = "init:prev"
    override val description: String
        get() = "Move the initiative table back a turn"

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val hook = event.hook
        dndHelper.decrementTurnTable(hook, event, deleteDelay)
    }
}
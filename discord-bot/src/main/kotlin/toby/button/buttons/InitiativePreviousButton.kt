package toby.button.buttons

import database.dto.UserDto
import toby.button.ButtonContext
import toby.button.IButton
import toby.helpers.DnDHelper

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
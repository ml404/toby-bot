package toby.button.buttons

import toby.button.ButtonContext
import toby.button.IButton
import toby.helpers.DnDHelper
import toby.jpa.dto.UserDto

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
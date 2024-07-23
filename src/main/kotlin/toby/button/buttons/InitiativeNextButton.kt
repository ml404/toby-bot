package toby.button.buttons

import toby.button.ButtonContext
import toby.button.IButton
import toby.helpers.DnDHelper
import toby.jpa.dto.UserDto

class InitiativeNextButton : IButton {
    override val name: String
        get() = "init:next"
    override val description: String
        get() = "Move the initiative table onto the next member"

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val hook = event.hook
        DnDHelper.incrementTurnTable(hook, event, deleteDelay)
    }
}
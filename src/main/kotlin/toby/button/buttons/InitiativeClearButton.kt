package toby.button.buttons

import toby.button.ButtonContext
import toby.button.IButton
import toby.helpers.DnDHelper
import toby.jpa.dto.UserDto

class InitiativeClearButton: IButton {
    override val name: String
        get() = "init:clear"
    override val description: String
        get() = "Clear and delete the initiative table"

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val hook = ctx.event.hook
        DnDHelper.clearInitiative(hook, event)

    }
}
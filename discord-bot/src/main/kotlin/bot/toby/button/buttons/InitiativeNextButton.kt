package bot.toby.button.buttons

import bot.toby.button.ButtonContext
import bot.toby.button.IButton
import bot.toby.helpers.DnDHelper
import database.dto.UserDto

class InitiativeNextButton(private val dndHelper: DnDHelper) : IButton {
    override val name: String
        get() = "init:next"
    override val description: String
        get() = "Move the initiative table onto the next member"

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val hook = event.hook
        dndHelper.incrementTurnTable(hook, event, deleteDelay)
    }
}
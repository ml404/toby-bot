package bot.toby.button.buttons

import bot.toby.button.ButtonContext
import bot.toby.button.IButton
import bot.toby.helpers.DnDHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class InitiativeClearButton @Autowired constructor(private val dnDHelper: DnDHelper) : IButton {
    override val name: String
        get() = "init:clear"
    override val description: String
        get() = "Clear and delete the initiative table"

    override fun handle(ctx: ButtonContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val hook = ctx.event.hook
        dnDHelper.clearInitiative(hook, event)
    }
}
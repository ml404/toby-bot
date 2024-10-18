package bot.toby.button.buttons

import bot.toby.button.ButtonContext
import bot.toby.button.IButton
import bot.toby.managers.CommandManagerImpl
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class ResendLastRequestButton @Autowired constructor(private val commandManager: CommandManagerImpl) : IButton {
    override val name: String
        get() = "resend_last_request"
    override val description: String
        get() = "Resend the last send request"

    override fun handle(ctx: ButtonContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val (cmd, cmdCtx) = commandManager.lastCommands[event.guild] ?: return
        cmd.handle(cmdCtx, requestingUserDto, deleteDelay)
        event.deferEdit().queue()
    }
}
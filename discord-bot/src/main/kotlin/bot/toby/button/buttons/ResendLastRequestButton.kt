package bot.toby.button.buttons

import database.dto.UserDto
import bot.toby.button.ButtonContext
import bot.toby.button.IButton
import bot.toby.managers.CommandManager

class ResendLastRequestButton(private val commandManager: CommandManager) : IButton {
    override val name: String
        get() = "resend_last_request"
    override val description: String
        get() = "Resend the last send request"

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val (cmd, cmdCtx) = commandManager.lastCommands[event.guild] ?: return
            cmd.handle(cmdCtx, requestingUserDto, deleteDelay)
            event.deferEdit().queue()
    }
}
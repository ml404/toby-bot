package toby.button.buttons

import toby.button.ButtonContext
import toby.button.IButton
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.command.commands.dnd.RollCommand
import toby.jpa.dto.UserDto
import toby.managers.CommandManager
import java.util.*

class RollButton(private val commandManager: CommandManager) : IButton {
    override val name: String
        get() = "roll"
    override val description: String
        get() = "Button used to roll dice"

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val componentId = event.componentId

        val (commandName, options) = componentId.split(":").takeIf { it.size == 2 } ?: return
        val cmd = commandManager.getCommand(commandName.lowercase(Locale.getDefault())) ?: return

        event.channel.sendTyping().queue()
        if (cmd.name == "roll") {
            val rollCommand = cmd as? RollCommand ?: return
            val optionArray = options.split(",").mapNotNull { it.toIntOrNull() }.toTypedArray()
            if (optionArray.size == 3) {
                rollCommand.handleDiceRoll(
                    event,
                    optionArray[0],
                    optionArray[1],
                    optionArray[2]
                ).queue { invokeDeleteOnMessageResponse(deleteDelay ?: 0) }
            }
        }
    }
}
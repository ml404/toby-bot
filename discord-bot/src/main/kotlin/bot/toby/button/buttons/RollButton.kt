package bot.toby.button.buttons

import bot.toby.button.ButtonContext
import bot.toby.button.IButton
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import bot.toby.command.commands.dnd.RollCommand
import bot.toby.managers.CommandManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.*

@Component
class RollButton @Autowired constructor(private val commandManager: CommandManager) : IButton {
    override val name: String
        get() = "roll"
    override val description: String
        get() = "Button used to roll dice"

    override fun handle(ctx: ButtonContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
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
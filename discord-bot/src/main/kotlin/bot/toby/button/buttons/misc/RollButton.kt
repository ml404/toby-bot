package bot.toby.button.buttons.misc

import bot.toby.command.commands.dnd.RollCommand
import core.button.Button
import core.button.ButtonContext
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.managers.CommandManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.*

@Component
class RollButton @Autowired constructor(private val commandManager: CommandManager) : Button {
    override val name: String
        get() = "roll"
    override val description: String
        get() = "Button used to roll dice"

    // Defer here (non-ephemeral) so reroll results land as a public
    // message. The manager's default ephemeral auto-defer would have
    // hidden them.
    override val defersReply: Boolean get() = false

    override fun handle(ctx: ButtonContext, requestingUserDto: database.dto.user.UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()
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
                ).queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
        }
    }
}
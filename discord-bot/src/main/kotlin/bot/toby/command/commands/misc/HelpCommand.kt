package bot.toby.command.commands.misc

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class HelpCommand @Autowired constructor(private val commands: List<core.command.Command>) : MiscCommand {
    private val COMMAND = "command"
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val args = ctx.event.options
        val event = ctx.event
        event.deferReply(true).queue()
        // If no specific command is provided, direct users to the general wiki page
        if (args.isEmpty()) {
            val helpMessage =
                "For a list of all available commands, visit the [Toby Bot Commands Wiki](https://github.com/ml404/toby-bot/wiki/Commands)"
            event.hook.sendMessage(helpMessage).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        val searchOptional = event.getOption(COMMAND)?.asString
        val command = getCommand(searchOptional!!)
        if (command == null) {
            event.hook.sendMessage("Nothing found for command '$searchOptional'").queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        event.hook.sendMessage(command.description).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun getCommand(searchOptional: String) = commands.find { it.name.lowercase() == searchOptional }

    override val name: String
        get() = "help"
    override val description: String
        get() = "get help with the command you give this command"
    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.STRING, COMMAND, "Command you would like help with", false, true))
}

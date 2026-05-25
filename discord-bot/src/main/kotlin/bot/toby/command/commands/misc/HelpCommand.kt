package bot.toby.command.commands.misc

import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class HelpCommand @Autowired constructor(private val commands: List<core.command.Command>) : MiscCommand {
    companion object {
        private const val COMMAND = "command"
    }
    override val ephemeral: Boolean = true

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val args = ctx.event.options
        val event = ctx.event
        // If no specific command is provided, direct users to the general wiki page
        if (args.isEmpty()) {
            val helpMessage =
                "For a list of all available commands, visit the [Toby Bot Commands Wiki](https://github.com/ml404/toby-bot/wiki/Commands).\n" +
                "Enjoying the bot? You can support development on [Ko-fi](https://ko-fi.com/fratlayton)."
            event.hook.replyAndDelete(helpMessage, deleteDelay)
            return
        }

        val searchOptional = event.getOption(COMMAND)?.asString
        val command = getCommand(searchOptional!!)
        if (command == null) {
            event.hook.replyAndDelete("Nothing found for command '$searchOptional'", deleteDelay)
            return
        }
        event.hook.replyEphemeralAndDelete(command.description, deleteDelay)
    }

    private fun getCommand(searchOptional: String) = commands.find { it.name.lowercase() == searchOptional }

    override val name: String
        get() = "help"
    override val description: String
        get() = "get help with the command you give this command"
    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.STRING, COMMAND, "Command you would like help with", false, true))
}

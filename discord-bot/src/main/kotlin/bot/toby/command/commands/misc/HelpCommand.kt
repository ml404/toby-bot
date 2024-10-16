package bot.toby.command.commands.misc

import bot.toby.command.CommandContext
import bot.toby.command.ICommand
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import bot.toby.managers.CommandManager
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.function.Consumer

@Component
class HelpCommand @Autowired constructor(private val manager: CommandManager) : IMiscCommand {
    private val COMMAND = "command"
    override fun handle(ctx: CommandContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        val args = ctx.event.options
        val event = ctx.event
        event.deferReply(true).queue()
        if (args.isEmpty()) {
            val builder = StringBuilder()
            val commandConsumer = Consumer { command: ICommand -> builder.append("`/${command.name}` \n") }
            builder.append(String.format("List of all current commands below. If you want to find out how to use one of the commands try doing `%shelp commandName`\n", "/"))
            builder.append("**Music Commands**:\n")
            manager.musicCommands.forEach(commandConsumer)
            builder.append("**DnD Commands**:\n")
            manager.dndCommands.forEach(commandConsumer)
            builder.append("**Miscellaneous Commands**:\n")
            manager.miscCommands.forEach(commandConsumer)
            builder.append("**Moderation Commands**:\n")
            manager.moderationCommands.forEach(commandConsumer)
            builder.append("**Fetch Commands**:\n")
            manager.fetchCommands.forEach(commandConsumer)
            event.hook.sendMessage(builder.toString()).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        val searchOptional = event.getOption(COMMAND)?.asString
        val command = manager.getCommand(searchOptional!!)
        if (command == null) {
            event.hook.sendMessage("Nothing found for command '$searchOptional'").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        event.hook.sendMessage(command.description).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    override val name: String
        get() = "help"
    override val description: String
        get() = "get help with the command you give this command"
    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.STRING, COMMAND, "Command you would like help with", false, true))
}

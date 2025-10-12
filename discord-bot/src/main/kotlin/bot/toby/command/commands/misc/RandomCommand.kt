package bot.toby.command.commands.misc

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component

@Component
class RandomCommand : MiscCommand {
    private val LIST = "list"
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()
        if (event.options.isEmpty()) {
            event.hook.sendMessage(description).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val stringList = event.getOption(LIST)?.asString?.split(",")?.dropLastWhile { it.isEmpty() }?.toList()
        event.hook.sendMessage(stringList?.random() ?: "").queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    override val name: String
        get() = "random"
    override val description: String
        get() = "Return one item from a list you provide with options separated by commas."
    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(
                OptionType.STRING,
                LIST,
                "List of elements you want to pick a random value from",
                true
            )
        )
}

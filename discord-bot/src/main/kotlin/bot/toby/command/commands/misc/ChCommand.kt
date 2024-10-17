package bot.toby.command.commands.misc

import bot.toby.command.CommandContext
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component
import java.util.*

@Component
class ChCommand : IMiscCommand {
    override val name: String = "ch"
    override val description: String = "Allow me to 'ch' whatever you type."
    private val MESSAGE = "message"

    override fun handle(ctx: CommandContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()

        val newMessage = (event.getOption(MESSAGE)?.asString ?: "").split(" ").joinToString(" ") { word ->
            val vowelIndex = word.indexOfFirst { isVowel(it.toString()) }
            if (word.substring(vowelIndex).lowercase(Locale.getDefault()).startsWith("ink")) {
                "gamerword"
            } else {
                "ch" + word.substring(vowelIndex).lowercase(Locale.getDefault())
            }
        }

        event.hook.sendMessage("Oh! I think you mean: '$newMessage'").queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
    }

    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.STRING, MESSAGE, "Message to 'Ch'", true))

    private fun isVowel(s: String): Boolean {
        val vowels = listOf("a", "e", "i", "o", "u")
        return vowels.contains(s.lowercase(Locale.getDefault()))
    }
}
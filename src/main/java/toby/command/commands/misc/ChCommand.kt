package toby.command.commands.misc

import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.UserDto
import java.util.*
import java.util.stream.Collectors

class ChCommand : IMiscCommand {
    private val MESSAGE = "message"
    override fun handle(ctx: CommandContext?, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx!!.event
        event.deferReply().queue()
        val message = Optional.ofNullable(event.getOption(MESSAGE)).map { obj: OptionMapping -> obj.asString }.orElse("")
        val newMessage = Arrays.stream(message.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()).map { s: String ->
            var vowelIndex = 0
            for (i in s.indices) {
                val c = s[i]
                if (isVowel(c.toString())) {
                    vowelIndex = i
                    break
                }
            }
            if (s.substring(vowelIndex).lowercase(Locale.getDefault()).startsWith("ink")) {
                return@map "gamerword"
            } else return@map "ch" + s.substring(vowelIndex).lowercase(Locale.getDefault())
        }.collect(Collectors.joining(" "))
        event.hook.sendMessage("Oh! I think you mean: '$newMessage'").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    override val name: String get() = "ch"
    override val description: String get() = "Allow me to 'ch' whatever you type."

    private fun isVowel(s: String): Boolean {
        val vowels = listOf("a", "e", "i", "o", "u")
        return vowels.contains(s.lowercase(Locale.getDefault()))
    }

    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.STRING, MESSAGE, "Message to 'Ch'", true))
}
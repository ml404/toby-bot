package toby.command.commands.moderation

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.emote.Emotes
import toby.jpa.dto.UserDto
import java.util.*

class PollCommand : IModerationCommand {
    override fun handle(ctx: CommandContext?, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx!!.event
        val hook = event.hook
        event.deferReply().queue()
        val choiceOptional = Optional.ofNullable(event.getOption(CHOICES)).map { obj: OptionMapping -> obj.asString }
        if (choiceOptional.isPresent) {
            val question = Optional.ofNullable(event.getOption(QUESTION)).map { obj: OptionMapping -> obj.asString }.orElse("Poll")
            val pollArgs = choiceOptional.map { s: String -> listOf(*s.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) }.orElse(emptyList())
            if (pollArgs.size > 10) {
                hook.sendMessageFormat("Please keep the poll size under 10 items, or else %s.", event.guild!!.jda.getEmojiById(Emotes.TOBY)).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return
            }
            val emojiList = listOf("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟")
            val poll = EmbedBuilder().setTitle(question).setAuthor(ctx.author!!.getEffectiveName()).setFooter("Please react to this poll with the emoji that aligns with the option you want to vote for")
            for (i in pollArgs.indices) {
                poll.appendDescription(String.format("%s - **%s** \n", emojiList[i], pollArgs[i].trim { it <= ' ' }))
            }
            event.channel.sendMessageEmbeds(poll.build()).queue { message: Message ->
                for (i in pollArgs.indices) {
                    message.addReaction(Emoji.fromUnicode(emojiList[i])).queue()
                }
            }
        } else {
            hook.sendMessage(description).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        }
    }

    override val name: String
        get() = "poll"
    override val description: String
        get() = "Start a poll for every user in the server who has read permission in the channel you're posting to"
    override val optionData: List<OptionData>
        get() {
            val question = OptionData(OptionType.STRING, QUESTION, "Question for the poll", true)
            val choices = OptionData(OptionType.STRING, CHOICES, "Comma delimited list of answers for the poll", true)
            return listOf(question, choices)
        }

    companion object {
        const val QUESTION = "question"
        const val CHOICES = "choices"
    }
}

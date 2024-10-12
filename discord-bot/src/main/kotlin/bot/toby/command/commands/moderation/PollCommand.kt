package bot.toby.command.commands.moderation

import bot.toby.command.CommandContext
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import bot.toby.emote.Emotes
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData

class PollCommand : IModerationCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        val hook = event.hook

        val choiceOption = event.getOption(CHOICES)?.asString
        if (!choiceOption.isNullOrEmpty()) {
            val question = event.getOption(QUESTION)?.asString ?: "Poll"
            val pollArgs = choiceOption.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            if (pollArgs.size > 10) {
                hook.sendMessageFormat("Please keep the poll size under 10 items, or else %s.", event.guild!!.jda.getEmojiById(Emotes.TOBY))
                    .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
                return
            }

            val emojiList = listOf("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟")
            val poll = EmbedBuilder()
                .setTitle(question)
                .setAuthor(ctx.author?.effectiveName)
                .setFooter("Please react to this poll with the emoji that aligns with the option you want to vote for")

            for ((index, option) in pollArgs.withIndex()) {
                poll.appendDescription("${emojiList[index]} - **$option** \n")
            }

            hook.sendMessageEmbeds(poll.build()).queue { message ->
                for (i in pollArgs.indices) {
                    message.addReaction(Emoji.fromUnicode(emojiList[i])).queue()
                }
            }
        } else {
            hook.sendMessage(description).queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
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

package bot.toby.modal.modals

import core.modal.Modal
import core.modal.ModalContext
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.emoji.Emoji
import org.springframework.stereotype.Component

/**
 * Submission handler for the multi-option poll modal opened by
 * `/poll`. The slash command used to take a comma-delimited `choices`
 * option — fragile (forgotten delimiters, awkward quoting) and capped
 * at Discord's slash-option length. The modal replaces that with up
 * to ten per-option fields, all but the first optional, plus the
 * question itself as a paragraph input.
 *
 * The poll embed is sent to the channel (visible to everyone) while
 * the ephemeral interaction reply confirms back to the poll author
 * — keeps the channel feed clean.
 */
@Component
class PollModal : Modal {
    override val name = MODAL_NAME

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val question = event.getValue(FIELD_QUESTION)?.asString?.trim().orEmpty().ifEmpty { "Poll" }
        val options = (1..MAX_OPTIONS).mapNotNull { idx ->
            event.getValue("$FIELD_OPTION_PREFIX$idx")?.asString?.trim()?.takeIf { it.isNotEmpty() }
        }

        if (options.isEmpty()) {
            event.hook.sendMessage("Add at least one option to the poll.").setEphemeral(true).queue()
            return
        }

        val authorName = event.member?.effectiveName ?: event.user.effectiveName
        val embed = EmbedBuilder()
            .setTitle(question)
            .setAuthor(authorName)
            .setFooter("Please react to this poll with the emoji that aligns with the option you want to vote for")

        options.forEachIndexed { index, option ->
            embed.appendDescription("${EMOJI_LIST[index]} - **$option** \n")
        }

        event.channel.sendMessageEmbeds(embed.build()).queue { message ->
            options.indices.forEach { i ->
                message.addReaction(Emoji.fromUnicode(EMOJI_LIST[i])).queue()
            }
        }

        event.hook.sendMessage("Poll posted.").setEphemeral(true).queue()
    }

    companion object {
        const val MODAL_NAME = "poll"
        const val FIELD_QUESTION = "poll_question"
        const val FIELD_OPTION_PREFIX = "option_"

        // Discord caps modals at 5 components. With 1 paragraph for the
        // question, that leaves 4 single-line option fields.
        const val MAX_OPTIONS = 4

        val EMOJI_LIST: List<String> = listOf("1️⃣", "2️⃣", "3️⃣", "4️⃣")
    }
}

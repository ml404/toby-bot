package bot.toby.command.commands.economy

import bot.toby.modal.modals.TipMessageModal
import core.command.MessageContextCommand
import database.dto.user.UserDto
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import org.springframework.stereotype.Component

/**
 * Right-click a message → **Apps → Tip this**.
 *
 * A tip is always a reaction to a specific thing somebody said, and `/tip user:
 * amount:` asked for the one part the moment already knew — who — while the
 * thing being rewarded scrolled away. This takes the recipient off the message
 * and carries a link back to it into the announcement, so the channel sees what
 * the credit was for rather than just that some moved.
 *
 * That link is the reason this is not merely a shortcut for the slash command.
 * Nothing else can say what a tip was for.
 *
 * The three refusals are made here rather than left to the service so the form
 * never opens onto a tip that cannot land — a modal that takes an amount and
 * then rejects it is a worse answer than a sentence.
 */
@Component
class TipMessageContextCommand : MessageContextCommand {

    override val name: String get() = COMMAND_NAME

    /** Opens a modal, which has to be the interaction's first response. */
    override val defersReply: Boolean = false

    override fun handle(event: MessageContextInteractionEvent, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(event.guild, event.member)

        // The manager drops guild-less interactions before dispatch, so the
        // message here always has a channel the jump link can point into.
        val message = event.target
        val author = message.author

        if (author.isBot) return refuse(event, "You can't tip a bot.")
        if (author.idLong == requestingUserDto.discordId) {
            return refuse(event, "That's your own message — you can't tip yourself.")
        }

        logger.info { "Opening tip form for ${author.idLong} from message ${message.idLong}" }

        event.replyModal(
            TipMessageModal.amountForm(
                recipientDiscordId = author.idLong,
                recipientName = message.member?.effectiveName ?: author.effectiveName,
                channelId = message.channel.idLong,
                messageId = message.idLong,
            )
        ).queue()
    }

    /** Not deferred, so this answers the interaction directly. */
    private fun refuse(event: MessageContextInteractionEvent, message: String) {
        event.reply(message).setEphemeral(true).queue()
    }

    companion object {
        /** Shown verbatim in Discord's Apps menu; max 32 characters. */
        const val COMMAND_NAME = "Tip this"
    }
}

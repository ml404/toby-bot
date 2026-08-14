package bot.toby.button.buttons.economy

import bot.toby.modal.modals.TipMessageModal
import core.button.Button
import core.button.ButtonContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Member
import org.springframework.stereotype.Component
import net.dv8tion.jda.api.components.buttons.Button as JdaButton

/**
 * Opens the tip form for a member, from wherever their name already appears.
 *
 * The paying half of the profile card. Seeing what somebody's balance and title
 * are is the most natural moment to add to it, and the alternative was leaving
 * the card to type `/tip user: amount:` with their name again.
 *
 * Unlike the slash command the amount is not known yet, so the form asks for
 * it — see [TipMessageModal.amountForm]. Everything after submission is the
 * shared path: same service, same daily cap, same audit log.
 */
@Component
class TipUserButton : Button {

    override val name: String = BUTTON_NAME
    override val description: String = "Tip a member some social credit."

    /** Opens a modal, which has to be the interaction's first response. */
    override val defersReply: Boolean = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        val targetId = event.componentId.substringAfter(':').toLongOrNull()
            ?: return reply(ctx, "That button has lost track of who it was for — open the menu again.")

        // The same three refusals the slash command makes, made here so the
        // form never opens onto a tip that cannot land.
        if (targetId == requestingUserDto.discordId) return reply(ctx, "You can't tip yourself.")
        val target = ctx.guild.getMemberById(targetId)
            ?: return reply(ctx, "They don't seem to be in this server any more.")
        if (target.user.isBot) return reply(ctx, "You can't tip a bot.")

        event.replyModal(TipMessageModal.amountForm(targetId, target.effectiveName)).queue()
    }

    /** Not deferred, so this answers the interaction directly. */
    private fun reply(ctx: ButtonContext, message: String) {
        ctx.event.reply(message).setEphemeral(true).queue()
    }

    companion object {
        const val BUTTON_NAME = "tipuser"

        fun button(target: Member): JdaButton =
            JdaButton.of(ButtonStyle.SUCCESS, "$BUTTON_NAME:${target.idLong}", "💸 Tip")
    }
}

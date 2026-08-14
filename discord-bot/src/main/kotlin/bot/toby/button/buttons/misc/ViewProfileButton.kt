package bot.toby.button.buttons.misc

import bot.toby.helpers.ProfileCardHelper
import bot.toby.profile.ProfilePresenter
import core.button.Button
import core.button.ButtonContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.utils.FileUpload
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import net.dv8tion.jda.api.components.buttons.Button as JdaButton

/**
 * Shows a member's profile card, from wherever their name already appears.
 *
 * The half of the cross-link that runs intros → profile. Somebody's intro
 * playing is the most common reason to wonder who they are, and the answer used
 * to mean closing the list and typing `/profile user:` with the name again.
 *
 * The target rides in the component id. It is only a discord id, which is
 * public and which the interaction hands over anyway, so nothing here needs to
 * survive the ephemeral message it sits on.
 */
@Component
class ViewProfileButton @Autowired constructor(
    private val profileCardHelper: ProfileCardHelper,
) : Button {

    override val name: String = BUTTON_NAME
    override val description: String = "Show a member's profile card."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        val targetId = event.componentId.substringAfter(':').toLongOrNull()
            ?: return reply(ctx, "That button has lost track of whose profile it was for — open the menu again.")

        // Cached rather than fetched: whoever pressed this was just shown the
        // member, so JDA has them. Someone who left between the two is the
        // case worth wording, not worth a REST round trip.
        val target = ctx.guild.getMemberById(targetId)
            ?: return reply(ctx, "They don't seem to be in this server any more.")

        val png = profileCardHelper.renderPng(ctx.guild, target)
            ?: return reply(ctx, "Sorry — couldn't render that profile card. The error has been logged.")

        logger.info { "Showed profile of $targetId to ${event.user.idLong}" }

        event.hook.sendMessageEmbeds(ProfilePresenter.embed())
            .addFiles(FileUpload.fromData(png, ProfilePresenter.ATTACHMENT_NAME))
            .addComponents(ProfilePresenter.rows(target, isSelf = targetId == requestingUserDto.discordId))
            .setEphemeral(true)
            .queue()
    }

    private fun reply(ctx: ButtonContext, message: String) {
        ctx.event.hook.sendMessage(message).setEphemeral(true).queue()
    }

    companion object {
        const val BUTTON_NAME = "viewprofile"

        fun button(target: Member): JdaButton =
            JdaButton.of(ButtonStyle.SECONDARY, "$BUTTON_NAME:${target.idLong}", "👤 Profile")
    }
}

package bot.toby.command.commands.misc

import bot.toby.helpers.ProfileCardHelper
import bot.toby.profile.ProfilePresenter
import core.command.UserContextCommand
import database.dto.user.UserDto
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.utils.FileUpload
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Right-click a member → **Apps → Profile**.
 *
 * Right-clicking somebody to find out who they are is the gesture the menu was
 * built for, and the answer already existed — it just needed you to know
 * `/profile` took a `user:` option and to type their name into it again.
 *
 * Ephemeral, unlike `/profile`. A slash command is something you chose to say
 * out loud; a right-click is a glance, and putting somebody else's card into
 * the channel because you looked at it is a different act from posting your
 * own. The buttons under it are the point: the card itself is a picture, so the
 * ways off it — hear their intro, pay them — are what make this the start of an
 * interaction rather than the end of one.
 */
@Component
class ProfileContextCommand @Autowired constructor(
    private val profileCardHelper: ProfileCardHelper,
) : UserContextCommand {

    override val name: String get() = COMMAND_NAME

    override fun handle(event: UserContextInteractionEvent, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(event.guild, event.member)

        val guild = event.guild ?: return reply(
            event,
            "Profiles are per-server — use this inside the server you want to look at.",
        )
        val target = event.targetMember ?: return reply(
            event,
            "${event.target.effectiveName} isn't a member of this server, so they have no profile here.",
        )

        val png = profileCardHelper.renderPng(guild, target) ?: return reply(
            event,
            "Sorry — couldn't render that profile card. The error has been logged.",
        )

        logger.info { "Showed profile of ${target.idLong} to ${event.user.idLong}" }

        event.hook.sendMessageEmbeds(ProfilePresenter.embed())
            .addFiles(FileUpload.fromData(png, ProfilePresenter.ATTACHMENT_NAME))
            .addComponents(ProfilePresenter.rows(target, isSelf = target.idLong == event.user.idLong))
            .setEphemeral(true)
            .queue()
    }

    private fun reply(event: UserContextInteractionEvent, message: String) {
        event.hook.sendMessage(message).setEphemeral(true).queue()
    }

    companion object {
        /** Shown verbatim in Discord's Apps menu; max 32 characters. */
        const val COMMAND_NAME = "Profile"
    }
}

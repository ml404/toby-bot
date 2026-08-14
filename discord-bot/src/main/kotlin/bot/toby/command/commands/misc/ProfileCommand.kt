package bot.toby.command.commands.misc

import bot.toby.helpers.ProfileCardHelper
import bot.toby.profile.ProfilePresenter
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.utils.FileUpload
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/profile [user]` — renders the target member's profile card (level,
 * XP, social credit, equipped title, three most-recent achievements)
 * as a 900×400 PNG and posts it inline.
 *
 * Defers the reply (rendering + avatar fetch can take ~100-300ms,
 * which is close to Discord's 3-second initial-response budget under
 * load). The rendering itself lives in [ProfileCardHelper], shared with
 * the right-click **Profile** entry.
 *
 * The card posts publicly here, unlike the right-click entry: a slash
 * command is something you chose to say out loud. The buttons under it
 * are per-presser regardless — each opens an ephemeral reply or a form
 * belonging to whoever pressed, not to whoever ran the command.
 */
@Component
class ProfileCommand @Autowired constructor(
    private val profileCardHelper: ProfileCardHelper,
) : MiscCommand {

    override val name: String = "profile"
    override val description: String =
        "Render a member's profile card — level, XP, balance, title, and recent achievements."

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.USER, OPT_USER, "Member to inspect (defaults to you)", false),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val guild = event.guild ?: run {
            event.hook.replyEphemeralAndDelete("This command can only be used in a server.", deleteDelay)
            return
        }
        val target: Member = event.getOption(OPT_USER)?.asMember ?: event.member ?: run {
            event.hook.replyEphemeralAndDelete("Could not resolve a member.", deleteDelay)
            return
        }
        val png = profileCardHelper.renderPng(guild, target) ?: run {
            event.hook.replyEphemeralAndDelete(
                "Sorry — couldn't render that profile card. The error has been logged.", deleteDelay
            )
            return
        }
        event.hook.sendMessageEmbeds(ProfilePresenter.embed())
            .addFiles(FileUpload.fromData(png, ProfilePresenter.ATTACHMENT_NAME))
            .addComponents(ProfilePresenter.rows(target, isSelf = target.idLong == event.user.idLong))
            .queue()
    }

    companion object {
        private const val OPT_USER = "user"
    }
}

package bot.toby.command.commands.misc

import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.utils.FileUpload
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import web.profile.ProfileCardRenderer
import web.service.ProfileCardAggregator

/**
 * `/profile [user]` — renders the target member's profile card (level,
 * XP, social credit, equipped title, three most-recent achievements)
 * as a 900×400 PNG and posts it inline.
 *
 * Defers the reply (rendering + avatar fetch can take ~100-300ms,
 * which is close to Discord's 3-second initial-response budget under
 * load). The aggregator + renderer are the same pair used by the web
 * PNG endpoint, so a screenshot from chat and a `card.png` URL render
 * identically.
 */
@Component
class ProfileCommand @Autowired constructor(
    private val aggregator: ProfileCardAggregator,
    private val renderer: ProfileCardRenderer,
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
        val data = aggregator.build(guild, target)
        val png = runCatching { renderer.renderPng(data) }
            .onFailure {
                logger.error {
                    "Profile card render failed for guild ${guild.id} user ${target.id}: " +
                        "${it.javaClass.simpleName}: ${it.message}"
                }
            }
            .getOrNull()
        if (png == null) {
            event.hook.replyEphemeralAndDelete(
                "Sorry — couldn't render that profile card. The error has been logged.", deleteDelay
            )
            return
        }
        val embed = EmbedBuilder()
            .setColor(0x5B8DEF)
            .setImage("attachment://$ATTACHMENT_NAME")
            .build()
        event.hook.sendMessageEmbeds(embed)
            .addFiles(FileUpload.fromData(png, ATTACHMENT_NAME))
            .queue()
    }

    companion object {
        private const val OPT_USER = "user"
        private const val ATTACHMENT_NAME = "profile.png"
    }
}

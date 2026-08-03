package bot.toby.command.commands.music.intro

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.IntroHelper
import bot.toby.intro.IntroPresenter
import bot.toby.lavaplayer.PlayerManager
import common.intro.IntroSlots
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/listintros` — read-only view of the intros configured for this server.
 *
 * Until now the only way to see what you had set was to start `/editintro` or
 * `/deleteintro` and read the prompt before backing out, which is a strange
 * thing to ask of someone who just wants to check their volume. The embed
 * shows slot, volume, clip range and source type for each intro, and links
 * across to the web dashboard for the richer editor (drag-reorder, waveform
 * trim bar, previews).
 *
 * Super-users can pass `user:` to inspect someone else's, the same permission
 * rule `/setintro` applies to setting them.
 */
@Component
class ListIntrosCommand @Autowired constructor(
    private val introHelper: IntroHelper,
) : MusicCommand {

    override val ephemeral: Boolean = true

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: UserDto,
        deleteDelay: Int
    ) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        val requestedMember: Member? = event.getOption(USER)?.asMember
        if (requestedMember != null && requestedMember.idLong != event.user.idLong && !requestingUserDto.superUser) {
            sendErrorMessage(event, deleteDelay)
            return
        }

        val targetMember = requestedMember ?: event.member ?: run {
            event.hook.sendMessage("Intros are per-server — run this inside the server you want to see.")
                .setEphemeral(true).queue()
            return
        }

        val targetDto = if (targetMember.idLong == event.user.idLong) {
            requestingUserDto
        } else {
            introHelper.findUserById(targetMember.idLong, targetMember.guild.idLong)
        }

        val embed = IntroPresenter.listEmbed(targetMember, targetDto.musicDtos, IntroSlots.MAX_INTRO_COUNT)
        event.hook.sendMessageEmbeds(embed)
            .addComponents(ActionRow.of(IntroPresenter.webDashboardButton(targetMember.guild.idLong)))
            .setEphemeral(true)
            .queue()
    }

    override val name: String get() = "listintros"

    override val description: String get() = "Show the intro songs you have set on this server."

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.USER, USER, "Whose intros to show (super-users only)", false)
        )

    companion object {
        private const val USER = "user"
    }
}

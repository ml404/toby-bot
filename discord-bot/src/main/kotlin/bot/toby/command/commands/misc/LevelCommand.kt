package bot.toby.command.commands.misc

import bot.toby.helpers.UserDtoHelper
import common.leveling.LevelCurve
import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class LevelCommand @Autowired constructor(
    private val userDtoHelper: UserDtoHelper
) : MiscCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()
        val target: Member? = event.getOption(USER)?.asMember ?: event.member
        if (target == null) {
            event.hook.replyEphemeralAndDelete("Could not resolve a member.", deleteDelay)
            return
        }
        printLevel(event, target, deleteDelay)
    }

    private fun printLevel(event: SlashCommandInteractionEvent, member: Member, deleteDelay: Int) {
        val dto = userDtoHelper.calculateUserDto(member.idLong, member.guild.idLong)
        val progress = LevelCurve.progress(dto.xp)
        val body = buildString {
            append("**").append(member.effectiveName).append("** — Level ")
            append(progress.level).append('\n')
            append(progressBar(progress.xpIntoLevel, progress.xpForNextLevel)).append(' ')
            append(progress.xpIntoLevel).append(" / ").append(progress.xpForNextLevel).append(" XP\n")
            append("Total XP: ").append(dto.xp)
            if (progress.xpRemaining > 0) {
                append(" — ").append(progress.xpRemaining).append(" XP to next level")
            }
        }
        event.hook.replyAndDelete(body, deleteDelay)
    }

    private fun progressBar(into: Long, total: Long): String {
        if (total <= 0L) return "[" + "█".repeat(BAR_WIDTH) + "]"
        val filled = ((into.toDouble() / total) * BAR_WIDTH)
            .toInt()
            .coerceIn(0, BAR_WIDTH)
        return "[" + "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled) + "]"
    }

    override val name: String = "level"
    override val description: String =
        "Show your level and XP progress (or another member's if mentioned)."
    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.USER, USER, "Member to inspect (defaults to you)", false)
    )

    companion object {
        private const val USER = "user"
        private const val BAR_WIDTH = 20
    }
}

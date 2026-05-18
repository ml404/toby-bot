package bot.toby.command.commands.misc

import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.Command.Companion.replyEphemeralEmbedAndDelete
import core.command.CommandContext
import database.dto.UserDto
import database.service.AchievementService
import database.service.AchievementService.AchievementView
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

@Component
class AchievementsCommand @Autowired constructor(
    private val achievementService: AchievementService
) : MiscCommand {

    override val name: String = "achievements"
    override val description: String =
        "Show unlocked achievements and progress toward the next ones (yours by default)."
    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.USER, OPT_USER, "Member to inspect (defaults to you)", false)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()
        val guild = event.guild ?: run {
            event.hook.replyEphemeralAndDelete(
                "This command can only be used in a server.",
                deleteDelay
            )
            return
        }
        val target: Member = event.getOption(OPT_USER)?.asMember ?: event.member ?: run {
            event.hook.replyEphemeralAndDelete("Could not resolve a member.", deleteDelay)
            return
        }
        val views = achievementService.listFor(target.idLong, guild.idLong)
        event.hook.replyEphemeralEmbedAndDelete(buildEmbed(target.effectiveName, views), deleteDelay)
    }

    private fun buildEmbed(displayName: String, views: List<AchievementView>): MessageEmbed {
        val unlocked = views.filter { it.unlockedAt != null }
        val locked = views.filter { it.unlockedAt == null }

        val body = buildString {
            if (views.isEmpty()) {
                append("No achievements have been seeded yet — check back soon.")
                return@buildString
            }
            append("**${unlocked.size}** / **${views.size}** unlocked\n\n")

            if (unlocked.isNotEmpty()) {
                append("__Unlocked__\n")
                unlocked.take(MAX_LINES_PER_SECTION).forEach { v ->
                    val icon = v.achievement.icon ?: "🏅"
                    append("$icon **").append(v.achievement.name).append("** — ")
                    append(v.achievement.description).append('\n')
                }
                if (unlocked.size > MAX_LINES_PER_SECTION) {
                    append("…and ").append(unlocked.size - MAX_LINES_PER_SECTION).append(" more\n")
                }
                append('\n')
            }
            if (locked.isNotEmpty()) {
                append("__In progress / locked__\n")
                locked.take(MAX_LINES_PER_SECTION).forEach { v ->
                    val icon = v.achievement.icon ?: "🔒"
                    append("$icon **").append(v.achievement.name).append("** — ")
                    append(v.achievement.description)
                    if (v.achievement.threshold > 1) {
                        append("  (").append(v.progress).append('/').append(v.achievement.threshold).append(')')
                    }
                    append('\n')
                }
                if (locked.size > MAX_LINES_PER_SECTION) {
                    append("…and ").append(locked.size - MAX_LINES_PER_SECTION).append(" more\n")
                }
            }
        }

        return EmbedBuilder()
            .setTitle("$displayName — Achievements")
            .setDescription(body)
            .setColor(Color(0xFFC857))
            .build()
    }

    companion object {
        private const val OPT_USER = "user"
        private const val MAX_LINES_PER_SECTION = 15
    }
}

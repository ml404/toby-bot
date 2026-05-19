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
        val builder = EmbedBuilder()
            .setTitle("$displayName — Achievements")
            .setColor(Color(0xFFC857))

        if (views.isEmpty()) {
            builder.setDescription("No achievements have been seeded yet — check back soon.")
            return builder.build()
        }

        val unlocked = views.filter { it.unlockedAt != null }
        val totalXp = unlocked.sumOf { it.achievement.xpReward.toLong() }
        builder.setDescription(
            "**${unlocked.size}** / **${views.size}** unlocked  ·  **$totalXp XP** earned"
        )

        val byCategory = views.groupBy { it.achievement.category }
        CATEGORY_ORDER.forEach { category ->
            val entries = byCategory[category] ?: return@forEach
            builder.addField(renderCategoryField(category, entries))
        }
        // Any catalog category not in CATEGORY_ORDER (future-proofing).
        byCategory.keys
            .filter { it !in CATEGORY_ORDER }
            .sorted()
            .forEach { category ->
                builder.addField(renderCategoryField(category, byCategory.getValue(category)))
            }

        return builder.build()
    }

    private fun renderCategoryField(category: String, entries: List<AchievementView>): MessageEmbed.Field {
        val unlockedCount = entries.count { it.unlockedAt != null }
        val title = "${categoryDisplay(category)}  ($unlockedCount/${entries.size})"

        val sorted = entries.sortedWith(
            compareBy<AchievementView> { it.unlockedAt == null }
                .thenByDescending { it.unlockedAt?.epochSecond ?: 0L }
                .thenByDescending { it.progress }
        )

        val lines = sorted.map { renderLine(it) }
        val body = joinWithLimit(lines, FIELD_VALUE_LIMIT)
        return MessageEmbed.Field(title, body, false)
    }

    private fun renderLine(view: AchievementView): String {
        val a = view.achievement
        val icon = a.icon ?: if (view.unlockedAt != null) "🏅" else "🔒"
        val rewardSuffix = rewardSuffix(a.xpReward, a.creditReward)

        return if (view.unlockedAt != null) {
            val ts = "<t:${view.unlockedAt!!.epochSecond}:R>"
            buildString {
                append("✅ ").append(icon).append(" **").append(a.name).append("** — ").append(a.description)
                append("  ·  *").append(ts).append('*')
                if (rewardSuffix.isNotEmpty()) append("  ·  ").append(rewardSuffix)
            }
        } else {
            buildString {
                append("🔒 ").append(icon).append(" **").append(a.name).append("** — ").append(a.description)
                if (a.threshold > 1) {
                    append("  `[").append(progressBar(view.progress, a.threshold)).append("]` ")
                    append(view.progress).append('/').append(a.threshold)
                }
                if (rewardSuffix.isNotEmpty()) append("  ·  ").append(rewardSuffix)
            }
        }
    }

    private fun rewardSuffix(xpReward: Int, creditReward: Long): String {
        val parts = mutableListOf<String>()
        if (xpReward > 0) parts.add("+$xpReward XP")
        if (creditReward > 0L) parts.add("+${creditReward}¢")
        return parts.joinToString(" ")
    }

    private fun progressBar(progress: Long, threshold: Long): String {
        if (threshold <= 0L) return "░".repeat(BAR_WIDTH)
        val ratio = (progress.toDouble() / threshold).coerceIn(0.0, 1.0)
        val filled = (ratio * BAR_WIDTH).toInt().coerceIn(0, BAR_WIDTH)
        return "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled)
    }

    private fun categoryDisplay(category: String): String =
        CATEGORY_DISPLAY[category] ?: category.replaceFirstChar { it.uppercase() }

    private fun joinWithLimit(lines: List<String>, limit: Int): String {
        val sb = StringBuilder()
        var dropped = 0
        for ((index, line) in lines.withIndex()) {
            val candidate = if (sb.isEmpty()) line else "\n$line"
            val remaining = lines.size - index
            // Reserve space for a trailing "…and N more" if we'd overflow.
            val reserved = if (remaining > 1) TRUNCATE_RESERVE else 0
            if (sb.length + candidate.length + reserved > limit) {
                dropped = lines.size - index
                break
            }
            sb.append(candidate)
        }
        if (dropped > 0) sb.append("\n…and ").append(dropped).append(" more")
        return sb.toString()
    }

    companion object {
        private const val OPT_USER = "user"
        private const val BAR_WIDTH = 10
        private const val FIELD_VALUE_LIMIT = 1024
        private const val TRUNCATE_RESERVE = 24

        private val CATEGORY_ORDER = listOf("streak", "level", "casino", "social", "music", "voice")

        private val CATEGORY_DISPLAY = mapOf(
            "streak" to "🔥 Streaks",
            "level" to "🎖️ Levels",
            "casino" to "🎰 Casino",
            "social" to "🤝 Social",
            "music" to "🎵 Music",
            "voice" to "🎙️ Voice"
        )
    }
}

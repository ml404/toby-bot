package bot.toby.menu.menus

import bot.toby.helpers.MenuHelper.ACTIVITY_CONTRIB
import core.command.Command.Companion.deleteAfter
import core.menu.Menu
import core.menu.MenuContext
import database.service.activity.ActivityMonthlyRollupService
import database.service.user.UserService
import net.dv8tion.jda.api.EmbedBuilder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Backs the "See who contributed…" select menu attached to `/activity server`.
 * The select's componentId is `activitycontrib:<guildId>:<monthEpochDay>`;
 * the picked activity name rides on the option `value`. No registry — all
 * state is on the wire.
 */
@Component
class ActivityContribMenu @Autowired constructor(
    private val rollupService: ActivityMonthlyRollupService,
    private val userService: UserService,
) : Menu {

    override val name: String get() = ACTIVITY_CONTRIB

    companion object {
        const val TOP_CONTRIBUTORS_LIMIT = 8
    }

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()
        val hook = event.hook

        val parts = event.componentId.split(":")
        if (parts.size < 3) {
            hook.sendMessage("That menu has expired — run `/activity server` again.")
                .setEphemeral(true).queue { it.deleteAfter(deleteDelay) }
            return
        }
        val guildId = parts[1].toLongOrNull()
        val epochDay = parts[2].toLongOrNull()
        if (guildId == null || epochDay == null) {
            hook.sendMessage("That menu has expired — run `/activity server` again.")
                .setEphemeral(true).queue { it.deleteAfter(deleteDelay) }
            return
        }
        val monthStart = runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull()
        if (monthStart == null) {
            hook.sendMessage("That menu has expired — run `/activity server` again.")
                .setEphemeral(true).queue { it.deleteAfter(deleteDelay) }
            return
        }
        val activityName = event.selectedOptions.firstOrNull()?.value ?: run {
            hook.sendMessage("No game selected.")
                .setEphemeral(true).queue { it.deleteAfter(deleteDelay) }
            return
        }

        val optedOut = runCatching {
            userService.listGuildUsers(guildId)
                .filterNotNull()
                .filter { it.activityTrackingOptOut }
                .map { it.discordId }
                .toSet()
        }.getOrDefault(emptySet())

        val contributors = runCatching { rollupService.forGuildMonth(guildId, monthStart) }
            .getOrDefault(emptyList())
            .filter { it.discordId !in optedOut && it.activityName == activityName }
            .groupBy { it.discordId }
            .mapValues { (_, rs) -> rs.sumOf { it.seconds } }
            .entries
            .sortedByDescending { it.value }
            .take(TOP_CONTRIBUTORS_LIMIT)

        val monthLabel = monthStart.month.name.lowercase().replaceFirstChar { it.titlecase() } +
                " ${monthStart.year}"

        val embed = if (contributors.isEmpty()) {
            EmbedBuilder()
                .setTitle("$activityName — $monthLabel")
                .setDescription("No contributors recorded for that month.")
                .build()
        } else {
            val total = contributors.sumOf { it.value }
            val guild = ctx.guild
            val body = contributors.joinToString("\n") { (discordId, seconds) ->
                val member = runCatching { guild.getMemberById(discordId) }.getOrNull()
                val name = member?.effectiveName ?: "Unknown"
                val pct = if (total > 0) ((seconds * 100.0) / total).toInt() else 0
                "• $name — ${formatDuration(seconds)} ($pct%)"
            }
            EmbedBuilder()
                .setTitle("$activityName — $monthLabel")
                .setDescription(body)
                .setFooter("Total tracked: ${formatDuration(total)}")
                .build()
        }

        hook.sendMessageEmbeds(embed).setEphemeral(true)
            .queue { it.deleteAfter(deleteDelay) }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}

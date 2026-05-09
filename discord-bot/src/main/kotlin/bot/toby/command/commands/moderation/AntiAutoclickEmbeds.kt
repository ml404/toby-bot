package bot.toby.command.commands.moderation

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color
import java.time.Duration
import java.time.Instant

/**
 * Shared embed builders for the anti-autoclicker session log. One embed
 * per `(userId, guildId, gameKey)` suspicion session, posted by
 * `bot.toby.notify.AntiAutoclickNotifier`:
 *
 * - [openEmbed]   — initial post when the streak first becomes ≥1.
 * - [activeEmbed] — edit-in-place updates as forced-loss substitutions accumulate.
 * - [closedEmbed] — final edit when the streak resets back to 0.
 *
 * Colour-coded so admins can scan a channel quickly: orange = newly flagged,
 * red = bias actively firing, grey = session ended (historical record).
 */
internal object AntiAutoclickEmbeds {

    // Discord-blurple-adjacent palette aligned with TipEmbeds' OK/ERROR colours.
    private val OPEN_COLOR = Color(255, 166, 0)    // amber — new suspicion
    private val ACTIVE_COLOR = Color(237, 66, 69)  // red — bias firing
    private val CLOSED_COLOR = Color(149, 165, 166) // grey — session ended

    fun openEmbed(
        discordId: Long,
        gameKey: String,
        streak: Int,
        startedAt: Instant,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("🤖 Autoclicker signature detected")
        .setDescription(
            "<@${discordId}> tripped the anti-autoclicker heuristic on **$gameKey**. " +
                "Forced-loss bias may start firing on subsequent bets if the pattern continues."
        )
        .addField("Game", gameKey, true)
        .addField("Streak", streak.toString(), true)
        .addField("Started", "<t:${startedAt.epochSecond}:R>", true)
        .setColor(OPEN_COLOR)
        .build()

    fun activeEmbed(
        discordId: Long,
        gameKey: String,
        currentStreak: Int,
        peakStreak: Int,
        fireCount: Int,
        edgePct: Double,
        startedAt: Instant,
        now: Instant,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("🤖 Anti-autoclick bias active")
        .setDescription(
            "<@${discordId}> on **$gameKey** — bias has fired **$fireCount** time" +
                (if (fireCount == 1) "" else "s") + "."
        )
        .addField("Game", gameKey, true)
        .addField("Forced losses", fireCount.toString(), true)
        .addField("Active for", formatDuration(Duration.between(startedAt, now)), true)
        .addField("Current streak", currentStreak.toString(), true)
        .addField("Peak streak", peakStreak.toString(), true)
        .addField("Effective edge", "%.1f %%".format(edgePct), true)
        .addField("Started", "<t:${startedAt.epochSecond}:R>", false)
        .setColor(ACTIVE_COLOR)
        .build()

    fun closedEmbed(
        discordId: Long,
        gameKey: String,
        peakStreak: Int,
        totalFires: Int,
        startedAt: Instant,
        endedAt: Instant,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("🤖 Anti-autoclick session ended")
        .setDescription(
            "<@${discordId}>'s autoclicker streak on **$gameKey** has reset. Final summary below."
        )
        .addField("Game", gameKey, true)
        .addField("Forced losses", totalFires.toString(), true)
        .addField("Peak streak", peakStreak.toString(), true)
        .addField("Duration", formatDuration(Duration.between(startedAt, endedAt)), true)
        .addField("Started", "<t:${startedAt.epochSecond}:f>", true)
        .addField("Ended", "<t:${endedAt.epochSecond}:R>", true)
        .setColor(CLOSED_COLOR)
        .build()

    private fun formatDuration(d: Duration): String {
        val seconds = d.seconds.coerceAtLeast(0)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}

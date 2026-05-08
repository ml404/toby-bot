package bot.toby.command.commands.economy

import database.service.KenoService.PlayOutcome
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * Shared embed plumbing for the Discord `/keno` flow. Mirrors
 * [BaccaratEmbeds] in shape — one-call `outcomeEmbed(outcome)` that
 * builds the right Win / Lose embed, plus the standard failure-case
 * routing through [WagerCommandEmbeds.failureEmbed].
 *
 * Unlike baccarat there is no button flow — keno resolves in a single
 * slash-command call so there's no `parseButtonId`, no prompt embed.
 */
internal object KenoEmbeds {

    private const val TITLE = "🎯 Keno"
    private val NEUTRAL_COLOR = Color(0xA0, 0xA0, 0xB0)

    /**
     * Inline picks listing: `[1, 7, 18, 33, 71]`. The hit numbers are
     * **bolded** so the resolution at a glance is "where did the
     * matches land".
     */
    fun picksLine(picks: List<Int>, hits: Set<Int>): String =
        picks.joinToString(", ") { if (it in hits) "**$it**" else it.toString() }

    /**
     * Inline draws listing: `[3, 7, 18, 22, ...]`. Hit numbers (those
     * the player picked) are **bolded**; the rest are plain. 20 numbers
     * fit comfortably on one line in Discord.
     */
    fun drawsLine(draws: List<Int>, picks: Set<Int>): String =
        draws.sorted().joinToString(", ") { if (it in picks) "**$it**" else it.toString() }

    fun outcomeEmbed(outcome: PlayOutcome): MessageEmbed = when (outcome) {
        is PlayOutcome.Win -> {
            val picksSet = outcome.picks.toSet()
            WagerCommandEmbeds.outcomeEmbed(
                title = "$TITLE • ${outcome.hits}/${outcome.picks.size} hit",
                description =
                    "**Your picks:** ${picksLine(outcome.picks, picksSet.intersect(outcome.draws.toSet()))}\n" +
                    "**Drawn:** ${drawsLine(outcome.draws, picksSet)}\n\n" +
                    "🎯 Won **+${outcome.net} credits** at " +
                    "**${WagerCommandEmbeds.multiplierLabel(outcome.multiplier)}** on a " +
                    "${outcome.stake} stake." +
                    jackpotLine(outcome.jackpotPayout),
                newBalance = outcome.newBalance,
                color = WagerCommandColors.WIN
            )
        }

        is PlayOutcome.Lose -> {
            val picksSet = outcome.picks.toSet()
            val color = if (outcome.hits > 0) NEUTRAL_COLOR else WagerCommandColors.LOSE
            WagerCommandEmbeds.outcomeEmbed(
                title = "$TITLE • ${outcome.hits}/${outcome.picks.size} hit",
                description =
                    "**Your picks:** ${picksLine(outcome.picks, picksSet.intersect(outcome.draws.toSet()))}\n" +
                    "**Drawn:** ${drawsLine(outcome.draws, picksSet)}\n\n" +
                    "❌ Lost **${outcome.stake} credits**." +
                    tributeLine(outcome.lossTribute),
                newBalance = outcome.newBalance,
                color = color
            )
        }

        is PlayOutcome.InsufficientCredits -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have)
        )
        is PlayOutcome.InsufficientCoinsForTopUp -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have)
        )
        is PlayOutcome.InvalidStake -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InvalidStake(outcome.min, outcome.max)
        )
        is PlayOutcome.InvalidPicks -> errorEmbed(
            "Pick ${outcome.min}-${outcome.max} distinct numbers between 1 and ${outcome.poolMax}."
        )
        PlayOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }

    fun errorEmbed(message: String): MessageEmbed = WagerCommandEmbeds.errorEmbed(TITLE, message)

    private fun jackpotLine(jackpotPayout: Long): String =
        if (jackpotPayout > 0L) "\n🎰 Jackpot hit! **+$jackpotPayout credits.**" else ""

    private fun tributeLine(tribute: Long): String =
        if (tribute > 0L) "\n💰 +$tribute credits to the jackpot pool." else ""
}

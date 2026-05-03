package bot.toby.command.commands.economy

import database.card.Card
import database.economy.Baccarat
import database.service.BaccaratService.PlayOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * Shared embed/component plumbing for the Discord `/baccarat` flow.
 * The slash command uses [promptEmbed] + [sideButtonId] to post the bet
 * prompt; [bot.toby.button.buttons.BaccaratButton] uses [outcomeEmbed]
 * to render the resolution. Mirrors [HighlowEmbeds] one-for-one — the
 * button-id encoding (`baccarat:<SIDE>:<stake>:<userId>`) and the
 * win/lose colour palette are deliberately the same shape so the two
 * flows feel consistent.
 */
internal object BaccaratEmbeds {

    const val BUTTON_NAME = "baccarat"
    private const val BUTTON_DELIM = ":"

    private const val TITLE = "🎴 Baccarat"
    private val PROMPT_COLOR = Color(0x2C, 0x3E, 0x50)
    private val NEUTRAL_COLOR = Color(0xA0, 0xA0, 0xB0)

    fun sideButtonId(side: Baccarat.Side, stake: Long, userId: Long): String =
        listOf(BUTTON_NAME, side.name, stake.toString(), userId.toString())
            .joinToString(BUTTON_DELIM)

    data class ParsedButtonId(
        val side: Baccarat.Side,
        val stake: Long,
        val userId: Long
    )

    fun parseButtonId(componentId: String): ParsedButtonId? {
        val parts = componentId.split(BUTTON_DELIM)
        if (parts.size != 4 || !parts[0].equals(BUTTON_NAME, ignoreCase = true)) return null
        val side = runCatching { Baccarat.Side.valueOf(parts[1].uppercase()) }.getOrNull() ?: return null
        val stake = parts[2].toLongOrNull() ?: return null
        val userId = parts[3].toLongOrNull() ?: return null
        return ParsedButtonId(side, stake, userId)
    }

    private fun cardLabel(card: Card): String = "`$card`"

    /**
     * Inline hand: ``A♠ 7♦`` or ``A♠ 7♦ → 5♣`` when a third card was drawn.
     * Trailing **(total)** and an optional Natural badge mirror the
     * blackjack handLine style.
     */
    fun handLine(cards: List<Card>, total: Int, isNatural: Boolean): String {
        if (cards.isEmpty()) return "—"
        val cardsText = if (cards.size <= 2) {
            cards.joinToString(" ") { cardLabel(it) }
        } else {
            val opening = cards.subList(0, 2).joinToString(" ") { cardLabel(it) }
            val rest = cards.subList(2, cards.size).joinToString(" ") { cardLabel(it) }
            "$opening → $rest"
        }
        val suffix = if (isNatural) " — **Natural**" else ""
        return "$cardsText **($total)**$suffix"
    }

    fun promptEmbed(stake: Long): MessageEmbed = EmbedBuilder()
        .setTitle("$TITLE • $stake credits")
        .setDescription(
            "Pick a side. Both hands deal automatically.\n\n" +
                "**Player** pays **${WagerCommandEmbeds.multiplierLabel(Baccarat.PLAYER_WIN_MULT)}**, " +
                "**Banker** pays **${WagerCommandEmbeds.multiplierLabel(Baccarat.BANKER_WIN_MULT)}** (5% commission), " +
                "**Tie** pays **${WagerCommandEmbeds.multiplierLabel(Baccarat.TIE_WIN_MULT)}**.\n" +
                "On a tied game, Player and Banker bets are refunded."
        )
        .setColor(PROMPT_COLOR)
        .build()

    fun outcomeEmbed(outcome: PlayOutcome): MessageEmbed = when (outcome) {
        is PlayOutcome.Win -> WagerCommandEmbeds.outcomeEmbed(
            title = "$TITLE • ${outcome.side.display}",
            description = "**Player:** ${handLine(outcome.playerCards, outcome.playerTotal, outcome.isPlayerNatural)}\n" +
                "**Banker:** ${handLine(outcome.bankerCards, outcome.bankerTotal, outcome.isBankerNatural)}\n\n" +
                winVerdict(outcome) +
                jackpotLine(outcome.jackpotPayout),
            newBalance = outcome.newBalance,
            color = WagerCommandColors.WIN
        )

        is PlayOutcome.Push -> WagerCommandEmbeds.outcomeEmbed(
            title = "$TITLE • ${outcome.side.display}",
            description = "**Player:** ${handLine(outcome.playerCards, outcome.playerTotal, outcome.isPlayerNatural)}\n" +
                "**Banker:** ${handLine(outcome.bankerCards, outcome.bankerTotal, outcome.isBankerNatural)}\n\n" +
                "🤝 Tie game — your **${outcome.side.display}** stake of " +
                "**${outcome.stake} credits** is refunded.",
            newBalance = outcome.newBalance,
            color = NEUTRAL_COLOR
        )

        is PlayOutcome.Lose -> WagerCommandEmbeds.outcomeEmbed(
            title = "$TITLE • ${outcome.side.display}",
            description = "**Player:** ${handLine(outcome.playerCards, outcome.playerTotal, outcome.isPlayerNatural)}\n" +
                "**Banker:** ${handLine(outcome.bankerCards, outcome.bankerTotal, outcome.isBankerNatural)}\n\n" +
                "❌ ${outcome.winner.display} wins. Lost **${outcome.stake} credits**." +
                tributeLine(outcome.lossTribute),
            newBalance = outcome.newBalance,
            color = WagerCommandColors.LOSE
        )

        is PlayOutcome.InsufficientCredits -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have)
        )
        is PlayOutcome.InsufficientCoinsForTopUp -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have)
        )
        is PlayOutcome.InvalidStake -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InvalidStake(outcome.min, outcome.max)
        )
        PlayOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }

    fun errorEmbed(message: String): MessageEmbed = WagerCommandEmbeds.errorEmbed(TITLE, message)

    private fun winVerdict(win: PlayOutcome.Win): String {
        val emoji = when (win.side) {
            Baccarat.Side.PLAYER -> "✅"
            Baccarat.Side.BANKER -> "⚖️"
            Baccarat.Side.TIE -> "🎉"
        }
        val flavour = when (win.side) {
            Baccarat.Side.PLAYER -> "Player wins"
            Baccarat.Side.BANKER -> "Banker wins (5% commission)"
            Baccarat.Side.TIE -> "Tie!"
        }
        return "$emoji $flavour — won **+${win.net} credits** at ${WagerCommandEmbeds.multiplierLabel(win.multiplier)}."
    }

    private fun jackpotLine(jackpotPayout: Long): String =
        if (jackpotPayout > 0L) "\n🎰 Jackpot hit! **+$jackpotPayout credits.**" else ""

    private fun tributeLine(tribute: Long): String =
        if (tribute > 0L) "\n💰 +$tribute credits to the jackpot pool." else ""
}

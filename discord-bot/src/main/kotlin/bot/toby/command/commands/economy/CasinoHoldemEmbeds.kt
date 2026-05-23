package bot.toby.command.commands.economy

import common.card.Card
import common.poker.CasinoHoldem
import common.poker.CasinoHoldemTable
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * Shared embed/component plumbing for the Discord `/casinoholdem` flow.
 *
 * Component IDs encode the action and table id:
 *   `casinoholdem:CALL:<tableId>`
 * The [bot.toby.button.buttons.CasinoHoldemButton] handler parses this
 * back out and routes to [database.service.CasinoHoldemService.applyAction].
 */
internal object CasinoHoldemEmbeds {

    const val BUTTON_NAME = "casinoholdem"
    private const val BUTTON_DELIM = ":"

    const val TITLE = "🃏 Casino Hold'em"

    private val TABLE_COLOR = Color(0x2C, 0x3E, 0x50)
    private val NEUTRAL_COLOR = Color(0xA0, 0xA0, 0xB0)
    private const val HIDDEN = "🂠"

    enum class Action { CALL, FOLD }

    fun buttonId(action: Action, tableId: Long): String =
        listOf(BUTTON_NAME, action.name, tableId.toString()).joinToString(BUTTON_DELIM)

    data class ParsedButtonId(val action: Action, val tableId: Long)

    fun parseButtonId(componentId: String): ParsedButtonId? {
        val parts = componentId.split(BUTTON_DELIM)
        if (parts.size != 3 || !parts[0].equals(BUTTON_NAME, ignoreCase = true)) return null
        val action = runCatching { Action.valueOf(parts[1].uppercase()) }.getOrNull() ?: return null
        val tableId = parts[2].toLongOrNull() ?: return null
        return ParsedButtonId(action, tableId)
    }

    private fun cardLabel(card: Card): String = "`$card`"

    private fun handLine(cards: List<Card>): String =
        if (cards.isEmpty()) "—" else cards.joinToString(" ") { cardLabel(it) }

    /** Embed shown after a successful deal — flop visible, dealer hole hidden. */
    fun dealEmbed(table: CasinoHoldemTable): MessageEmbed {
        val callCost = table.stake * CasinoHoldem.CALL_MULTIPLE
        val description = buildString {
            append("**Dealer:** ").append(HIDDEN).append(' ').append(HIDDEN).append('\n')
            append("**Board:** ").append(handLine(table.board)).append('\n')
            append("**You:** ").append(handLine(table.playerHole)).append("\n\n")
            append("Ante **${table.stake}** credits at risk. ")
            append("**Call** to commit another **$callCost** credits and see the turn + river, ")
            append("or **Fold** to forfeit the ante.")
        }
        return EmbedBuilder()
            .setTitle("$TITLE • ${table.stake} credits ante")
            .setDescription(description)
            .setColor(TABLE_COLOR)
            .setFooter("Dealer must hold a pair of fours or better to qualify.")
            .build()
    }

    /** Embed shown after the hand resolves (FOLD or showdown). */
    fun resolvedEmbed(
        table: CasinoHoldemTable,
        result: CasinoHoldemTable.HandResult,
        newBalance: Long,
        jackpotPayout: Long,
        lossTribute: Long,
    ): MessageEmbed {
        val description = buildString {
            if (result.folded) {
                append("**Dealer:** ").append(HIDDEN).append(' ').append(HIDDEN).append('\n')
                append("**Board:** ").append(handLine(result.board)).append('\n')
                append("**You:** ").append(handLine(result.playerHole)).append("\n\n")
                append("You folded. Ante of **${result.anteStake}** forfeited.")
            } else {
                append("**Dealer:** ").append(handLine(result.dealerHole)).append('\n')
                append("**Board:** ").append(handLine(result.board)).append('\n')
                append("**You:** ").append(handLine(result.playerHole)).append("\n\n")
                val resolution = result.resolution
                if (resolution == null) {
                    append("Hand resolved.")
                } else {
                    if (!resolution.dealerQualified) {
                        append("Dealer didn't qualify — ante pays even, call pushes.\n")
                    }
                    append("Ante: ").append(legLabel(resolution.anteResult, result.anteStake, result.antePayout))
                        .append('\n')
                    append("Call: ").append(callLegLabel(resolution.callResult, result.callStake, result.callPayout))
                        .append('\n')
                }
                append('\n').append(netLine(result.net))
            }
            if (jackpotPayout > 0L) append("\n🎰 Jackpot hit! +$jackpotPayout credits.")
            if (lossTribute > 0L) append("\n💰 +$lossTribute credits to the jackpot pool.")
        }
        return EmbedBuilder()
            .setTitle(TITLE)
            .setDescription(description)
            .addField("New balance", "$newBalance credits", true)
            .setColor(colorFor(result))
            .build()
    }

    private fun legLabel(result: CasinoHoldem.AnteResult, stake: Long, payout: Long): String =
        when (result) {
            CasinoHoldem.AnteResult.WIN -> "✅ Win +${payout - stake}"
            CasinoHoldem.AnteResult.PUSH -> "🤝 Push (refunded $stake)"
            CasinoHoldem.AnteResult.LOSE -> "❌ Lose -$stake"
        }

    private fun callLegLabel(result: CasinoHoldem.CallResult, stake: Long, payout: Long): String =
        when (result) {
            CasinoHoldem.CallResult.WIN_ROYAL_FLUSH -> "🎉 Royal flush! +${payout - stake} (100:1)"
            CasinoHoldem.CallResult.WIN_STRAIGHT_FLUSH -> "🎉 Straight flush +${payout - stake} (20:1)"
            CasinoHoldem.CallResult.WIN_QUADS -> "🎉 Quads +${payout - stake} (10:1)"
            CasinoHoldem.CallResult.WIN_FULL_HOUSE -> "✅ Full house +${payout - stake} (3:1)"
            CasinoHoldem.CallResult.WIN_FLUSH -> "✅ Flush +${payout - stake} (2:1)"
            CasinoHoldem.CallResult.WIN_STRAIGHT -> "✅ Straight +${payout - stake} (1:1)"
            CasinoHoldem.CallResult.WIN_OTHER -> "✅ Win +${payout - stake} (1:1)"
            CasinoHoldem.CallResult.PUSH -> "🤝 Push (refunded $stake)"
            CasinoHoldem.CallResult.LOSE -> "❌ Lose -$stake"
            CasinoHoldem.CallResult.FOLDED -> "—"
        }

    private fun netLine(net: Long): String = when {
        net > 0 -> "Net **+$net credits**."
        net < 0 -> "Net **$net credits**."
        else -> "Broke even."
    }

    private fun colorFor(result: CasinoHoldemTable.HandResult): Color = when {
        result.folded -> WagerCommandColors.LOSE
        result.net > 0 -> WagerCommandColors.WIN
        result.net < 0 -> WagerCommandColors.LOSE
        else -> NEUTRAL_COLOR
    }

    fun errorEmbed(message: String): MessageEmbed = WagerCommandEmbeds.errorEmbed(TITLE, message)
}

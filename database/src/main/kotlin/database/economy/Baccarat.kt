package database.economy

import database.card.Card
import database.card.Deck
import database.card.Rank

/**
 * Pure-logic Punto Banco baccarat. No Spring, no DB, no JDA — deals two
 * hands from a [Deck], follows the standard third-card "tableau" to
 * complete each side, and resolves the player's side bet to a payout
 * multiplier.
 *
 * Mechanic
 *   The player picks one of three sides before the deal: PLAYER, BANKER,
 *   or TIE. Card values are pip values with all tens and faces counted as
 *   zero (A=1, 2-9=face, 10/J/Q/K=0); a hand's total is the sum mod 10.
 *
 *   Naturals: if either side has 8 or 9 after the first two cards, both
 *   stand and the higher total wins (tie pushes the side bet, pays Tie).
 *
 *   Otherwise, the Player draws a third card on totals 0-5 and stands on
 *   6-7. The Banker's draw rule depends on whether the Player drew, and
 *   if so on the value of the Player's third card (the standard Punto
 *   Banco tableau in [shouldBankerDraw]). Both sides take at most one
 *   extra card.
 *
 *   Payouts on a winning side bet:
 *     - PLAYER:  1:1            (multiplier 2.0)
 *     - BANKER:  1:1 minus 5%   (multiplier 1.95)
 *     - TIE:     8:1            (multiplier 9.0)
 *   On a tied game, PLAYER and BANKER side bets push (multiplier 1.0).
 *   Any non-winning side returns 0.0. Per-bet house edge sits between
 *   /coinflip and /slots: ~1.24% on Player, ~1.06% on Banker, ~14.4% on
 *   Tie at this schedule.
 */
class Baccarat {

    enum class Side(val display: String) {
        PLAYER("Player"),
        BANKER("Banker"),
        TIE("Tie")
    }

    data class Hand(
        val side: Side,
        val winner: Side,
        val playerCards: List<Card>,
        val bankerCards: List<Card>,
        val playerTotal: Int,
        val bankerTotal: Int,
        val isPlayerNatural: Boolean,
        val isBankerNatural: Boolean,
        val multiplier: Double
    ) {
        val isWin: Boolean get() = multiplier > 1.0
        val isPush: Boolean get() = multiplier == 1.0
        val isLose: Boolean get() = multiplier == 0.0
    }

    /**
     * Deal both hands from [deck] and resolve [side]. The deck is mutated
     * — callers should pass a freshly seeded [Deck] per hand. Six cards
     * worst-case (two each plus an optional third per side), which leaves
     * the 52-card deck nowhere near exhaustion.
     */
    fun play(side: Side, deck: Deck): Hand {
        val player = mutableListOf(deck.deal(), deck.deal())
        val banker = mutableListOf(deck.deal(), deck.deal())

        val pTotal0 = handTotal(player)
        val bTotal0 = handTotal(banker)
        val playerNatural = pTotal0 >= 8
        val bankerNatural = bTotal0 >= 8

        if (!playerNatural && !bankerNatural) {
            val playerThird: Card? = if (pTotal0 <= 5) deck.deal().also { player.add(it) } else null
            if (shouldBankerDraw(handTotal(banker), playerThird)) {
                banker.add(deck.deal())
            }
        }

        val pTotal = handTotal(player)
        val bTotal = handTotal(banker)
        val winner = when {
            pTotal > bTotal -> Side.PLAYER
            bTotal > pTotal -> Side.BANKER
            else -> Side.TIE
        }

        return Hand(
            side = side,
            winner = winner,
            playerCards = player.toList(),
            bankerCards = banker.toList(),
            playerTotal = pTotal,
            bankerTotal = bTotal,
            isPlayerNatural = playerNatural,
            isBankerNatural = bankerNatural,
            multiplier = multiplier(side, winner)
        )
    }

    /**
     * Punto Banco banker tableau. With no Player third card, Banker mirrors
     * the Player's stand-on-six rule. With a Player third card, the table
     * lookup below decides per Banker total.
     */
    private fun shouldBankerDraw(bankerTotal: Int, playerThird: Card?): Boolean {
        if (bankerTotal >= 7) return false
        if (playerThird == null) return bankerTotal <= 5
        val p = playerThird.baccaratValue()
        return when (bankerTotal) {
            0, 1, 2 -> true
            3 -> p != 8
            4 -> p in 2..7
            5 -> p in 4..7
            6 -> p in 6..7
            else -> false
        }
    }

    /** Resolve the side bet given which side actually won. */
    fun multiplier(side: Side, winner: Side): Double {
        if (winner == Side.TIE && side != Side.TIE) return PUSH_MULT
        if (side != winner) return LOSS_MULT
        return previewMultiplier(side)
    }

    /** Per-side payout multiplier for embed labels and button previews. */
    fun previewMultiplier(side: Side): Double = when (side) {
        Side.PLAYER -> PLAYER_WIN_MULT
        Side.BANKER -> BANKER_WIN_MULT
        Side.TIE -> TIE_WIN_MULT
    }

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L

        const val PLAYER_WIN_MULT: Double = 2.0
        const val BANKER_WIN_MULT: Double = 1.95
        const val TIE_WIN_MULT: Double = 9.0
        const val PUSH_MULT: Double = 1.0
        const val LOSS_MULT: Double = 0.0
    }
}

/** Pip value used by baccarat: A=1, 2-9=face, 10/J/Q/K=0. */
internal fun Card.baccaratValue(): Int = when (rank) {
    Rank.ACE -> 1
    Rank.TWO -> 2
    Rank.THREE -> 3
    Rank.FOUR -> 4
    Rank.FIVE -> 5
    Rank.SIX -> 6
    Rank.SEVEN -> 7
    Rank.EIGHT -> 8
    Rank.NINE -> 9
    Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING -> 0
}

/** Hand total: sum of pip values mod 10. */
internal fun handTotal(cards: List<Card>): Int = cards.sumOf { it.baccaratValue() } % 10

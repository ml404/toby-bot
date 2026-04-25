package database.economy

import kotlin.random.Random

/**
 * Pure-logic 3-reel slot machine. No Spring, no DB, no JDA — just weighted
 * reel draws and a payout table.
 *
 * Reel design:
 *   Same symbols on all three reels, drawn independently. Weights are out
 *   of 10:
 *     🍒 (4) common
 *     🍋 (3) common
 *     🔔 (2) uncommon
 *     ⭐ (1) rare
 *
 * Payouts (multiplier × stake on 3-of-a-kind):
 *     🍒🍒🍒  =   5×   P=0.064  EV=0.320
 *     🍋🍋🍋  =  10×   P=0.027  EV=0.270
 *     🔔🔔🔔  =  25×   P=0.008  EV=0.200
 *     ⭐⭐⭐  = 100×   P=0.001  EV=0.100
 *     anything else: 0× (lose stake)
 *
 * RTP ≈ 0.890 (~11% house edge). 100× jackpot at MAX_STAKE = 500 credits
 * pays out 50,000 — meaningful but not "buy every title in one pull"
 * territory (cheapest title costs 100 credits, most expensive 5,000).
 *
 * Stake bounds (MIN_STAKE / MAX_STAKE) live here so the Discord command,
 * the web controller, and the service all agree on what's valid.
 */
class SlotMachine(
    private val reel: List<Symbol> = DEFAULT_REEL,
    private val payouts: Map<Symbol, Long> = DEFAULT_PAYOUTS
) {

    enum class Symbol(val display: String) {
        CHERRY("🍒"),
        LEMON("🍋"),
        BELL("🔔"),
        STAR("⭐")
    }

    data class Pull(val symbols: List<Symbol>, val multiplier: Long) {
        val isWin: Boolean get() = multiplier > 0L
    }

    fun pull(random: Random): Pull {
        val drawn = List(REEL_COUNT) { reel[random.nextInt(reel.size)] }
        val multiplier = if (drawn.toSet().size == 1) {
            payouts[drawn.first()] ?: 0L
        } else {
            0L
        }
        return Pull(drawn, multiplier)
    }

    companion object {
        const val REEL_COUNT: Int = 3
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L

        // Weighted reel: 4 cherries + 3 lemons + 2 bells + 1 star = 10 cells.
        // Same reel on all three slots; independent draws.
        val DEFAULT_REEL: List<Symbol> = buildList {
            repeat(4) { add(Symbol.CHERRY) }
            repeat(3) { add(Symbol.LEMON) }
            repeat(2) { add(Symbol.BELL) }
            repeat(1) { add(Symbol.STAR) }
        }

        val DEFAULT_PAYOUTS: Map<Symbol, Long> = mapOf(
            Symbol.CHERRY to 5L,
            Symbol.LEMON to 10L,
            Symbol.BELL to 25L,
            Symbol.STAR to 100L
        )
    }
}

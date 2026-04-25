package database.economy

import kotlin.random.Random

/**
 * Pure-logic 6-sided dice. No Spring, no DB, no JDA — just a uniform
 * draw 1..sides and a comparison against the caller's prediction.
 *
 * Mechanic
 *   User picks a number 1..6, rolls a die. Match → `multiplier × stake`
 *   payout. True odds of a hit are 1/6 ≈ 0.1667; with a 5× payout,
 *   RTP = 5/6 ≈ 0.833 (~17% house edge). Sits between `/coinflip`
 *   (no edge) and `/slots` (~11% edge) on the risk/sink scale.
 *
 * Stake bounds (`MIN_STAKE` / `MAX_STAKE`) live here so the Discord
 * command, the web controller, and the service all agree on what's
 * valid.
 */
class Dice(
    private val sides: Int = DEFAULT_SIDES,
    private val multiplier: Long = DEFAULT_MULTIPLIER
) {

    data class Roll(val landed: Int, val predicted: Int, val multiplier: Long) {
        val isWin: Boolean get() = landed == predicted
    }

    fun roll(predicted: Int, random: Random): Roll {
        val landed = random.nextInt(1, sides + 1)
        return Roll(
            landed = landed,
            predicted = predicted,
            multiplier = if (landed == predicted) multiplier else 0L
        )
    }

    fun isValidPrediction(predicted: Int): Boolean = predicted in 1..sides
    val sidesCount: Int get() = sides

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L
        const val DEFAULT_SIDES: Int = 6
        // 5× on hit; with 1/6 true odds the RTP is 5/6 ≈ 0.833 (~17% edge).
        const val DEFAULT_MULTIPLIER: Long = 5L
    }
}

package common.casino.roulette

import kotlin.random.Random

/**
 * Pure-logic European roulette wheel (single zero, 37 pockets, 2.7%
 * house edge from the green zero). No Spring, no DB, no JDA — just a
 * uniform pocket draw and a per-bet payout table.
 *
 * Bet menu (one bet per spin, mirroring `/dice` + `/coinflip` for a
 * one-stake/one-outcome service contract):
 *
 *   RED / BLACK              1:1   (even-money outside; 0 loses)
 *   ODD / EVEN               1:1   (even-money outside; 0 loses)
 *   LOW (1-18) / HIGH (19-36)1:1   (even-money outside; 0 loses)
 *   DOZEN_1/2/3              2:1   (1-12, 13-24, 25-36)
 *   COLUMN_1/2/3             2:1   (1,4,7…/2,5,8…/3,6,9…)
 *   STRAIGHT (single number) 35:1  (caller picks 0-36)
 *
 * [WagerHelper.applyMultiplier] computes `payout = multiplier × stake`
 * and `net = payout − stake`, so the Bet.multiplier values below are
 * `(odds + 1)` (e.g. 1:1 → 2×, 35:1 → 36×).
 *
 * RTP across every bet type works out to 36/37 ≈ 0.973 (the standard
 * European house edge). The green zero is the entire edge — losses
 * still feed the per-guild jackpot pool via [JackpotHelper.divertOnLoss]
 * and wins still roll for it via [JackpotHelper.rollOnWin], identical
 * to every other minigame.
 *
 * Stake bounds (`MIN_STAKE` / `MAX_STAKE`) live here so the Discord
 * command, the web controller, and the service all agree on what's
 * valid; per-guild admin overrides go through `ROULETTE_MIN_STAKE` /
 * `ROULETTE_MAX_STAKE` config keys.
 */
class Roulette {

    enum class Color { RED, BLACK, GREEN }

    enum class Bet(val display: String, val multiplier: Long) {
        RED("Red", 2L),
        BLACK("Black", 2L),
        ODD("Odd", 2L),
        EVEN("Even", 2L),
        LOW("Low (1-18)", 2L),
        HIGH("High (19-36)", 2L),
        DOZEN_1("1st dozen", 3L),
        DOZEN_2("2nd dozen", 3L),
        DOZEN_3("3rd dozen", 3L),
        COLUMN_1("1st column", 3L),
        COLUMN_2("2nd column", 3L),
        COLUMN_3("3rd column", 3L),
        STRAIGHT("Straight", 36L);

        val requiresNumber: Boolean get() = this == STRAIGHT
    }

    data class Spin(
        val landed: Int,
        val color: Color,
        val bet: Bet,
        val straightNumber: Int?,
        val multiplier: Long,
    ) {
        val isWin: Boolean get() = multiplier > 0L
    }

    fun spin(bet: Bet, straightNumber: Int?, random: Random): Spin {
        val landed = random.nextInt(POCKET_COUNT)
        val won = wins(bet, straightNumber, landed)
        return Spin(
            landed = landed,
            color = colorOf(landed),
            bet = bet,
            straightNumber = straightNumber,
            multiplier = if (won) bet.multiplier else 0L,
        )
    }

    companion object {
        const val POCKET_COUNT: Int = 37          // European: 0-36 inclusive.
        const val MAX_NUMBER: Int = 36
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L

        /**
         * Standard European wheel red pockets. Black is the complement
         * within 1..36; 0 is green. Memorised verbatim from the European
         * single-zero wheel — same set every casino uses.
         */
        val RED_NUMBERS: Set<Int> = setOf(
            1, 3, 5, 7, 9, 12, 14, 16, 18,
            19, 21, 23, 25, 27, 30, 32, 34, 36,
        )

        /**
         * Standard European wheel pocket order (clockwise starting at 0).
         * Used by the web UI to animate the ball decelerating across
         * sequential pockets — pure cosmetic data, draw probability is
         * still uniform over 0..36.
         */
        val WHEEL_ORDER: List<Int> = listOf(
            0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27,
            13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1,
            20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26,
        )

        fun colorOf(pocket: Int): Color = when {
            pocket == 0 -> Color.GREEN
            pocket in RED_NUMBERS -> Color.RED
            else -> Color.BLACK
        }

        /**
         * Predicate over the bet menu: does [pocket] cover this [bet]?
         * Pure function — the service uses it both inside [Roulette.spin]
         * and (defensively) when settling Win vs. Lose so the JSON shape
         * always agrees with the wheel position the UI is about to land
         * the ball on. The zero pocket loses every outside bet by design.
         */
        fun wins(bet: Bet, straightNumber: Int?, pocket: Int): Boolean = when (bet) {
            Bet.RED -> pocket in RED_NUMBERS
            Bet.BLACK -> pocket != 0 && pocket !in RED_NUMBERS
            Bet.ODD -> pocket != 0 && pocket % 2 == 1
            Bet.EVEN -> pocket != 0 && pocket % 2 == 0
            Bet.LOW -> pocket in 1..18
            Bet.HIGH -> pocket in 19..36
            Bet.DOZEN_1 -> pocket in 1..12
            Bet.DOZEN_2 -> pocket in 13..24
            Bet.DOZEN_3 -> pocket in 25..36
            Bet.COLUMN_1 -> pocket != 0 && pocket % 3 == 1
            Bet.COLUMN_2 -> pocket != 0 && pocket % 3 == 2
            Bet.COLUMN_3 -> pocket != 0 && pocket % 3 == 0
            Bet.STRAIGHT -> straightNumber != null && pocket == straightNumber
        }
    }
}

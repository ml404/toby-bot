package common.casino.horseracing

import kotlin.random.Random

/**
 * Pure-logic horse racing minigame — six horses, one race, one bet per
 * call. No Spring, no DB, no JDA. Mirrors the shape of [Roulette]: a
 * [Bet] enum, a [Race] result data class, and a single [race] entry point
 * the [database.service.casino.horseracing.HorseRacingService] calls through.
 *
 * Field: six horses indexed 1..6 (H1 favourite, H6 longshot). Each
 * [HorseProfile] carries a fixed win probability plus a per-bet-type
 * payout multiplier triplet. The finishing order is sampled via a
 * Plackett–Luce model — pick the winner with probabilities proportional
 * to each horse's strength, then the second from the remaining horses
 * with renormalised strengths, and so on. Strengths are the same as the
 * stated win probabilities, so `P(H_i finishes 1st) = winProb_i` exactly.
 *
 * Bets (each pays from the picked horse's matching multiplier):
 *
 *   WIN      — the horse must finish 1st.
 *   PLACE    — the horse must finish 1st or 2nd.
 *   SHOW     — the horse must finish 1st, 2nd, or 3rd.
 *
 * The per-bet-type multipliers are calibrated so each bet on each horse
 * lands at the target RTP of ~0.92 — verified by [HorseRacingTest]. The
 * payouts are fractional (Place/Show pay less than even money for the
 * favourite) so the service uses [database.service.pvp.WagerHelper.applyMultiplier]'s
 * `Double` overload (the same path Highlow uses).
 */
class HorseRacing {

    enum class Bet(val display: String) {
        WIN("Win"),
        PLACE("Place"),
        SHOW("Show"),
    }

    data class HorseProfile(
        val index: Int,
        val name: String,
        val emoji: String,
        val winProb: Double,
        val winMult: Double,
        val placeMult: Double,
        val showMult: Double,
    ) {
        fun multiplier(bet: Bet): Double = when (bet) {
            Bet.WIN -> winMult
            Bet.PLACE -> placeMult
            Bet.SHOW -> showMult
        }
    }

    data class Race(
        val finishingOrder: List<Int>,
        val bet: Bet,
        val pickedHorse: Int,
        val multiplier: Double,
    ) {
        val isWin: Boolean get() = multiplier > 0.0
    }

    /**
     * Sample a finishing order via Plackett–Luce, decide the payout for
     * [pickedHorse] under [bet], and bundle the result. [pickedHorse] is
     * 1-indexed to match the Discord slash command / web UI numbering.
     */
    fun race(pickedHorse: Int, bet: Bet, random: Random): Race {
        require(pickedHorse in 1..HORSES.size) {
            "pickedHorse $pickedHorse outside 1..${HORSES.size}"
        }
        val order = sampleFinishingOrder(random)
        val won = wins(pickedHorse, bet, order)
        val mult = if (won) HORSES[pickedHorse - 1].multiplier(bet) else 0.0
        return Race(
            finishingOrder = order,
            bet = bet,
            pickedHorse = pickedHorse,
            multiplier = mult,
        )
    }

    /**
     * Plackett–Luce sample of a finishing order. The first finisher is
     * drawn with probabilities proportional to each horse's strength
     * (set equal to its [HorseProfile.winProb]). After each pick the
     * winner is removed and the remaining strengths are renormalised
     * for the next position, so favourites land on the podium more
     * often than just their win probability suggests — exactly the
     * joint distribution Place/Show payouts are calibrated against.
     */
    private fun sampleFinishingOrder(random: Random): List<Int> {
        val remaining = HORSES.toMutableList()
        val order = ArrayList<Int>(HORSES.size)
        while (remaining.isNotEmpty()) {
            val total = remaining.sumOf { it.winProb }
            var draw = random.nextDouble() * total
            var idx = 0
            // Walk the cumulative weights; due to FP rounding the last
            // horse is the safe fallback if draw never crosses the
            // accumulating sum.
            while (idx < remaining.size - 1) {
                draw -= remaining[idx].winProb
                if (draw <= 0.0) break
                idx++
            }
            order.add(remaining[idx].index)
            remaining.removeAt(idx)
        }
        return order
    }

    companion object {
        const val MIN_STAKE: Long = 10L
        const val MAX_STAKE: Long = 500L

        /**
         * Six horses, favourite (H1) through longshot (H6). Win
         * probabilities sum to 1.0 by construction. Payout multipliers
         * are calibrated so every (horse, bet-type) pair returns ~0.92
         * RTP under Plackett–Luce sampling — pinned by [HorseRacingTest].
         *
         * Horse Racing is structurally jackpot-ineligible
         * (`JackpotGame.HORSE_RACING.eligibleForJackpot = false`,
         * mirroring HIGHLOW's carve-out) because Show-on-favourite wins
         * ~75% of races — high enough that a farmer staking at anchor
         * on H1-Show could flip the −10 c/race base EV into positive
         * expected value once the pool grows past a few thousand
         * credits. The RTP gate is the wrong proxy (filters by house
         * edge, not win rate), so the carve-out is structural and
         * losses still tribute into the pool.
         *
         * Lookup is 1-indexed externally (the slash command and web
         * surface both expose H1..H6) — when reading this list use
         * `HORSES[index - 1]`.
         */
        val HORSES: List<HorseProfile> = listOf(
            HorseProfile(1, "Thunderbolt", "🐎", winProb = 0.30, winMult = 3.1, placeMult = 1.7, showMult = 1.2),
            HorseProfile(2, "Silver Streak", "🐴", winProb = 0.22, winMult = 4.2, placeMult = 2.1, showMult = 1.5),
            HorseProfile(3, "Midnight Star", "🐎", winProb = 0.18, winMult = 5.1, placeMult = 2.5, showMult = 1.6),
            HorseProfile(4, "Sun Dancer", "🐴", winProb = 0.13, winMult = 7.1, placeMult = 3.3, showMult = 2.0),
            HorseProfile(5, "Iron Hoof", "🐎", winProb = 0.10, winMult = 9.2, placeMult = 4.3, showMult = 2.5),
            HorseProfile(6, "Wild Card", "🐴", winProb = 0.07, winMult = 13.1, placeMult = 6.0, showMult = 3.4),
        )

        val FIELD_SIZE: Int = HORSES.size

        /** Pure predicate: does the picked horse satisfy [bet] given [finishingOrder]? */
        fun wins(pickedHorse: Int, bet: Bet, finishingOrder: List<Int>): Boolean {
            val topN = when (bet) {
                Bet.WIN -> 1
                Bet.PLACE -> 2
                Bet.SHOW -> 3
            }
            return finishingOrder.take(topN).contains(pickedHorse)
        }

        fun horse(index: Int): HorseProfile = HORSES[index - 1]
    }
}

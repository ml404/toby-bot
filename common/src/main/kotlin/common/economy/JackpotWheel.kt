package common.economy

import kotlin.random.Random

/**
 * Tiered weighted-random payout wheel spun when a casino-game win
 * rolls into the jackpot. Replaces the prior fixed-percent payout
 * with a "compromise" mechanic: most spins give a small slice of the
 * pool, rare spins give a bigger one. Pool growth from trade fees and
 * loss tribute outpaces average drain, so the rare top-tier slice
 * compounds the longer it goes unhit.
 *
 * Configuration travels as a single CSV string in the per-guild
 * `config` table under [database.dto.guild.ConfigDto.Configurations.JACKPOT_WHEEL_SEGMENTS]:
 *
 *     "weight:payoutPct,weight:payoutPct,..."
 *
 *   - weight     positive integer; relative draw frequency (not a percent)
 *   - payoutPct  whole-number percent of the pool (1..100) paid when the
 *                segment is picked
 *
 * The default ([DEFAULT_SEGMENTS]) is `80:1,10:5,5:10,4:20,1:50` — 80%
 * of jackpot wins pay 1% of the pool (pity), 1% pay 50% (mega). EV per
 * win is ~3.1% of the pool.
 *
 * To express a flat fixed-percent payout (the behaviour the deleted
 * `JACKPOT_PAYOUT_PCT` config used to provide), set a single segment,
 * e.g. `1:30` for "always 30% of the pool".
 *
 * Weighted-pick mirrors the [SlotMachine] reel pattern: weights expand
 * into a flat list of segment indices and a single `random.nextInt`
 * picks one. Safer than a cumulative-walk for the small segment counts
 * (≤ [MAX_SEGMENTS]) and keeps the seeded-RNG tests reproducible.
 */
object JackpotWheel {

    /**
     * One wedge of the wheel. [weight] is a relative integer (the
     * segment is picked with probability `weight / Σweights`).
     * [payoutPct] is a fraction in `(0, 1]` of the pool — already
     * normalised from the CSV's whole-number percent.
     */
    data class Segment(val weight: Int, val payoutPct: Double) {
        init {
            require(weight > 0) { "segment weight must be positive: $weight" }
            require(payoutPct > 0.0 && payoutPct <= 1.0) {
                "segment payoutPct must be in (0,1]: $payoutPct"
            }
        }
    }

    /**
     * Result of [spin] — the picked index plus the segment's fraction,
     * so callers don't have to look it up against the segment list.
     */
    data class Spin(val tierIndex: Int, val payoutPct: Double)

    /**
     * Hard cap on segment count. Keeps the config-row size bounded
     * (a 12-segment wheel serialises to ~70 bytes) and keeps the
     * expanded-pick array small.
     */
    const val MAX_SEGMENTS: Int = 12

    /**
     * Default wheel composition. 80% of jackpot wins pay 1% of the
     * pool, 10% pay 5%, 5% pay 10%, 4% pay 20%, 1% pay 50%. EV per
     * win ≈ 3.1% of the pool — well under historic 100% so the pool
     * grows under typical traffic.
     */
    const val DEFAULT_SEGMENTS: String = "80:1,10:5,5:10,4:20,1:50"

    private val PARSED_DEFAULT: List<Segment> = requireNotNull(tryParse(DEFAULT_SEGMENTS)) {
        "DEFAULT_SEGMENTS must parse cleanly"
    }

    /**
     * Parse [csv] into segments, falling back to [PARSED_DEFAULT] when
     * [csv] is null, blank, malformed, or fails validation. Live reader
     * path — never throws.
     */
    fun parse(csv: String?): List<Segment> = tryParse(csv) ?: PARSED_DEFAULT

    /**
     * Pure-validation entry point used by the moderation save path.
     * Returns `null` when [csv] parses + validates cleanly, or a
     * human-readable error string when it doesn't. An empty/blank value
     * is treated as "reset to default" — also returns null.
     */
    fun validateConfigString(csv: String?): String? {
        if (csv.isNullOrBlank()) return null
        val pairs = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (pairs.isEmpty()) return "Provide at least one weight:pct segment."
        if (pairs.size > MAX_SEGMENTS) {
            return "Too many segments (max $MAX_SEGMENTS); given ${pairs.size}."
        }
        var totalWeight = 0L
        for ((i, pair) in pairs.withIndex()) {
            val parts = pair.split(':').map { it.trim() }
            if (parts.size != 2) {
                return "Segment ${i + 1} must be 'weight:pct' (got '$pair')."
            }
            val w = parts[0].toIntOrNull()
                ?: return "Segment ${i + 1} weight must be a positive integer (got '${parts[0]}')."
            if (w <= 0) return "Segment ${i + 1} weight must be > 0 (got $w)."
            val pct = parts[1].toIntOrNull()
                ?: return "Segment ${i + 1} pct must be an integer 1-100 (got '${parts[1]}')."
            if (pct !in 1..100) return "Segment ${i + 1} pct must be 1-100 (got $pct)."
            totalWeight += w
        }
        if (totalWeight <= 0L) return "Total weight must be > 0."
        return null
    }

    private fun tryParse(csv: String?): List<Segment>? {
        if (csv.isNullOrBlank()) return null
        val parts = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_SEGMENTS) return null
        val out = ArrayList<Segment>(parts.size)
        for (pair in parts) {
            val kv = pair.split(':').map { it.trim() }
            if (kv.size != 2) return null
            val w = kv[0].toIntOrNull() ?: return null
            val pct = kv[1].toIntOrNull() ?: return null
            if (w <= 0 || pct !in 1..100) return null
            out.add(Segment(weight = w, payoutPct = pct / 100.0))
        }
        return out
    }

    /**
     * Pick one segment by weight. The expanded-list approach keeps the
     * call to a single [Random.nextInt] for reproducibility under a
     * seeded RNG.
     */
    fun spin(segments: List<Segment>, random: Random): Spin {
        require(segments.isNotEmpty()) { "cannot spin an empty wheel" }
        val total = segments.sumOf { it.weight }
        require(total > 0) { "total weight must be positive" }
        val draw = random.nextInt(total)
        var running = 0
        for ((index, seg) in segments.withIndex()) {
            running += seg.weight
            if (draw < running) return Spin(tierIndex = index, payoutPct = seg.payoutPct)
        }
        // Unreachable given `running` ends at `total` and `draw < total`.
        val last = segments.lastIndex
        return Spin(tierIndex = last, payoutPct = segments[last].payoutPct)
    }
}

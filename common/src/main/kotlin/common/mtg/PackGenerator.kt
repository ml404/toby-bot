package common.mtg

import kotlin.random.Random

/**
 * Step 2–3 of the cube-prep tool from the doc: take a card pool, pull a
 * random subset, and deal it into evenly-sized booster packs while
 * keeping the as-fan distribution (colours / colourless / lands) as level
 * across packs as possible.
 *
 * The "balanced" deal groups the selected cards into contiguous
 * [CardCategory] blocks and round-robins them into packs, so each
 * category's cards land on consecutive packs and every pack ends up
 * within one card of every other for each category — near the cube's
 * overall as-fan, with no pack all-red or land-flooded by luck of the
 * shuffle. The "unbalanced" deal is a plain shuffle-and-split, kept for
 * callers that want raw randomness.
 */
class PackGenerator(private val random: Random = Random.Default) {

    data class Packs(
        val packs: List<List<CubeCard>>,
        val packSize: Int,
    ) {
        val packCount: Int get() = packs.size

        /** Every dealt card, flattened — the realised cube subset. */
        val cards: List<CubeCard> get() = packs.flatten()
    }

    sealed interface Result {
        data class Success(val value: Packs) : Result
        data class Failure(val reason: String) : Result
    }

    /**
     * Deals [packCount] packs of [packSize] cards drawn from [pool].
     *
     * @param balanced when true, spreads each [CardCategory] as evenly as
     *   possible across the packs; when false, deals a plain shuffle.
     */
    fun generate(
        pool: List<CubeCard>,
        packCount: Int,
        packSize: Int,
        balanced: Boolean = true,
    ): Result {
        if (packCount <= 0) return Result.Failure("Pack count must be at least 1 (was $packCount).")
        if (packSize <= 0) return Result.Failure("Pack size must be at least 1 (was $packSize).")
        // Long multiply: packCount × packSize can overflow Int (e.g. a crafted
        // web request with packs=2^30, packSize=4 wraps to 0 and would slip past
        // this guard into a huge List(packCount) allocation — an OOM). With the
        // pool check below, anything that passes has needed ≤ pool.size, so it
        // safely fits back in an Int.
        val needed = packCount.toLong() * packSize.toLong()
        if (pool.size < needed) {
            return Result.Failure(
                "Not enough cards: need $needed ($packCount × $packSize) but the pool only has ${pool.size}."
            )
        }

        // Randomly select the exact number of cards we'll deal.
        val selected = pool.shuffled(random).take(needed.toInt())

        // The stream we deal from. Balanced → cards grouped into contiguous
        // category blocks (still shuffled within each block, via the stable
        // sort over the already-shuffled selection); unbalanced → as-shuffled.
        val stream = if (balanced) selected.sortedBy { it.category.ordinal } else selected

        // Deal the stream round-robin with a continuing pointer: card i goes
        // to pack i % packCount. Because `needed` is an exact multiple of
        // packCount every pack gets exactly packSize cards. And because each
        // category occupies a contiguous run, that run lands on consecutive
        // packs (mod packCount), so every pack's count of any one category
        // differs from every other pack's by at most one — level as-fan.
        val buckets = List(packCount) { mutableListOf<CubeCard>() }
        stream.forEachIndexed { i, card -> buckets[i % packCount].add(card) }

        // Shuffle within each pack so the deal order isn't visible to a
        // drafter opening the pack.
        val packs = buckets.map { it.shuffled(random) }
        return Result.Success(Packs(packs, packSize))
    }
}

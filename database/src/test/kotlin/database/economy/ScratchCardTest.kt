package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ScratchCardTest {

    @Test
    fun `scratch always returns 9 cells`() {
        val card = ScratchCard()
        val rng = Random(42)
        repeat(1_000) {
            val result = card.scratch(rng)
            assertEquals(ScratchCard.CELL_COUNT, result.cells.size)
            assertTrue(result.multiplier >= 0L)
        }
    }

    @Test
    fun `nine-of-a-kind cherry pays base cherry times 5`() {
        // Reel of only cherries → all 9 cells will be cherries → match=9.
        // multiplier = base 1 × (9 - (5-1)) = 5.
        val card = ScratchCard(reel = listOf(SlotMachine.Symbol.CHERRY))
        val result = card.scratch(Random(0))
        assertTrue(result.isWin)
        assertEquals(SlotMachine.Symbol.CHERRY, result.winningSymbol)
        assertEquals(9, result.matchCount)
        assertEquals(5L, result.multiplier, "9 cherries × base 1 × (9-4) = 5")
    }

    @Test
    fun `five-of-a-kind cherry is a stake-refund push`() {
        // 5🍒 sits at the boundary: multiplier = 1 → payout = stake →
        // net = 0. Intentional design — cherry is the cheapest symbol and
        // its lowest tier returns the stake rather than paying out. This
        // is what keeps cherry distinct from lemon at k=5 (5🍋 = 2×) while
        // respecting the closed-form RTP target.
        assertEquals(1L, ScratchCard.multiplierFor(SlotMachine.Symbol.CHERRY, 5))
        assertEquals(2L, ScratchCard.multiplierFor(SlotMachine.Symbol.LEMON, 5))
    }

    @Test
    fun `at each match-count every symbol pays a distinct multiplier`() {
        // Regression for the floor-collision bug: the prior `max(2, base × (k-4))`
        // wrapper made 5🍒 and 5🍋 both pay 2×, so players couldn't tell
        // them apart at the most common winning tier. The pure formula
        // separates the four symbols at every k. (Cross-k collisions like
        // 6🍒=2=5🍋 are fine — within a single card a player only sees one
        // matchCount.)
        for (k in ScratchCard.MATCH_THRESHOLD..ScratchCard.CELL_COUNT) {
            val perSymbol = SlotMachine.Symbol.entries.associateWith {
                ScratchCard.multiplierFor(it, k)
            }
            val unique = perSymbol.values.toSet()
            assertEquals(
                perSymbol.size,
                unique.size,
                "at $k matches, multipliers $perSymbol have collisions — symbols indistinguishable"
            )
        }
    }

    @Test
    fun `nine-of-a-kind star pays the rare payout scaled`() {
        val card = ScratchCard(reel = listOf(SlotMachine.Symbol.STAR))
        val result = card.scratch(Random(0))
        assertEquals(SlotMachine.Symbol.STAR, result.winningSymbol)
        assertEquals(9, result.matchCount)
        // 9 stars × base 40 × (9-4) = 200.
        assertEquals(200L, result.multiplier)
    }

    @Test
    fun `no 5-of-a-kind is a loss`() {
        // With the default weighted reel (4🍒+3🍋+2🔔+1⭐) and 9 cells,
        // sub-5 cards hit ~61% of the time, so seed 0 already produces
        // one. Iterating to 200 keeps this future-proof against rng
        // ordering shifts.
        val card = ScratchCard()
        for (seed in 0L..200L) {
            val result = card.scratch(Random(seed))
            if (!result.isWin) {
                assertFalse(result.isWin)
                assertNull(result.winningSymbol)
                assertEquals(0, result.matchCount)
                assertEquals(0L, result.multiplier)
                return
            }
        }
        error("expected at least one no-match draw in 200 seeds")
    }

    @Test
    fun `multiplierFor matches the live scratch path`() {
        // Single source of truth: the helper used by UI payout tables and
        // the live scratch() draw must produce the same multiplier for the
        // same (symbol, matchCount). Fix a single-symbol reel so we can
        // observe the live formula at k=CELL_COUNT and cross-check it.
        for (symbol in SlotMachine.Symbol.entries) {
            val card = ScratchCard(reel = listOf(symbol))
            val live = card.scratch(Random(0))
            val viaHelper = ScratchCard.multiplierFor(symbol, ScratchCard.CELL_COUNT)
            assertEquals(viaHelper, live.multiplier, "$symbol live vs helper drift")
        }
        // Sub-threshold returns 0 (helper is the only path that can be
        // queried at a non-winning matchCount; the live path always reports
        // 0 multiplier when no symbol hits MATCH_THRESHOLD).
        assertEquals(0L, ScratchCard.multiplierFor(SlotMachine.Symbol.STAR, ScratchCard.MATCH_THRESHOLD - 1))
    }

    @Test
    fun `multipliers are monotonic non-decreasing in k for every symbol`() {
        // Soft guard against a future tuning that accidentally inverts the
        // payout curve (e.g. negative base, or a typo that makes 6-of-a-kind
        // pay less than 5). Strict monotonicity isn't required — what
        // matters is that more matches never pays less.
        for (symbol in SlotMachine.Symbol.entries) {
            var prev = Long.MIN_VALUE
            for (k in ScratchCard.MATCH_THRESHOLD..ScratchCard.CELL_COUNT) {
                val mult = ScratchCard.multiplierFor(symbol, k)
                assertTrue(mult >= prev, "$symbol $k-of-a-kind multiplier $mult below k-1's $prev")
                prev = mult
            }
        }
    }

    @Test
    fun `expected RTP lands in target band`() {
        // Closed-form RTP via the binomial-PMF helper: deterministic, no
        // Monte Carlo. With current bases (1/2/8/40) on the default reel
        // the math lands ~0.875 — a 12.5% house edge. The lower bound
        // catches accidental payout-zero / threshold-too-high; the upper
        // bound is the real guard, ensuring tuning changes can't push RTP
        // back over break-even and have users always winning.
        val rtp = ScratchCard.expectedRtp()
        assertTrue(rtp in 0.85..0.92, "RTP $rtp outside target band 0.85..0.92")
    }
}

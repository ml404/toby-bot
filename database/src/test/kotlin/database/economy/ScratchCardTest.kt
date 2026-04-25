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
        // multiplier = base 1 × (9 - (5-1)) = 1 × 5 = 5.
        val card = ScratchCard(reel = listOf(SlotMachine.Symbol.CHERRY))
        val result = card.scratch(Random(0))
        assertTrue(result.isWin)
        assertEquals(SlotMachine.Symbol.CHERRY, result.winningSymbol)
        assertEquals(9, result.matchCount)
        assertEquals(5L, result.multiplier, "9 cherries × base 1 × (9-4) = 5")
    }

    @Test
    fun `nine-of-a-kind star pays the rare payout scaled`() {
        val card = ScratchCard(reel = listOf(SlotMachine.Symbol.STAR))
        val result = card.scratch(Random(0))
        assertEquals(SlotMachine.Symbol.STAR, result.winningSymbol)
        assertEquals(9, result.matchCount)
        // 9 stars × base 40 × (9-4) = 200
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
    fun `RTP across 200k cards lands in casino-style range`() {
        // ScratchCard's RTP isn't a clean closed-form — it depends on the
        // multinomial over the 4-symbol weighted reel × the multiplier
        // table. With the 5-of-a-kind threshold on 9 cells and the current
        // bases (1/2/8/40) the closed-form math lands ~0.875. ±10pp around
        // that range catches gross misconfiguration (back-to-3-of-a-kind
        // would overshoot 1.7×; payout-zero would undershoot to 0).
        val card = ScratchCard()
        val rng = Random(2026)
        val stake = 1_000L
        val n = 200_000
        var totalWagered = 0L
        var totalReturned = 0L
        repeat(n) {
            val result = card.scratch(rng)
            totalWagered += stake
            totalReturned += result.multiplier * stake
        }
        val rtp = totalReturned.toDouble() / totalWagered.toDouble()
        assertTrue(rtp in 0.78..0.97, "RTP $rtp outside expected casino range")
    }
}

package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ScratchCardTest {

    @Test
    fun `scratch always returns 5 cells`() {
        val card = ScratchCard()
        val rng = Random(42)
        repeat(1_000) {
            val result = card.scratch(rng)
            assertEquals(ScratchCard.CELL_COUNT, result.cells.size)
            assertTrue(result.multiplier >= 0L)
        }
    }

    @Test
    fun `five-of-a-kind cherry pays base cherry times 2`() {
        // Reel of only cherries → all 5 cells will be cherries → match=5.
        // multiplier = base 5 × (5 - (4-1)) = 5 × 2 = 10.
        val card = ScratchCard(reel = listOf(SlotMachine.Symbol.CHERRY))
        val result = card.scratch(Random(0))
        assertTrue(result.isWin)
        assertEquals(SlotMachine.Symbol.CHERRY, result.winningSymbol)
        assertEquals(5, result.matchCount)
        assertEquals(10L, result.multiplier, "5 cherries × base 5 × (5-3) = 10")
    }

    @Test
    fun `five-of-a-kind star pays the rare payout scaled`() {
        val card = ScratchCard(reel = listOf(SlotMachine.Symbol.STAR))
        val result = card.scratch(Random(0))
        assertEquals(SlotMachine.Symbol.STAR, result.winningSymbol)
        assertEquals(5, result.matchCount)
        // 5 stars × base 90 × (5-3) = 180
        assertEquals(180L, result.multiplier)
    }

    @Test
    fun `no 4-of-a-kind is a loss`() {
        // With the default weighted reel (4🍒+3🍋+2🔔+1⭐) and 5 cells,
        // sub-4 cards hit ~88% of the time, so seed 0 already produces
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
        // table. With the 4-of-a-kind threshold and the current bases
        // (5/7/22/90) we expect ~0.85-0.91 RTP. ±10pp around that range
        // catches gross misconfiguration (back-to-3-of-a-kind would
        // overshoot 1.7×; payout-zero would undershoot to 0).
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
        assertTrue(rtp in 0.75..1.0, "RTP $rtp outside expected casino range")
    }
}

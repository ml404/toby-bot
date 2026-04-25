package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SlotMachineTest {

    @Test
    fun `pull always returns 3 symbols and a non-negative multiplier`() {
        val machine = SlotMachine()
        val rng = Random(42)
        repeat(1_000) {
            val pull = machine.pull(rng)
            assertEquals(SlotMachine.REEL_COUNT, pull.symbols.size)
            assertTrue(pull.multiplier >= 0L, "multiplier must never go negative")
        }
    }

    @Test
    fun `three of the same symbol returns the configured multiplier for that symbol`() {
        SlotMachine.Symbol.entries.forEach { symbol ->
            val singleSymbolReel = listOf(symbol)
            val machine = SlotMachine(reel = singleSymbolReel, payouts = SlotMachine.DEFAULT_PAYOUTS)
            val pull = machine.pull(Random(1))
            assertEquals(listOf(symbol, symbol, symbol), pull.symbols)
            assertEquals(SlotMachine.DEFAULT_PAYOUTS.getValue(symbol), pull.multiplier)
            assertTrue(pull.isWin, "3-of-a-kind must be a win")
        }
    }

    @Test
    fun `mixed symbols return zero multiplier`() {
        // Reel where draws will rotate through different symbols, ensuring
        // mismatched results.
        val mixedReel = listOf(SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.LEMON, SlotMachine.Symbol.BELL)
        val machine = SlotMachine(reel = mixedReel, payouts = SlotMachine.DEFAULT_PAYOUTS)
        val rng = Random(123)
        var sawMix = false
        repeat(200) {
            val pull = machine.pull(rng)
            if (pull.symbols.toSet().size > 1) {
                sawMix = true
                assertEquals(0L, pull.multiplier, "mixed symbols must lose")
                assertTrue(!pull.isWin)
            }
        }
        assertTrue(sawMix, "expected at least one mixed-symbol pull in 200 draws")
    }

    @Test
    fun `RTP across 100k pulls is within +- 5 percent of 0_89`() {
        // The machine's expected return-to-player is 0.890 with the default
        // weights and payouts (see SlotMachine kdoc). With n = 100k the 95%
        // CI is much tighter than ±0.05; the loose ±5pp bound is a forgiving
        // floor that won't flake on CI.
        val machine = SlotMachine()
        val rng = Random(2026)
        val stake = 1_000L
        val n = 100_000
        var totalWagered = 0L
        var totalReturned = 0L
        repeat(n) {
            val pull = machine.pull(rng)
            totalWagered += stake
            totalReturned += pull.multiplier * stake
        }
        val rtp = totalReturned.toDouble() / totalWagered.toDouble()
        assertTrue(
            rtp in 0.84..0.94,
            "expected RTP near 0.890 (±0.05) across $n pulls but saw $rtp"
        )
    }
}

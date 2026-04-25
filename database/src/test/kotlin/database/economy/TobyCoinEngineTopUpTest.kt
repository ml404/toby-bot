package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-math tests for the sell-to-cover-shortfall helper used by
 * [web.service.TitlesWebService] and [database.service.CasinoTopUpHelper].
 * Pinned to the same sale arithmetic [database.service.EconomyTradeService]
 * applies — drift between the two would mean the chosen N would land
 * a few credits short of the shortfall after the fee + slippage land,
 * which is exactly what these helpers are written to prevent.
 */
class TobyCoinEngineTopUpTest {

    @Test
    fun `proceedsForSell matches the trade engine math`() {
        // Reference values: P=2.5, N=205. midpoint = (2.5 + 2.5*0.9795)/2
        // = 2.474375. gross = floor(2.474375 * 205) = 507. fee = floor(5.07) = 5.
        assertEquals(502L, TobyCoinEngine.proceedsForSell(2.5, 205))
    }

    @Test
    fun `proceedsForSell is zero when coins is non-positive`() {
        assertEquals(0L, TobyCoinEngine.proceedsForSell(2.5, 0))
        assertEquals(0L, TobyCoinEngine.proceedsForSell(2.5, -10))
    }

    @Test
    fun `proceedsForSell is zero when price is zero`() {
        assertEquals(0L, TobyCoinEngine.proceedsForSell(0.0, 100))
    }

    @Test
    fun `coinsNeededForShortfall covers cleanly when ceil already works`() {
        // Tiny shortfall, large price — no slippage / fee bump needed.
        assertEquals(1L, TobyCoinEngine.coinsNeededForShortfall(50L, 100.0))
    }

    @Test
    fun `coinsNeededForShortfall bumps for fee + slippage`() {
        // Reference: shortfall=500, P=2.5. ceil=200 nets 491; bumps to 205.
        assertEquals(205L, TobyCoinEngine.coinsNeededForShortfall(500L, 2.5))
    }

    @Test
    fun `coinsNeededForShortfall returns the true required N regardless of caller balance`() {
        // The engine helper doesn't peek at the user — caller compares
        // against their balance and surfaces "insufficient coins".
        val needed = TobyCoinEngine.coinsNeededForShortfall(1_000L, 1.0)
        assertTrue(needed >= 1_000L, "1000-credit shortfall at price=1 needs at least 1000 coins (was $needed)")
    }

    @Test
    fun `coinsNeededForShortfall is zero on a zero shortfall`() {
        assertEquals(0L, TobyCoinEngine.coinsNeededForShortfall(0L, 100.0))
    }
}

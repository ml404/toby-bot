package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

internal class TobyCoinEngineTest {

    @Test
    fun `tickRandomWalk is reproducible with the same seed`() {
        val a = TobyCoinEngine.tickRandomWalk(100.0, random = Random(42))
        val b = TobyCoinEngine.tickRandomWalk(100.0, random = Random(42))
        assertEquals(a, b, 1e-9)
    }

    @Test
    fun `tickRandomWalk moves the price away from the start value`() {
        val moved = (1..50).map { seed ->
            TobyCoinEngine.tickRandomWalk(100.0, random = Random(seed.toLong()))
        }
        assertTrue(moved.any { it != 100.0 }, "at least one tick should shift the price")
    }

    @Test
    fun `tickRandomWalk never returns below the price floor`() {
        var price = 1.5
        repeat(200) { price = TobyCoinEngine.tickRandomWalk(price, random = Random(it.toLong())) }
        assertTrue(price >= TobyCoinEngine.PRICE_FLOOR)
    }

    @Test
    fun `applyBuyPressure raises price and applySellPressure lowers it`() {
        val base = 100.0
        val up = TobyCoinEngine.applyBuyPressure(base, 10)
        val down = TobyCoinEngine.applySellPressure(base, 10)
        assertTrue(up > base, "buy should push price up")
        assertTrue(down < base, "sell should push price down")
    }

    @Test
    fun `applySellPressure respects the price floor for extreme trades`() {
        val result = TobyCoinEngine.applySellPressure(2.0, 1_000_000L)
        assertEquals(TobyCoinEngine.PRICE_FLOOR, result, 1e-9)
    }
}

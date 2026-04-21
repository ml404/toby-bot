package web.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DiceExpressionRollerTest {

    @Test
    fun `parseAmount accepts bare integer`() {
        val r = DiceExpressionRoller.parseAmount("45")
        assertEquals(45, r?.total)
        assertNull(r?.expression)
        assertNull(r?.rolls)
    }

    @Test
    fun `parseAmount rejects negative integer`() {
        assertNull(DiceExpressionRoller.parseAmount("-1"))
    }

    @Test
    fun `parseAmount rejects integer above literal cap`() {
        assertNull(DiceExpressionRoller.parseAmount("${DiceExpressionRoller.MAX_LITERAL_AMOUNT + 1}"))
    }

    @Test
    fun `parseAmount rolls dice expression within range`() {
        repeat(50) {
            val r = DiceExpressionRoller.parseAmount("3d20+30", Random(it.toLong()))
            assertNotNull(r)
            assertEquals("3d20+30", r!!.expression)
            assertEquals(3, r.rolls?.size)
            assertTrue(r.total in 33..90, "Total ${r.total} should be in 33..90")
        }
    }

    @Test
    fun `parseAmount with implicit count one`() {
        val r = DiceExpressionRoller.parseAmount("d20", Random(7))
        assertNotNull(r)
        assertEquals(1, r!!.rolls?.size)
        assertEquals("1d20", r.expression)
        assertTrue(r.total in 1..20)
    }

    @Test
    fun `parseAmount accepts negative modifier`() {
        val r = DiceExpressionRoller.parseAmount("1d4-2", Random(0))
        assertNotNull(r)
        assertEquals("1d4-2", r!!.expression)
        assertTrue(r.total >= 0, "Total ${r.total} clamped to >= 0")
    }

    @Test
    fun `parseAmount rejects disallowed die sides`() {
        assertNull(DiceExpressionRoller.parseAmount("1d7"))
        assertNull(DiceExpressionRoller.parseAmount("1d13"))
    }

    @Test
    fun `parseAmount rejects dice count over cap`() {
        assertNull(DiceExpressionRoller.parseAmount("99d20"))
    }

    @Test
    fun `parseAmount rejects modifier over cap`() {
        assertNull(DiceExpressionRoller.parseAmount("1d20+999"))
    }

    @Test
    fun `parseAmount rejects garbage`() {
        assertNull(DiceExpressionRoller.parseAmount("garbage"))
        assertNull(DiceExpressionRoller.parseAmount(""))
        assertNull(DiceExpressionRoller.parseAmount("d"))
    }

    @Test
    fun `parseAmount tolerates surrounding whitespace and case`() {
        val r = DiceExpressionRoller.parseAmount("  2D6+3  ", Random(1))
        assertNotNull(r)
        assertEquals("2d6+3", r!!.expression)
        assertTrue(r.total in 5..15)
    }
}

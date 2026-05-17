package common.leveling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LevelCurveTest {

    @Test
    fun `xpForNextLevel matches the MEE6-style polynomial`() {
        // 5n^2 + 50n + 100
        assertEquals(100L, LevelCurve.xpForNextLevel(0))
        assertEquals(155L, LevelCurve.xpForNextLevel(1))
        assertEquals(220L, LevelCurve.xpForNextLevel(2))
        assertEquals(295L, LevelCurve.xpForNextLevel(3))
    }

    @Test
    fun `cumulativeXpForLevel sums the curve`() {
        assertEquals(0L, LevelCurve.cumulativeXpForLevel(0))
        assertEquals(100L, LevelCurve.cumulativeXpForLevel(1))
        assertEquals(255L, LevelCurve.cumulativeXpForLevel(2))
        assertEquals(475L, LevelCurve.cumulativeXpForLevel(3))
        assertEquals(770L, LevelCurve.cumulativeXpForLevel(4))
    }

    @Test
    fun `levelForXp returns 0 for non-positive XP`() {
        assertEquals(0, LevelCurve.levelForXp(-1L))
        assertEquals(0, LevelCurve.levelForXp(0L))
        assertEquals(0, LevelCurve.levelForXp(99L))
    }

    @Test
    fun `levelForXp transitions at exact thresholds`() {
        // 100 XP -> level 1 starts.
        assertEquals(1, LevelCurve.levelForXp(100L))
        assertEquals(1, LevelCurve.levelForXp(254L))
        // 255 XP -> level 2 starts.
        assertEquals(2, LevelCurve.levelForXp(255L))
        assertEquals(2, LevelCurve.levelForXp(474L))
        // 475 XP -> level 3 starts.
        assertEquals(3, LevelCurve.levelForXp(475L))
    }

    @Test
    fun `progress returns the in-level offset and the curve for that level`() {
        val p = LevelCurve.progress(300L) // level 2, 300-255 = 45 XP into level 2.
        assertEquals(2, p.level)
        assertEquals(45L, p.xpIntoLevel)
        assertEquals(220L, p.xpForNextLevel)
        assertEquals(220L - 45L, p.xpRemaining)
    }

    @Test
    fun `progress at exact threshold puts the user at the start of the new level`() {
        val p = LevelCurve.progress(255L)
        assertEquals(2, p.level)
        assertEquals(0L, p.xpIntoLevel)
        assertEquals(220L, p.xpForNextLevel)
    }

    @Test
    fun `levels grow but never go backwards`() {
        var lastLevel = 0
        var xp = 0L
        repeat(50) {
            xp += 1000L
            val level = LevelCurve.levelForXp(xp)
            assertTrue(level >= lastLevel, "level must be monotonic with xp")
            lastLevel = level
        }
    }
}

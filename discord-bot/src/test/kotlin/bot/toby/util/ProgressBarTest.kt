package bot.toby.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProgressBarTest {

    @Test
    fun `default cell count produces 20 visible cells`() {
        val bar = ProgressBar.render(0L, 60_000L)
        // Each cell is one Unicode code point; using length on a String that may include
        // multi-char emoji handles works for our chosen single-codepoint glyphs.
        // Just count by codePointCount to be safe.
        val cells = bar.codePointCount(0, bar.length)
        assertEquals(20, cells)
    }

    @Test
    fun `zero position puts handle at the start`() {
        val bar = ProgressBar.render(0L, 60_000L, cells = 10)
        // First codepoint should be the handle 🔘
        val firstCp = bar.codePointAt(0)
        assertEquals("🔘".codePointAt(0), firstCp)
    }

    @Test
    fun `full position puts handle at the end`() {
        val bar = ProgressBar.render(60_000L, 60_000L, cells = 10)
        val cps = bar.codePoints().toArray()
        assertEquals(10, cps.size)
        assertEquals("🔘".codePointAt(0), cps.last())
    }

    @Test
    fun `mid position puts handle near the middle`() {
        val bar = ProgressBar.render(5_000L, 10_000L, cells = 10)
        val cps = bar.codePoints().toArray()
        val handleIdx = cps.indexOfFirst { it == "🔘".codePointAt(0) }
        assertTrue(handleIdx in 3..5, "expected handle near middle (3..5), got $handleIdx")
    }

    @Test
    fun `position past duration is clamped to end`() {
        val bar = ProgressBar.render(99_999L, 60_000L, cells = 10)
        val cps = bar.codePoints().toArray()
        assertEquals("🔘".codePointAt(0), cps.last())
    }

    @Test
    fun `negative position is clamped to start`() {
        val bar = ProgressBar.render(-1000L, 60_000L, cells = 10)
        val cps = bar.codePoints().toArray()
        assertEquals("🔘".codePointAt(0), cps.first())
    }

    @Test
    fun `zero duration returns empty bar without handle`() {
        val bar = ProgressBar.render(0L, 0L, cells = 10)
        val cps = bar.codePoints().toArray()
        assertEquals(10, cps.size)
        // No handle present
        assertTrue(cps.none { it == "🔘".codePointAt(0) })
    }

    @Test
    fun `negative duration returns empty bar without handle`() {
        val bar = ProgressBar.render(1000L, -1L, cells = 10)
        val cps = bar.codePoints().toArray()
        assertTrue(cps.none { it == "🔘".codePointAt(0) })
    }

    @Test
    fun `zero cells throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProgressBar.render(0L, 10_000L, cells = 0)
        }
    }

    @Test
    fun `negative cells throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProgressBar.render(0L, 10_000L, cells = -5)
        }
    }
}

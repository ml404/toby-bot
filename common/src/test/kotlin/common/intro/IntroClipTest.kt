package common.intro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntroClipTest {

    // --- parseTimestamp -----------------------------------------------------

    @Test
    fun `blank input parses to no bound`() {
        assertEquals(IntroClip.Parsed.Ok(null), IntroClip.parseTimestamp(null))
        assertEquals(IntroClip.Parsed.Ok(null), IntroClip.parseTimestamp(""))
        assertEquals(IntroClip.Parsed.Ok(null), IntroClip.parseTimestamp("   "))
    }

    @Test
    fun `raw seconds parse to milliseconds`() {
        assertEquals(IntroClip.Parsed.Ok(12_000), IntroClip.parseTimestamp("12"))
        assertEquals(IntroClip.Parsed.Ok(12_500), IntroClip.parseTimestamp("12.5"))
        assertEquals(IntroClip.Parsed.Ok(0), IntroClip.parseTimestamp("0"))
    }

    @Test
    fun `mm ss parses to milliseconds`() {
        assertEquals(IntroClip.Parsed.Ok(72_000), IntroClip.parseTimestamp("1:12"))
        assertEquals(IntroClip.Parsed.Ok(3_500), IntroClip.parseTimestamp("0:03.5"))
        assertEquals(IntroClip.Parsed.Ok(630_000), IntroClip.parseTimestamp("10:30"))
    }

    @Test
    fun `malformed input is rejected with the field label`() {
        val result = IntroClip.parseTimestamp("abc", "Clip start")
        assertTrue(result is IntroClip.Parsed.Invalid)
        assertTrue((result as IntroClip.Parsed.Invalid).message.startsWith("Clip start"))
    }

    @Test
    fun `seconds component must be under sixty`() {
        assertTrue(IntroClip.parseTimestamp("1:60") is IntroClip.Parsed.Invalid)
        assertTrue(IntroClip.parseTimestamp("1:2:3") is IntroClip.Parsed.Invalid)
        assertTrue(IntroClip.parseTimestamp("-5") is IntroClip.Parsed.Invalid)
    }

    // --- format / describe --------------------------------------------------

    @Test
    fun `format pads seconds and keeps sub-second precision`() {
        assertEquals("0:03", IntroClip.format(3_000))
        assertEquals("1:12", IntroClip.format(72_000))
        assertEquals("0:03.5", IntroClip.format(3_500))
        assertEquals("", IntroClip.format(null))
    }

    @Test
    fun `format round-trips through parseTimestamp`() {
        listOf(0, 3_000, 12_500, 72_000, 630_000).forEach { ms ->
            assertEquals(IntroClip.Parsed.Ok(ms), IntroClip.parseTimestamp(IntroClip.format(ms)), "round-trip of $ms")
        }
    }

    @Test
    fun `describe covers every combination of bounds`() {
        assertEquals("full track", IntroClip.describe(null, null))
        assertEquals("0:03 – 0:12", IntroClip.describe(3_000, 12_000))
        assertEquals("from 0:03", IntroClip.describe(3_000, null))
        assertEquals("up to 0:12", IntroClip.describe(null, 12_000))
    }

    // --- validate -----------------------------------------------------------

    @Test
    fun `unclipped intro is fine when the source fits`() {
        assertNull(IntroClip.validate(null, null, 10_000))
        assertNull(IntroClip.validate(null, null, null))
    }

    @Test
    fun `unclipped intro is rejected when the source is longer than the cap`() {
        val error = IntroClip.validate(null, null, 60_000)
        assertNotNull(error)
        assertTrue(error!!.contains("set a start/end clip"), "should point the user at clipping: $error")
    }

    @Test
    fun `a tight clip rescues a long source`() {
        assertNull(IntroClip.validate(10_000, 22_000, 120_000))
    }

    @Test
    fun `clip longer than the cap is rejected`() {
        assertNotNull(IntroClip.validate(0, IntroClip.MAX_CLIP_DURATION_MS + 1, 60_000))
        assertNull(IntroClip.validate(0, IntroClip.MAX_CLIP_DURATION_MS, 60_000))
    }

    @Test
    fun `end must be after start and within the source`() {
        assertNotNull(IntroClip.validate(5_000, 5_000, 60_000))
        assertNotNull(IntroClip.validate(5_000, 3_000, 60_000))
        assertNotNull(IntroClip.validate(0, 20_000, 10_000))
        assertNotNull(IntroClip.validate(-1, 1_000, 60_000))
    }

    @Test
    fun `open-ended clip is measured against the rest of the source`() {
        // 120s source, start at 10s -> 110s of playback, way over the cap.
        assertNotNull(IntroClip.validate(10_000, null, 120_000))
        // Start late enough and the remainder fits.
        assertNull(IntroClip.validate(110_000, null, 120_000))
        // Unknown source duration can't be checked, so it passes.
        assertNull(IntroClip.validate(10_000, null, null))
    }
}

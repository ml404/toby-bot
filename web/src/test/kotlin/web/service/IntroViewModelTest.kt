package web.service

import common.intro.IntroHealth
import common.intro.IntroLoudness
import common.media.MediaToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The row's derived labels. These are what turned the intros page from
 * "everything here looks fine" into something that can say an intro stopped
 * working — the page previously rendered a link deleted months ago exactly
 * like a healthy one.
 */
class IntroViewModelTest {

    private fun row(
        failureCount: Int = 0,
        lastFailureReason: String? = null,
        playCount: Int = 0,
        measuredRms: Double? = null,
    ) = IntroViewModel(
        id = "1_2_1",
        index = 1,
        fileName = "track",
        introVolume = 90,
        url = "https://youtu.be/x",
        failureCount = failureCount,
        lastFailureReason = lastFailureReason,
        playCount = playCount,
        measuredRms = measuredRms,
    )

    @Test
    fun `a healthy row is not flagged`() {
        assertFalse(row().broken)
        assertFalse(row(failureCount = 1).broken)
    }

    @Test
    fun `repeated failures flag the row, on the same rule Discord uses`() {
        assertTrue(row(failureCount = IntroHealth.UNHEALTHY_AFTER_FAILURES).broken)
    }

    @Test
    fun `the failure reason falls back to something readable`() {
        assertEquals("Source could not be loaded", row(failureCount = 2).brokenReason)
        assertEquals("Video unavailable", row(failureCount = 2, lastFailureReason = "Video unavailable").brokenReason)
    }

    @Test
    fun `a never-played intro shows no play count`() {
        // "Played 0×" is noise, and every pre-existing row starts at zero
        // because counting only began with this change.
        assertNull(row().playLabel)
        assertEquals("Played 4×", row(playCount = 4).playLabel)
    }

    @Test
    fun `loudness is only described once it has been measured`() {
        assertNull(row().loudnessLabel)
        assertEquals("loud source", row(measuredRms = IntroLoudness.TARGET_RMS * 2).loudnessLabel)
    }

    @Test
    fun `the clip badge still reads as a range`() {
        val clipped = row().copy(startMs = 1_000, endMs = 5_000)

        assertEquals("0:01 – 0:05", clipped.clipLabel)
    }

    @Test
    fun `the row's audio url is signed rather than just carrying the id`() {
        // `/music` stays anonymous so lavaplayer can fetch it, and intro ids
        // are two public Discord snowflakes — the signature is the only thing
        // between them and every uploaded MP3 on the bot.
        val url = row().mediaUrl

        assertTrue(url.startsWith("/music?id=1_2_1&"), url)
        assertTrue(url.contains("${MediaToken.EXPIRY_PARAM}="), url)
        assertTrue(url.contains("${MediaToken.SIGNATURE_PARAM}="), url)
    }

    @Test
    fun `the minted url is one the endpoint will actually accept`() {
        val url = row().mediaUrl
        val expiry = Regex("${MediaToken.EXPIRY_PARAM}=(\\d+)").find(url)!!.groupValues[1].toLong()
        val signature = Regex("${MediaToken.SIGNATURE_PARAM}=([0-9a-f]+)").find(url)!!.groupValues[1]

        assertTrue(MediaToken.isValid("1_2_1", expiry, signature))
    }
}

package bot.toby.intro

import bot.toby.helpers.HttpHelper
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.entities.Message.Attachment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class IntroValidationServiceTest {

    private val httpHelper: HttpHelper = mockk()

    private fun service(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        IntroValidationService(httpHelper, dispatcher)

    private fun attachment(extension: String?, size: Int): Attachment = mockk {
        io.mockk.every { fileExtension } returns extension
        io.mockk.every { this@mockk.size } returns size
    }

    // --- parseVolume --------------------------------------------------------

    @Test
    fun `a trailing standalone number is the volume`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        assertEquals(85, svc.parseVolume("https://www.youtube.com/watch?v=dQw4w9WgXcQ 85"))
        assertEquals(0, svc.parseVolume("https://www.youtube.com/watch?v=dQw4w9WgXcQ 0"))
        assertEquals(100, svc.parseVolume("https://www.youtube.com/watch?v=dQw4w9WgXcQ 100"))
    }

    @Test
    fun `a leading standalone number is also accepted`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        assertEquals(70, svc.parseVolume("70 https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `a bare number works for the attachment reply`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        assertEquals(30, svc.parseVolume("30"))
    }

    @Test
    fun `digits inside the url are not mistaken for a volume`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        // Regression: the old \b(\d{1,3})\b scan matched these and silently
        // set the intro to 42% / 7%.
        assertNull(svc.parseVolume("https://youtu.be/dQw4w9WgXcQ?t=42"))
        assertNull(svc.parseVolume("https://www.youtube.com/watch?v=abc&list=PL9&index=7"))
        assertNull(svc.parseVolume("https://example.com/clips/12/intro.mp3"))
    }

    @Test
    fun `no number at all means no volume`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        assertNull(svc.parseVolume("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(svc.parseVolume(""))
        assertNull(svc.parseVolume("   "))
    }

    @Test
    fun `an out-of-range number is not treated as a volume`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        assertNull(svc.parseVolume("https://example.com/a.mp3 101"))
        assertNull(svc.parseVolume("https://example.com/a.mp3 9999"))
    }

    // --- isValidAttachment --------------------------------------------------

    @Test
    fun `only mp3 files within the size limit are valid attachments`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        assertTrue(svc.isValidAttachment(attachment("mp3", 1_000)))
        assertTrue(svc.isValidAttachment(attachment("mp3", IntroValidationService.MAX_FILE_SIZE)))
        assertFalse(svc.isValidAttachment(attachment("mp3", IntroValidationService.MAX_FILE_SIZE + 1)))
        assertFalse(svc.isValidAttachment(attachment("wav", 1_000)))
        assertFalse(svc.isValidAttachment(attachment(null, 1_000)))
    }

    // --- convertShortsUrls / isValidUrl ------------------------------------

    @Test
    fun `shorts urls are rewritten to the watch form`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        assertEquals(
            "https://www.youtube.com/watch?v=abc123",
            svc.convertShortsUrls("https://www.youtube.com/shorts/abc123"),
        )
        assertEquals(
            "https://www.youtube.com/watch?v=abc123",
            svc.convertShortsUrls("https://www.youtube.com/watch?v=abc123"),
        )
    }

    @Test
    fun `isValidUrl accepts http urls and rejects junk`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        assertTrue(svc.isValidUrl("https://www.youtube.com/watch?v=abc"))
        assertFalse(svc.isValidUrl("not a url"))
    }

    // --- checkForOverlyLongIntroDuration -----------------------------------

    @Test
    fun `a source over the cap is over limit`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 21.seconds
        assertTrue(svc.checkForOverlyLongIntroDuration("https://example.com/a"))
    }

    @Test
    fun `a source within the cap is not over limit`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 10.seconds
        assertFalse(svc.checkForOverlyLongIntroDuration("https://example.com/a"))
    }

    @Test
    fun `an unknown duration is not treated as over limit`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns null
        assertFalse(svc.checkForOverlyLongIntroDuration("https://example.com/a"))
    }

    // --- validateIntroLength (legacy boolean gate) -------------------------

    @Test
    fun `validateIntroLength reports over-limit for a long source`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 60.seconds

        var result: Boolean? = null
        svc.validateIntroLength("https://example.com/a") { result = it }
        advanceUntilIdle()

        assertEquals(true, result)
    }

    @Test
    fun `validateIntroLength fails closed when the lookup throws`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } throws IllegalStateException("boom")

        var result: Boolean? = null
        svc.validateIntroLength("https://example.com/a") { result = it }
        advanceUntilIdle()

        assertEquals(true, result)
    }

    // --- sourceDurationMs ---------------------------------------------------

    @Test
    fun `sourceDurationMs converts the looked-up duration to milliseconds`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 12.seconds
        assertEquals(12_000, svc.sourceDurationMs("https://example.com/a"))
    }

    @Test
    fun `sourceDurationMs is null when the lookup fails`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } throws IllegalStateException("boom")
        assertNull(svc.sourceDurationMs("https://example.com/a"))
    }

    // --- validateClipAgainstSource -----------------------------------------

    @Test
    fun `an unclipped long source is rejected`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 60.seconds

        var error: String? = null
        var called = false
        svc.validateClipAgainstSource("https://example.com/a", null, null) { error = it; called = true }
        advanceUntilIdle()

        assertTrue(called)
        assertNotNull(error)
        assertTrue(error!!.contains("set a start/end clip"), error!!)
    }

    @Test
    fun `a clip inside a long source is accepted`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 60.seconds

        var error: String? = "unset"
        svc.validateClipAgainstSource("https://example.com/a", 10_000, 20_000) { error = it }
        advanceUntilIdle()

        assertNull(error)
    }

    @Test
    fun `a clip past the end of the source is rejected`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } returns 20.seconds

        var error: String? = null
        svc.validateClipAgainstSource("https://example.com/a", 15_000, 25_000) { error = it }
        advanceUntilIdle()

        assertEquals("End time exceeds the source duration.", error)
    }

    @Test
    fun `an upload has no source to look up so only the span is checked`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))

        var error: String? = "unset"
        svc.validateClipAgainstSource(null, 1_000, 5_000) { error = it }
        advanceUntilIdle()
        assertNull(error)

        var tooLong: String? = null
        svc.validateClipAgainstSource(null, 0, 60_000) { tooLong = it }
        advanceUntilIdle()
        assertNotNull(tooLong)
    }

    @Test
    fun `a lookup failure lets the intro through rather than blocking everyone`() = runTest {
        val svc = service(StandardTestDispatcher(testScheduler))
        coEvery { httpHelper.getYouTubeVideoDuration(any()) } throws IllegalStateException("api down")

        var error: String? = "unset"
        svc.validateClipAgainstSource("https://example.com/a", null, null) { error = it }
        advanceUntilIdle()

        assertNull(error)
    }
}

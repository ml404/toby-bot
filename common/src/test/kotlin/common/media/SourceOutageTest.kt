package common.media

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceOutageTest {

    // --- reading a failure message ------------------------------------------

    @Test
    fun `the bot check is recognised as a refusal`() {
        assertTrue(SourceOutage.looksLikeBlock("Sign in to confirm you're not a bot"))
    }

    @Test
    fun `a curly apostrophe in the bot check still matches`() {
        // These messages come through with typographic punctuation more often
        // than not, and a marker list that only knew about `'` would miss them.
        assertTrue(SourceOutage.looksLikeBlock("Sign in to confirm you’re not a bot"))
    }

    @Test
    fun `the age gate is not a refusal, despite reading almost the same`() {
        // "Sign in to confirm your age" is the track being gated and genuinely
        // unplayable. Conflating the two would pause intro health for a real
        // broken link, which is the failure mode this whole thing exists to
        // avoid in the other direction.
        assertFalse(SourceOutage.looksLikeBlock("Sign in to confirm your age"))
        assertFalse(SourceOutage.looksLikeBlock("Please sign in"))
    }

    @Test
    fun `rate limiting is recognised in its usual forms`() {
        assertTrue(SourceOutage.looksLikeBlock("HTTP error 429: Too Many Requests"))
        assertTrue(SourceOutage.looksLikeBlock("Received status code 429"))
        assertTrue(SourceOutage.looksLikeBlock("You are being rate limited"))
        assertTrue(SourceOutage.looksLikeBlock("This IP has been temporarily blocked"))
    }

    @Test
    fun `an ordinary dead link is not a refusal`() {
        assertFalse(SourceOutage.looksLikeBlock("This video is not available in your country"))
        assertFalse(SourceOutage.looksLikeBlock("This video is unavailable"))
        assertFalse(SourceOutage.looksLikeBlock("The uploader has not made this video available"))
        assertFalse(SourceOutage.looksLikeBlock(null))
        assertFalse(SourceOutage.looksLikeBlock(""))
    }

    // --- correlating failures ------------------------------------------------

    private class Clock(var now: Long = 0) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun tracker(clock: Clock) = SourceOutageTracker(clock = clock)

    @Test
    fun `one dead link is not an outage`() {
        val t = tracker(Clock())

        t.recordFailure("https://youtu.be/a")

        assertFalse(t.isOutage())
    }

    @Test
    fun `the same link failing repeatedly is never an outage on its own`() {
        // This is why failures are keyed by source: a single dead intro
        // retried on every join would otherwise declare an outage and
        // suppress its own reporting forever.
        val t = tracker(Clock())

        repeat(10) { t.recordFailure("https://youtu.be/dead") }

        assertFalse(t.isOutage())
    }

    @Test
    fun `several different sources failing together is an outage`() {
        val t = tracker(Clock())

        t.recordFailure("https://youtu.be/a")
        t.recordFailure("https://youtu.be/b")
        assertFalse(t.isOutage())
        t.recordFailure("https://youtu.be/c")

        assertTrue(t.isOutage())
    }

    @Test
    fun `failures spread out over time do not correlate`() {
        val clock = Clock()
        val t = tracker(clock)

        t.recordFailure("https://youtu.be/a")
        clock.now += SourceOutage.WINDOW_MS + 1
        t.recordFailure("https://youtu.be/b")
        clock.now += SourceOutage.WINDOW_MS + 1
        t.recordFailure("https://youtu.be/c")

        assertFalse(t.isOutage())
    }

    @Test
    fun `a message that names a block declares an outage immediately`() {
        // No point waiting for corroboration when the host has said so.
        val t = tracker(Clock())

        t.recordFailure("https://youtu.be/a", definite = true)

        assertTrue(t.isOutage())
    }

    @Test
    fun `the onset is reported once, not on every subsequent failure`() {
        val t = tracker(Clock())

        assertFalse(t.recordFailure("https://youtu.be/a"))
        assertFalse(t.recordFailure("https://youtu.be/b"))
        assertTrue(t.recordFailure("https://youtu.be/c"), "the third distinct failure declares it")
        assertFalse(t.recordFailure("https://youtu.be/d"), "already declared — don't log it again")
    }

    @Test
    fun `a successful load ends the outage at once`() {
        // Proof beats elapsed time: waiting the hold out would keep blaming
        // the host long after playback started working.
        val t = tracker(Clock())
        t.recordFailure("https://youtu.be/a", definite = true)
        assertTrue(t.isOutage())

        t.recordSuccess()

        assertFalse(t.isOutage())
    }

    @Test
    fun `a success also clears the failures counted so far`() {
        val t = tracker(Clock())
        t.recordFailure("https://youtu.be/a")
        t.recordFailure("https://youtu.be/b")

        t.recordSuccess()
        t.recordFailure("https://youtu.be/c")

        assertFalse(t.isOutage(), "the pre-success failures should not still be counting")
    }

    @Test
    fun `an outage lapses on its own if nothing more is heard`() {
        val clock = Clock()
        val t = tracker(clock)
        t.recordFailure("https://youtu.be/a", definite = true)

        clock.now += SourceOutage.OUTAGE_HOLD_MS + 1

        assertFalse(t.isOutage())
    }

    @Test
    fun `continued failures keep the outage alive`() {
        val clock = Clock()
        val t = tracker(clock)
        t.recordFailure("https://youtu.be/a", definite = true)

        clock.now += SourceOutage.OUTAGE_HOLD_MS - 1
        t.recordFailure("https://youtu.be/b", definite = true)
        clock.now += SourceOutage.OUTAGE_HOLD_MS - 1

        assertTrue(t.isOutage())
    }

    @Test
    fun `it is safe to record from several threads at once`() {
        // Failures arrive on lavaplayer's load threads while the answer is
        // read from Discord's dispatch threads.
        val t = SourceOutageTracker(threshold = 50, clock = Clock())
        val threads = (0 until 8).map { thread ->
            Thread { repeat(50) { i -> t.recordFailure("https://youtu.be/$thread-$i") } }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(t.isOutage())
    }
}

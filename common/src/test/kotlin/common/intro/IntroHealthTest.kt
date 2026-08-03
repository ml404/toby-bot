package common.intro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntroHealthTest {

    @Test
    fun `a single failure is not enough to call an intro broken`() {
        // Lavaplayer already retries transient severities before reporting a
        // failure at all, but one bad night for YouTube shouldn't tell someone
        // their intro is dead.
        assertFalse(IntroHealth.isUnhealthy(0))
        assertFalse(IntroHealth.isUnhealthy(1))
    }

    @Test
    fun `repeated failures cross the threshold`() {
        assertTrue(IntroHealth.isUnhealthy(IntroHealth.UNHEALTHY_AFTER_FAILURES))
        assertTrue(IntroHealth.isUnhealthy(IntroHealth.UNHEALTHY_AFTER_FAILURES + 5))
    }

    @Test
    fun `a blank reason becomes something a user can read`() {
        // "" in the embed reads as "we have no idea", which is worse than a
        // generic sentence and untrue — we know the load failed.
        assertEquals("Source could not be loaded", IntroHealth.normaliseReason(null))
        assertEquals("Source could not be loaded", IntroHealth.normaliseReason("   "))
    }

    @Test
    fun `a real reason is kept verbatim`() {
        val reason = "This video is not available in your country"
        assertEquals(reason, IntroHealth.normaliseReason("  $reason  "))
    }

    @Test
    fun `an overlong reason is truncated to fit the column`() {
        val truncated = IntroHealth.normaliseReason("x".repeat(IntroHealth.MAX_REASON_LENGTH + 200))

        assertEquals(IntroHealth.MAX_REASON_LENGTH, truncated.length)
        assertTrue(truncated.endsWith("…"), truncated.takeLast(5))
    }
}

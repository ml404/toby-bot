package common.intro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntroGuildPolicyTest {

    // --- the server-wide switch ---------------------------------------------

    @Test
    fun `a guild that has never set the key keeps playing intros`() {
        // Opt-out is the whole point: shipping this must not silently disable
        // the feature for every existing server.
        assertTrue(IntroGuildPolicy.introsEnabled(null))
        assertTrue(IntroGuildPolicy.introsEnabled(""))
    }

    @Test
    fun `only an explicit false turns intros off`() {
        assertFalse(IntroGuildPolicy.introsEnabled("false"))
        assertFalse(IntroGuildPolicy.introsEnabled("  FALSE  "))
        assertTrue(IntroGuildPolicy.introsEnabled("true"))
        // Junk is not "off" — a typo shouldn't quietly disable the feature.
        assertTrue(IntroGuildPolicy.introsEnabled("maybe"))
    }

    // --- per-channel exemptions ---------------------------------------------

    @Test
    fun `channel ids parse from csv, spaces and mentions alike`() {
        assertEquals(setOf(1L, 2L, 3L), IntroGuildPolicy.excludedChannelIds("1,2,3"))
        assertEquals(setOf(1L, 2L), IntroGuildPolicy.excludedChannelIds("1, 2"))
        assertEquals(setOf(42L), IntroGuildPolicy.excludedChannelIds("<#42>"))
    }

    @Test
    fun `one unparseable id does not discard the rest`() {
        // The value is hand-editable from /setconfig. Failing the whole set on
        // one bad entry would re-enable intros in channels an admin had
        // deliberately silenced.
        assertEquals(setOf(1L, 3L), IntroGuildPolicy.excludedChannelIds("1,oops,3"))
    }

    @Test
    fun `an empty or absent list excludes nothing`() {
        assertTrue(IntroGuildPolicy.excludedChannelIds(null).isEmpty())
        assertTrue(IntroGuildPolicy.excludedChannelIds("").isEmpty())
        assertTrue(IntroGuildPolicy.excludedChannelIds(" , , ").isEmpty())
    }

    @Test
    fun `formatting round-trips back through the parser`() {
        val ids = setOf(10L, 20L, 30L)
        assertEquals(ids, IntroGuildPolicy.excludedChannelIds(IntroGuildPolicy.formatExcludedChannelIds(ids)))
    }

    // --- the combined decision ----------------------------------------------

    @Test
    fun `intros play in a channel that is not exempt`() {
        assertTrue(IntroGuildPolicy.playsIn(enabledRaw = null, excludedRaw = "99", channelId = 1L))
    }

    @Test
    fun `intros do not play in an exempt channel`() {
        assertFalse(IntroGuildPolicy.playsIn(enabledRaw = null, excludedRaw = "1,99", channelId = 99L))
    }

    @Test
    fun `the server-wide switch beats the exemption list`() {
        assertFalse(IntroGuildPolicy.playsIn(enabledRaw = "false", excludedRaw = "", channelId = 1L))
    }

    @Test
    fun `an unresolved channel is not treated as exempt`() {
        // Not knowing which channel someone joined is not a reason to suppress
        // their intro; only the server-wide switch does that.
        assertTrue(IntroGuildPolicy.playsIn(enabledRaw = null, excludedRaw = "1,2", channelId = null))
        assertFalse(IntroGuildPolicy.playsIn(enabledRaw = "false", excludedRaw = "1,2", channelId = null))
    }

    // --- normalisation switch -----------------------------------------------

    @Test
    fun `loudness matching is on unless explicitly disabled`() {
        assertTrue(IntroGuildPolicy.normaliseVolume(null))
        assertTrue(IntroGuildPolicy.normaliseVolume("true"))
        assertFalse(IntroGuildPolicy.normaliseVolume("false"))
    }
}

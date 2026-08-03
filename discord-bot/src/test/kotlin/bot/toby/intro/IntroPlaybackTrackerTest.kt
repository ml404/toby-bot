package bot.toby.intro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class IntroPlaybackTrackerTest {

    private lateinit var tracker: IntroPlaybackTracker

    private val guildId = 1L
    private val discordId = 2L
    private val start: Instant = Instant.parse("2026-01-01T12:00:00Z")

    @BeforeEach
    fun setUp() {
        tracker = IntroPlaybackTracker()
    }

    @Test
    fun `a user who has never joined is not on cooldown`() {
        assertFalse(tracker.onCooldown(guildId, discordId, start))
        assertNull(tracker.lastPlayedIntroId(guildId, discordId))
    }

    @Test
    fun `a rejoin inside the window is on cooldown`() {
        tracker.record(guildId, discordId, "1_2_1", start)

        assertTrue(tracker.onCooldown(guildId, discordId, start))
        assertTrue(tracker.onCooldown(guildId, discordId, start.plusSeconds(1)))
        assertTrue(
            tracker.onCooldown(guildId, discordId, start.plusSeconds(IntroPlaybackTracker.COOLDOWN.seconds - 1))
        )
    }

    @Test
    fun `the cooldown lapses once the window passes`() {
        tracker.record(guildId, discordId, "1_2_1", start)

        assertFalse(tracker.onCooldown(guildId, discordId, start.plusSeconds(IntroPlaybackTracker.COOLDOWN.seconds)))
        assertFalse(tracker.onCooldown(guildId, discordId, start.plusSeconds(3600)))
    }

    @Test
    fun `the last played intro is remembered so the next pick can avoid it`() {
        tracker.record(guildId, discordId, "1_2_2", start)
        assertEquals("1_2_2", tracker.lastPlayedIntroId(guildId, discordId))
    }

    @Test
    fun `a later play replaces the remembered one`() {
        tracker.record(guildId, discordId, "1_2_1", start)
        tracker.record(guildId, discordId, "1_2_3", start.plusSeconds(300))

        assertEquals("1_2_3", tracker.lastPlayedIntroId(guildId, discordId))
        assertFalse(tracker.onCooldown(guildId, discordId, start.plusSeconds(400)))
        assertTrue(tracker.onCooldown(guildId, discordId, start.plusSeconds(301)))
    }

    @Test
    fun `a failed play still starts the cooldown but remembers no intro`() {
        // playUserIntro returning null shouldn't leave the user open to an
        // immediate retrigger loop.
        tracker.record(guildId, discordId, null, start)

        assertTrue(tracker.onCooldown(guildId, discordId, start))
        assertNull(tracker.lastPlayedIntroId(guildId, discordId))
    }

    @Test
    fun `tracking is scoped per guild and per user`() {
        tracker.record(guildId, discordId, "1_2_1", start)

        assertFalse(tracker.onCooldown(99L, discordId, start))
        assertFalse(tracker.onCooldown(guildId, 99L, start))
        assertNull(tracker.lastPlayedIntroId(99L, discordId))
    }
}

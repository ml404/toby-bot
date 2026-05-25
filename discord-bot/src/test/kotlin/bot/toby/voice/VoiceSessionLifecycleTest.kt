package bot.toby.voice

import database.dto.activity.VoiceSessionDto
import database.service.activity.VoiceSessionService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.mockk.junit5.MockKExtension
import java.time.Instant

@ExtendWith(MockKExtension::class)
class VoiceSessionLifecycleTest {

    private val voiceSessionService: VoiceSessionService = mockk(relaxed = true)
    private val voiceCompanyTracker: VoiceCompanyTracker = mockk(relaxed = true)
    private val voiceCreditAwardService: VoiceCreditAwardService = mockk(relaxed = true)

    private val lifecycle = VoiceSessionLifecycle(
        voiceSessionService, voiceCompanyTracker, voiceCreditAwardService,
    )

    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val userId = 100L
    private val guildId = 42L

    private fun channel(id: Long = 7L): AudioChannel = mockk(relaxed = true) {
        every { idLong } returns id
    }

    @Test
    fun `openSession with no stale session writes a new session and starts tracking - no premature award`() {
        every { voiceSessionService.findOpenSession(userId, guildId) } returns null
        val captured = slot<VoiceSessionDto>()
        every { voiceSessionService.openSession(capture(captured)) } answers { firstArg() }
        val ch = channel(id = 7L)

        lifecycle.openSession(userId, guildId, ch, now)

        verify(exactly = 0) { voiceCreditAwardService.closeSessionAndAward(any(), any(), any()) }
        verifyOrder {
            voiceCompanyTracker.reconcileChannel(ch, now)
            voiceSessionService.openSession(any())
            voiceCompanyTracker.startTracking(userId, guildId, ch, now)
        }
        assertEquals(userId, captured.captured.discordId)
        assertEquals(guildId, captured.captured.guildId)
        assertEquals(7L, captured.captured.channelId)
        assertEquals(now, captured.captured.joinedAt)
    }

    @Test
    fun `openSession closes a stale open session and awards its credits before opening the new one`() {
        // Recovered crash: previous session never closed. Lifecycle must
        // finalize it (with the company seconds tracked against the OLD
        // session) before starting fresh, otherwise credits leak.
        val stale = VoiceSessionDto(
            discordId = userId, guildId = guildId, channelId = 5L, joinedAt = now.minusSeconds(3600),
        )
        every { voiceSessionService.findOpenSession(userId, guildId) } returns stale
        every { voiceCompanyTracker.stopTracking(userId, guildId, now) } returns 1234L
        every { voiceCreditAwardService.closeSessionAndAward(stale, now, 1234L) } just Runs
        val ch = channel(id = 7L)

        lifecycle.openSession(userId, guildId, ch, now)

        verifyOrder {
            voiceCompanyTracker.stopTracking(userId, guildId, now)
            voiceCreditAwardService.closeSessionAndAward(stale, now, 1234L)
            voiceCompanyTracker.reconcileChannel(ch, now)
            voiceSessionService.openSession(any())
            voiceCompanyTracker.startTracking(userId, guildId, ch, now)
        }
    }

    @Test
    fun `openSession swallows persistence failure - never re-throws to the caller`() {
        // The whole body is runCatching-wrapped because a transient DB
        // hiccup shouldn't crash JDA's voice-event dispatcher.
        every { voiceSessionService.findOpenSession(userId, guildId) } throws RuntimeException("db down")

        // Must not throw.
        lifecycle.openSession(userId, guildId, channel(), now)
    }

    @Test
    fun `closeSession with an open session awards its credits and refreshes other members' company`() {
        val open = VoiceSessionDto(
            id = 1L, discordId = userId, guildId = guildId, channelId = 7L, joinedAt = now.minusSeconds(60),
        )
        every { voiceSessionService.findOpenSession(userId, guildId) } returns open
        every { voiceCompanyTracker.stopTracking(userId, guildId, now) } returns 60L
        val leftChannel = channel(id = 7L)

        lifecycle.closeSession(userId, guildId, leftChannel, now)

        verifyOrder {
            voiceCompanyTracker.stopTracking(userId, guildId, now)
            voiceCreditAwardService.closeSessionAndAward(open, now, 60L)
            voiceCompanyTracker.reconcileChannel(leftChannel, now)
        }
    }

    @Test
    fun `closeSession with no open session still reconciles leftChannel so other members lose company correctly`() {
        // Source comment: "Other members still in the channel may have just
        // lost company — refresh their accumulators regardless of whether
        // the leaver had an open session."
        every { voiceSessionService.findOpenSession(userId, guildId) } returns null
        val leftChannel = channel(id = 7L)

        lifecycle.closeSession(userId, guildId, leftChannel, now)

        verify(exactly = 0) { voiceCompanyTracker.stopTracking(any(), any(), any()) }
        verify(exactly = 0) { voiceCreditAwardService.closeSessionAndAward(any(), any(), any()) }
        verify(exactly = 1) { voiceCompanyTracker.reconcileChannel(leftChannel, now) }
    }

    @Test
    fun `closeSession with null leftChannel skips the channel reconcile but still awards the session`() {
        // leftChannel is null when the bot itself left a guild — there's no
        // remaining channel to refresh, but the user's own session still
        // needs to be closed cleanly.
        val open = VoiceSessionDto(
            id = 1L, discordId = userId, guildId = guildId, channelId = 7L, joinedAt = now.minusSeconds(60),
        )
        every { voiceSessionService.findOpenSession(userId, guildId) } returns open
        every { voiceCompanyTracker.stopTracking(userId, guildId, now) } returns 60L

        lifecycle.closeSession(userId, guildId, leftChannel = null, now)

        verify(exactly = 1) { voiceCreditAwardService.closeSessionAndAward(open, now, 60L) }
        verify(exactly = 0) { voiceCompanyTracker.reconcileChannel(any(), any()) }
    }

    @Test
    fun `closeSession swallows persistence failure - never re-throws to the caller`() {
        every { voiceSessionService.findOpenSession(userId, guildId) } throws RuntimeException("db down")

        // Must not throw.
        lifecycle.closeSession(userId, guildId, channel(), now)
    }
}

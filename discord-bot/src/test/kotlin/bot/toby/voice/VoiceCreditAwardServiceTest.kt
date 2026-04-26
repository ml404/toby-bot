package bot.toby.voice

import database.dto.VoiceSessionDto
import database.service.SocialCreditAwardService
import database.service.VoiceSessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceCreditAwardServiceTest {

    private lateinit var voiceSessionService: VoiceSessionService
    private lateinit var awardService: SocialCreditAwardService
    private lateinit var service: VoiceCreditAwardService

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setup() {
        voiceSessionService = mockk(relaxed = true)
        awardService = mockk(relaxed = true)
        service = VoiceCreditAwardService(voiceSessionService, awardService)
    }

    private fun session(joinedAt: Instant): VoiceSessionDto {
        return VoiceSessionDto(
            id = 1L,
            discordId = discordId,
            guildId = guildId,
            channelId = 2L,
            joinedAt = joinedAt
        )
    }

    @Test
    fun `closeSessionAndAward converts counted seconds to credits using configured ratio`() {
        val t0 = Instant.parse("2026-04-10T10:00:00Z")
        every {
            awardService.award(
                discordId = discordId,
                guildId = guildId,
                amount = 5L,
                reason = "voice-session",
                countsAgainstDailyCap = any(),
                at = any(),
                dailyCap = VoiceCreditConfig.DAILY_CREDIT_CAP
            )
        } returns 5L

        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        // 600s company → 600/120 = 5 credits
        service.closeSessionAndAward(session(t0), t0.plusSeconds(600), hadCompanyDurationSeconds = 600L)

        assertEquals(600L, closed.captured.countedSeconds)
        assertEquals(5L, closed.captured.creditsAwarded)
        verify(exactly = 1) {
            awardService.award(
                discordId = discordId,
                guildId = guildId,
                amount = 5L,
                reason = "voice-session",
                countsAgainstDailyCap = any(),
                at = any(),
                dailyCap = VoiceCreditConfig.DAILY_CREDIT_CAP
            )
        }
    }

    @Test
    fun `closeSessionAndAward awards zero when no company time`() {
        val t0 = Instant.parse("2026-04-10T10:00:00Z")
        every {
            awardService.award(
                any(), any(), amount = 0L, any(), any(), any(), any()
            )
        } returns 0L

        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        service.closeSessionAndAward(session(t0), t0.plusSeconds(1800), hadCompanyDurationSeconds = 0L)

        assertEquals(0L, closed.captured.countedSeconds)
        assertEquals(0L, closed.captured.creditsAwarded)
    }

    @Test
    fun `closeSessionAndAward writes whatever the award service grants`() {
        val t0 = Instant.parse("2026-04-10T10:00:00Z")
        // Central service may clamp to the daily cap — voice service trusts the returned value.
        every {
            awardService.award(
                discordId = discordId,
                guildId = guildId,
                amount = 90L,
                reason = "voice-session",
                countsAgainstDailyCap = any(),
                at = any(),
                dailyCap = VoiceCreditConfig.DAILY_CREDIT_CAP
            )
        } returns 5L

        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        // 10800s / 120s = 90 credits requested; awardService only grants 5 (cap).
        service.closeSessionAndAward(session(t0), t0.plusSeconds(10800), hadCompanyDurationSeconds = 10800L)

        assertEquals(5L, closed.captured.creditsAwarded)
    }

    @Test
    fun `closeSessionAndAward clamps counted seconds to raw duration`() {
        val t0 = Instant.parse("2026-04-10T10:00:00Z")
        every { awardService.award(any(), any(), any(), any(), any(), any(), any()) } returns 0L
        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        // Raw = 300s. Company accumulator overstated at 9999s — must clamp to 300.
        service.closeSessionAndAward(session(t0), t0.plusSeconds(300), hadCompanyDurationSeconds = 9999L)

        assertEquals(300L, closed.captured.countedSeconds)
    }

    @Test
    fun `closeRecoveredSession caps duration at max recovered seconds`() {
        val t0 = Instant.parse("2026-04-01T00:00:00Z")
        every { awardService.award(any(), any(), any(), any(), any(), any(), any()) } returns 0L
        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        // Session was open for 24h. Should be capped at MAX_RECOVERED_SESSION_SECONDS (8h = 28800s).
        service.closeRecoveredSession(session(t0), t0.plusSeconds(24 * 3600L))

        assertNotNull(closed.captured.countedSeconds)
        assertEquals(VoiceCreditConfig.MAX_RECOVERED_SESSION_SECONDS, closed.captured.countedSeconds)
    }

    @Test
    fun `closeSessionAtShutdown awards full raw seconds without the recovery cap`() {
        val t0 = Instant.parse("2026-04-10T10:00:00Z")
        every { awardService.award(any(), any(), any(), any(), any(), any(), any()) } returns 0L
        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        // 3600s session, shutdown right at the end. Whole duration must count —
        // this is the reason shutdown is different from recovery: we KNOW the
        // user was present up to this instant.
        service.closeSessionAtShutdown(session(t0), t0.plusSeconds(3600L))

        assertEquals(3600L, closed.captured.countedSeconds)
        assertEquals(t0.plusSeconds(3600L), closed.captured.leftAt,
            "leftAt must reflect the actual shutdown moment, not any later wake-time")
    }

    @Test
    fun `closeSessionAtShutdown treats a negative duration as zero`() {
        // Clock skew / backwards-moving Instant.now — shouldn't produce negative credits.
        val t0 = Instant.parse("2026-04-10T10:00:00Z")
        every { awardService.award(any(), any(), any(), any(), any(), any(), any()) } returns 0L
        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        service.closeSessionAtShutdown(session(t0), t0.minusSeconds(10L))

        assertEquals(0L, closed.captured.countedSeconds)
    }

    @Test
    fun `closeSessionAndAward records zero when award service returns zero (unknown user)`() {
        val t0 = Instant.parse("2026-04-10T10:00:00Z")
        // Simulate "user doesn't exist" — central service returns 0 without consuming cap.
        every {
            awardService.award(
                discordId = discordId,
                guildId = guildId,
                amount = 5L,
                reason = "voice-session",
                countsAgainstDailyCap = any(),
                at = any(),
                dailyCap = VoiceCreditConfig.DAILY_CREDIT_CAP
            )
        } returns 0L
        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        service.closeSessionAndAward(session(t0), t0.plusSeconds(600), hadCompanyDurationSeconds = 600L)

        assertEquals(0L, closed.captured.creditsAwarded)
    }
}

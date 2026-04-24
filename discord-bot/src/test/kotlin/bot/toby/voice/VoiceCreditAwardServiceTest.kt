package bot.toby.voice

import database.dto.UserDto
import database.dto.VoiceCreditDailyDto
import database.dto.VoiceSessionDto
import database.service.UserService
import database.service.VoiceCreditDailyService
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
import java.time.LocalDate
import java.time.ZoneOffset

class VoiceCreditAwardServiceTest {

    private lateinit var voiceSessionService: VoiceSessionService
    private lateinit var voiceCreditDailyService: VoiceCreditDailyService
    private lateinit var userService: UserService
    private lateinit var service: VoiceCreditAwardService

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setup() {
        voiceSessionService = mockk(relaxed = true)
        voiceCreditDailyService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        service = VoiceCreditAwardService(voiceSessionService, voiceCreditDailyService, userService)
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
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 10L }
        every { userService.getUserById(discordId, guildId) } returns user
        every { voiceCreditDailyService.get(discordId, guildId, any()) } returns null

        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        // 600s company → 600/120 = 5 credits
        service.closeSessionAndAward(session(t0), t0.plusSeconds(600), hadCompanyDurationSeconds = 600L)

        assertEquals(600L, closed.captured.countedSeconds)
        assertEquals(5L, closed.captured.creditsAwarded)
        assertEquals(15L, user.socialCredit)
        verify(exactly = 1) { userService.updateUser(user) }
    }

    @Test
    fun `closeSessionAndAward awards zero when no company time`() {
        val t0 = Instant.parse("2026-04-10T10:00:00Z")
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 10L }
        every { userService.getUserById(discordId, guildId) } returns user

        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        service.closeSessionAndAward(session(t0), t0.plusSeconds(1800), hadCompanyDurationSeconds = 0L)

        assertEquals(0L, closed.captured.countedSeconds)
        assertEquals(0L, closed.captured.creditsAwarded)
        assertEquals(10L, user.socialCredit)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `closeSessionAndAward clamps award to daily cap`() {
        val t0 = Instant.parse("2026-04-10T10:00:00Z")
        val earnDate = LocalDate.ofInstant(t0.plusSeconds(7200), ZoneOffset.UTC)
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 0L }
        every { userService.getUserById(discordId, guildId) } returns user
        // Already at 55 credits today; cap is 60.
        every { voiceCreditDailyService.get(discordId, guildId, earnDate) } returns
            VoiceCreditDailyDto(discordId = discordId, guildId = guildId, earnDate = earnDate, credits = 55L)

        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }
        val dailyUpsert = slot<VoiceCreditDailyDto>()
        every { voiceCreditDailyService.upsert(capture(dailyUpsert)) } answers { dailyUpsert.captured }

        // 7200s / 120s = 60 credits requested. Daily cap leaves only 5 headroom.
        service.closeSessionAndAward(session(t0), t0.plusSeconds(7200), hadCompanyDurationSeconds = 7200L)

        assertEquals(5L, closed.captured.creditsAwarded)
        assertEquals(5L, user.socialCredit)
        assertEquals(60L, dailyUpsert.captured.credits)
    }

    @Test
    fun `closeSessionAndAward clamps counted seconds to raw duration`() {
        val t0 = Instant.parse("2026-04-10T10:00:00Z")
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 0L }
        every { userService.getUserById(discordId, guildId) } returns user
        every { voiceCreditDailyService.get(discordId, guildId, any()) } returns null

        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        // Raw = 300s. Company accumulator overstated at 9999s — must clamp to 300.
        service.closeSessionAndAward(session(t0), t0.plusSeconds(300), hadCompanyDurationSeconds = 9999L)

        assertEquals(300L, closed.captured.countedSeconds)
    }

    @Test
    fun `closeRecoveredSession caps duration at max recovered seconds`() {
        val t0 = Instant.parse("2026-04-01T00:00:00Z")
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 0L }
        every { userService.getUserById(discordId, guildId) } returns user
        every { voiceCreditDailyService.get(discordId, guildId, any()) } returns null

        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        // Session was open for 24h. Should be capped at MAX_RECOVERED_SESSION_SECONDS (8h = 28800s).
        service.closeRecoveredSession(session(t0), t0.plusSeconds(24 * 3600L))

        assertNotNull(closed.captured.countedSeconds)
        assertEquals(VoiceCreditConfig.MAX_RECOVERED_SESSION_SECONDS, closed.captured.countedSeconds)
    }

    @Test
    fun `closeSessionAndAward skips user update when dto is missing`() {
        val t0 = Instant.parse("2026-04-10T10:00:00Z")
        every { userService.getUserById(discordId, guildId) } returns null
        every { voiceCreditDailyService.get(discordId, guildId, any()) } returns null
        val closed = slot<VoiceSessionDto>()
        every { voiceSessionService.closeSession(capture(closed)) } answers { closed.captured }

        service.closeSessionAndAward(session(t0), t0.plusSeconds(600), hadCompanyDurationSeconds = 600L)

        assertEquals(5L, closed.captured.creditsAwarded)
        verify(exactly = 0) { userService.updateUser(any()) }
    }
}

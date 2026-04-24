package bot.toby.voice

import database.dto.VoiceSessionDto
import database.service.VoiceSessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceSessionRecoveryHookTest {

    private lateinit var voiceSessionService: VoiceSessionService
    private lateinit var voiceCreditAwardService: VoiceCreditAwardService
    private lateinit var hook: VoiceSessionRecoveryHook

    @BeforeEach
    fun setup() {
        voiceSessionService = mockk(relaxed = true)
        voiceCreditAwardService = mockk(relaxed = true)
        hook = VoiceSessionRecoveryHook(voiceSessionService, voiceCreditAwardService)
    }

    @Test
    fun `closeStaleOpenSessions invokes award service for each open session`() {
        val s1 = VoiceSessionDto(id = 1L, discordId = 1L, guildId = 10L, channelId = 100L, joinedAt = Instant.now())
        val s2 = VoiceSessionDto(id = 2L, discordId = 2L, guildId = 10L, channelId = 100L, joinedAt = Instant.now())
        every { voiceSessionService.findAllOpenSessions() } returns listOf(s1, s2)

        hook.closeStaleOpenSessions()

        verify(exactly = 1) { voiceCreditAwardService.closeRecoveredSession(s1, any()) }
        verify(exactly = 1) { voiceCreditAwardService.closeRecoveredSession(s2, any()) }
    }

    @Test
    fun `closeStaleOpenSessions is a no-op when no open sessions exist`() {
        every { voiceSessionService.findAllOpenSessions() } returns emptyList()

        hook.closeStaleOpenSessions()

        verify(exactly = 0) { voiceCreditAwardService.closeRecoveredSession(any(), any()) }
    }

    @Test
    fun `closeStaleOpenSessions continues after per-session failure`() {
        val s1 = VoiceSessionDto(id = 1L, discordId = 1L, guildId = 10L, channelId = 100L, joinedAt = Instant.now())
        val s2 = VoiceSessionDto(id = 2L, discordId = 2L, guildId = 10L, channelId = 100L, joinedAt = Instant.now())
        every { voiceSessionService.findAllOpenSessions() } returns listOf(s1, s2)
        every { voiceCreditAwardService.closeRecoveredSession(s1, any()) } throws RuntimeException("boom")

        hook.closeStaleOpenSessions()

        verify(exactly = 1) { voiceCreditAwardService.closeRecoveredSession(s2, any()) }
    }
}

package bot.toby.voice

import database.dto.VoiceSessionDto
import database.service.VoiceSessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceSessionShutdownHookTest {

    private lateinit var voiceSessionService: VoiceSessionService
    private lateinit var voiceCreditAwardService: VoiceCreditAwardService
    private lateinit var hook: VoiceSessionShutdownHook

    @BeforeEach
    fun setup() {
        voiceSessionService = mockk(relaxed = true)
        voiceCreditAwardService = mockk(relaxed = true)
        hook = VoiceSessionShutdownHook(voiceSessionService, voiceCreditAwardService)
    }

    @Test
    fun `closeOpenSessions calls closeSessionAtShutdown for each live session`() {
        val s1 = VoiceSessionDto(id = 1L, discordId = 1L, guildId = 10L, channelId = 100L, joinedAt = Instant.now())
        val s2 = VoiceSessionDto(id = 2L, discordId = 2L, guildId = 10L, channelId = 100L, joinedAt = Instant.now())
        every { voiceSessionService.findAllOpenSessions() } returns listOf(s1, s2)

        hook.closeOpenSessions()

        // Must use the shutdown-specific close method, not closeRecoveredSession.
        // closeRecoveredSession applies an 8h cap and is intended for the boot-
        // time recovery path (where the real leave moment is unknown). On
        // shutdown we KNOW the session was live up to this instant, so we award
        // the full duration via closeSessionAtShutdown.
        verify(exactly = 1) { voiceCreditAwardService.closeSessionAtShutdown(s1, any()) }
        verify(exactly = 1) { voiceCreditAwardService.closeSessionAtShutdown(s2, any()) }
        verify(exactly = 0) { voiceCreditAwardService.closeRecoveredSession(any(), any()) }
    }

    @Test
    fun `closeOpenSessions is a no-op when nobody is in voice`() {
        every { voiceSessionService.findAllOpenSessions() } returns emptyList()

        hook.closeOpenSessions()

        verify(exactly = 0) { voiceCreditAwardService.closeSessionAtShutdown(any(), any()) }
    }

    @Test
    fun `closeOpenSessions continues after per-session failure so deploy can finish`() {
        val s1 = VoiceSessionDto(id = 1L, discordId = 1L, guildId = 10L, channelId = 100L, joinedAt = Instant.now())
        val s2 = VoiceSessionDto(id = 2L, discordId = 2L, guildId = 10L, channelId = 100L, joinedAt = Instant.now())
        every { voiceSessionService.findAllOpenSessions() } returns listOf(s1, s2)
        every { voiceCreditAwardService.closeSessionAtShutdown(s1, any()) } throws RuntimeException("boom")

        // Must not propagate — blowing up in destroy() could leave subsequent
        // shutdown hooks unrun and corrupt a rolling deploy.
        hook.closeOpenSessions()

        verify(exactly = 1) { voiceCreditAwardService.closeSessionAtShutdown(s2, any()) }
    }

    @Test
    fun `destroy delegates to closeOpenSessions so Spring triggers the flush`() {
        val s1 = VoiceSessionDto(id = 1L, discordId = 1L, guildId = 10L, channelId = 100L, joinedAt = Instant.now())
        every { voiceSessionService.findAllOpenSessions() } returns listOf(s1)

        hook.destroy()

        verify(exactly = 1) { voiceCreditAwardService.closeSessionAtShutdown(s1, any()) }
    }

    @Test
    fun `closeOpenSessions survives the query itself failing`() {
        every { voiceSessionService.findAllOpenSessions() } throws RuntimeException("db down")

        // Don't want a failed DB read to crash shutdown — log and move on.
        hook.closeOpenSessions()

        verify(exactly = 0) { voiceCreditAwardService.closeSessionAtShutdown(any(), any()) }
    }
}

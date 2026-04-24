package bot.toby.activity

import database.dto.ActivitySessionDto
import database.service.ActivitySessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class ActivitySessionRecoveryHookTest {

    private lateinit var activitySessionService: ActivitySessionService
    private lateinit var activityTrackingService: ActivityTrackingService
    private lateinit var hook: ActivitySessionRecoveryHook

    @BeforeEach
    fun setup() {
        activitySessionService = mockk(relaxed = true)
        activityTrackingService = mockk(relaxed = true)
        hook = ActivitySessionRecoveryHook(activitySessionService, activityTrackingService)
    }

    @Test
    fun `closeStaleSessions invokes recovery for each open session`() {
        val s1 = ActivitySessionDto(
            id = 1L, discordId = 1L, guildId = 10L,
            activityName = "A", startedAt = Instant.now()
        )
        val s2 = ActivitySessionDto(
            id = 2L, discordId = 2L, guildId = 10L,
            activityName = "B", startedAt = Instant.now()
        )
        every { activitySessionService.findAllOpen() } returns listOf(s1, s2)

        hook.closeStaleSessions()

        verify(exactly = 1) { activityTrackingService.closeRecoveredSession(s1, any()) }
        verify(exactly = 1) { activityTrackingService.closeRecoveredSession(s2, any()) }
    }

    @Test
    fun `closeStaleSessions is a no-op when no open sessions`() {
        every { activitySessionService.findAllOpen() } returns emptyList()

        hook.closeStaleSessions()

        verify(exactly = 0) { activityTrackingService.closeRecoveredSession(any(), any()) }
    }

    @Test
    fun `closeStaleSessions continues after per-session failure`() {
        val s1 = ActivitySessionDto(
            id = 1L, discordId = 1L, guildId = 10L,
            activityName = "A", startedAt = Instant.now()
        )
        val s2 = ActivitySessionDto(
            id = 2L, discordId = 2L, guildId = 10L,
            activityName = "B", startedAt = Instant.now()
        )
        every { activitySessionService.findAllOpen() } returns listOf(s1, s2)
        every { activityTrackingService.closeRecoveredSession(s1, any()) } throws RuntimeException("boom")

        hook.closeStaleSessions()

        verify(exactly = 1) { activityTrackingService.closeRecoveredSession(s2, any()) }
    }
}

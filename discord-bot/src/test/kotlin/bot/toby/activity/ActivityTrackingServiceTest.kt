package bot.toby.activity

import database.dto.ActivitySessionDto
import database.dto.ConfigDto
import database.dto.UserDto
import database.service.ActivityMonthlyRollupService
import database.service.ActivitySessionService
import database.service.ConfigService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class ActivityTrackingServiceTest {

    private lateinit var activitySessionService: ActivitySessionService
    private lateinit var activityMonthlyRollupService: ActivityMonthlyRollupService
    private lateinit var userService: UserService
    private lateinit var configService: ConfigService
    private lateinit var service: ActivityTrackingService

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setup() {
        activitySessionService = mockk(relaxed = true)
        activityMonthlyRollupService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        service = ActivityTrackingService(
            activitySessionService,
            activityMonthlyRollupService,
            userService,
            configService
        )
    }

    @Test
    fun `isGuildTrackingEnabled true when config value is 'true'`() {
        every { configService.getConfigByName("ACTIVITY_TRACKING", guildId.toString()) } returns
            ConfigDto(name = "ACTIVITY_TRACKING", value = "true", guildId = guildId.toString())
        assertTrue(service.isGuildTrackingEnabled(guildId))
    }

    @Test
    fun `isGuildTrackingEnabled false when config value is absent`() {
        every { configService.getConfigByName("ACTIVITY_TRACKING", guildId.toString()) } returns null
        assertFalse(service.isGuildTrackingEnabled(guildId))
    }

    @Test
    fun `isGuildTrackingEnabled false when value is 'false'`() {
        every { configService.getConfigByName("ACTIVITY_TRACKING", guildId.toString()) } returns
            ConfigDto(name = "ACTIVITY_TRACKING", value = "false", guildId = guildId.toString())
        assertFalse(service.isGuildTrackingEnabled(guildId))
    }

    @Test
    fun `isTrackingAllowed respects user opt-out`() {
        every { configService.getConfigByName("ACTIVITY_TRACKING", guildId.toString()) } returns
            ConfigDto(name = "ACTIVITY_TRACKING", value = "true", guildId = guildId.toString())
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId = discordId, guildId = guildId).apply { activityTrackingOptOut = true }
        assertFalse(service.isTrackingAllowed(discordId, guildId))
    }

    @Test
    fun `isTrackingAllowed true when guild enabled and user not opted out`() {
        every { configService.getConfigByName("ACTIVITY_TRACKING", guildId.toString()) } returns
            ConfigDto(name = "ACTIVITY_TRACKING", value = "true", guildId = guildId.toString())
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId = discordId, guildId = guildId)
        assertTrue(service.isTrackingAllowed(discordId, guildId))
    }

    @Test
    fun `isTrackingAllowed true when user has no profile yet`() {
        every { configService.getConfigByName("ACTIVITY_TRACKING", guildId.toString()) } returns
            ConfigDto(name = "ACTIVITY_TRACKING", value = "true", guildId = guildId.toString())
        every { userService.getUserById(discordId, guildId) } returns null
        assertTrue(service.isTrackingAllowed(discordId, guildId))
    }

    @Test
    fun `openSession does nothing when guild tracking disabled`() {
        every { configService.getConfigByName("ACTIVITY_TRACKING", guildId.toString()) } returns null
        service.openSession(discordId, guildId, "Minecraft", Instant.now())
        verify(exactly = 0) { activitySessionService.openSession(any()) }
    }

    @Test
    fun `openSession closes any existing open session before opening a new one`() {
        every { configService.getConfigByName("ACTIVITY_TRACKING", guildId.toString()) } returns
            ConfigDto(name = "ACTIVITY_TRACKING", value = "true", guildId = guildId.toString())
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId = discordId, guildId = guildId)

        val stale = ActivitySessionDto(
            id = 7L,
            discordId = discordId,
            guildId = guildId,
            activityName = "Old",
            startedAt = Instant.parse("2026-04-10T10:00:00Z")
        )
        every { activitySessionService.findOpen(discordId, guildId) } returns stale

        val openedAt = Instant.parse("2026-04-10T12:00:00Z")
        service.openSession(discordId, guildId, "New", openedAt)

        verify(exactly = 1) { activitySessionService.closeSession(stale) }
        verify(exactly = 1) { activitySessionService.openSession(any()) }
    }

    @Test
    fun `closeSession writes rollup when session is at least 60s`() {
        val start = Instant.parse("2026-04-10T10:00:00Z")
        val end = start.plusSeconds(600)
        val session = ActivitySessionDto(
            id = 1L,
            discordId = discordId,
            guildId = guildId,
            activityName = "Minecraft",
            startedAt = start
        )
        val closed = slot<ActivitySessionDto>()
        every { activitySessionService.closeSession(capture(closed)) } answers { closed.captured }

        service.closeSession(session, end)

        verify(exactly = 1) {
            activityMonthlyRollupService.addSeconds(
                discordId = discordId,
                guildId = guildId,
                monthStart = any(),
                activityName = "Minecraft",
                delta = 600L
            )
        }
        assertEquals(end, closed.captured.endedAt)
    }

    @Test
    fun `closeSession discards sessions shorter than MIN_SESSION_SECONDS`() {
        val start = Instant.parse("2026-04-10T10:00:00Z")
        val session = ActivitySessionDto(
            id = 1L,
            discordId = discordId,
            guildId = guildId,
            activityName = "Minecraft",
            startedAt = start
        )

        service.closeSession(session, start.plusSeconds(20))

        verify(exactly = 0) { activityMonthlyRollupService.addSeconds(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `closeRecoveredSession caps duration at max`() {
        val start = Instant.parse("2026-04-10T00:00:00Z")
        val session = ActivitySessionDto(
            id = 1L,
            discordId = discordId,
            guildId = guildId,
            activityName = "Minecraft",
            startedAt = start
        )
        val closed = slot<ActivitySessionDto>()
        every { activitySessionService.closeSession(capture(closed)) } answers { closed.captured }

        service.closeRecoveredSession(session, start.plusSeconds(24 * 3600L))

        val sec = java.time.Duration.between(closed.captured.startedAt, closed.captured.endedAt).seconds
        assertEquals(ActivityTrackingService.MAX_RECOVERED_SESSION_SECONDS, sec)
    }
}

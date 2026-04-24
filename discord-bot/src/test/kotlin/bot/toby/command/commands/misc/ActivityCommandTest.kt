package bot.toby.command.commands.misc

import bot.toby.activity.ActivityTrackingService
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import database.service.ActivityMonthlyRollupService
import database.service.UserService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class ActivityCommandTest : CommandTest {
    private lateinit var rollupService: ActivityMonthlyRollupService
    private lateinit var userService: UserService
    private lateinit var activityTrackingService: ActivityTrackingService
    private lateinit var command: ActivityCommand

    private val discordId = 1L
    private val guildId = 1L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        rollupService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        activityTrackingService = mockk(relaxed = true)
        command = ActivityCommand(rollupService, userService, activityTrackingService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun `tracking-on flips opt-out off`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { activityTrackingOptOut = true }
        every { event.subcommandName } returns "tracking-on"

        command.handle(DefaultCommandContext(event), user, 5)

        assertFalse(user.activityTrackingOptOut)
        verify(exactly = 1) { userService.updateUser(user) }
    }

    @Test
    fun `tracking-off flips opt-out on`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "tracking-off"

        command.handle(DefaultCommandContext(event), user, 5)

        assertTrue(user.activityTrackingOptOut)
        verify(exactly = 1) { userService.updateUser(user) }
    }

    @Test
    fun `me replies with disabled message when guild tracking off`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "me"
        every { activityTrackingService.isGuildTrackingEnabled(guildId) } returns false

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { rollupService.forUser(any(), any()) }
    }

    @Test
    fun `me replies with opt-out message when user opted out`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { activityTrackingOptOut = true }
        every { event.subcommandName } returns "me"
        every { activityTrackingService.isGuildTrackingEnabled(guildId) } returns true

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { rollupService.forUser(any(), any()) }
    }

    @Test
    fun `server replies with disabled message when guild tracking off`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "server"
        every { activityTrackingService.isGuildTrackingEnabled(guildId) } returns false

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { rollupService.forGuildMonth(any(), any()) }
    }
}

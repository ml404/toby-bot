package bot.toby.command.commands.misc

import bot.toby.activity.ActivityTrackingService
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.DefaultCommandContext
import database.dto.ActivityMonthlyRollupDto
import database.dto.UserDto
import database.service.activity.ActivityMonthlyRollupService
import database.service.user.UserService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

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
        // setEphemeral returns `R` (self-type), which mockk(relaxed = true) defaults
        // to a generic MessageCreateRequest proxy — not assignable to
        // WebhookMessageCreateAction. Pin it to the typed mock so the
        // `.addComponents(...)` call downstream resolves correctly.
        every { CommandTest.webhookMessageCreateAction.setEphemeral(any()) } returns
            CommandTest.webhookMessageCreateAction
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

    // ---- Month option ----

    private fun stubMonth(value: String?) {
        if (value == null) {
            every { event.getOption(ActivityCommand.OPT_MONTH) } returns null
        } else {
            val opt = mockk<OptionMapping>()
            every { opt.asString } returns value
            every { event.getOption(ActivityCommand.OPT_MONTH) } returns opt
        }
    }

    private fun rollupFor(name: String, seconds: Long, month: LocalDate) = ActivityMonthlyRollupDto(
        discordId = 99L,
        guildId = guildId,
        monthStart = month,
        activityName = name,
        seconds = seconds,
    )

    @Test
    fun `server with valid month option queries that month's rollups`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "server"
        every { activityTrackingService.isGuildTrackingEnabled(guildId) } returns true
        val target = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(2)
        stubMonth("%04d-%02d".format(target.year, target.monthValue))
        every { userService.listGuildUsers(guildId) } returns emptyList()
        val monthSlot = slot<LocalDate>()
        every { rollupService.forGuildMonth(guildId, capture(monthSlot)) } returns listOf(
            rollupFor("Halo", 600, target)
        )

        command.handle(DefaultCommandContext(event), user, 5)

        assertEquals(target, monthSlot.captured)
    }

    @Test
    fun `server with out-of-range month falls back to current month`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "server"
        every { activityTrackingService.isGuildTrackingEnabled(guildId) } returns true
        stubMonth("2010-01")
        every { userService.listGuildUsers(guildId) } returns emptyList()
        val monthSlot = slot<LocalDate>()
        every { rollupService.forGuildMonth(guildId, capture(monthSlot)) } returns emptyList()

        command.handle(DefaultCommandContext(event), user, 5)

        val expected = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        assertEquals(expected, monthSlot.captured)
    }

    @Test
    fun `server with unparseable month falls back to current month`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "server"
        every { activityTrackingService.isGuildTrackingEnabled(guildId) } returns true
        stubMonth("not-a-month")
        every { userService.listGuildUsers(guildId) } returns emptyList()
        val monthSlot = slot<LocalDate>()
        every { rollupService.forGuildMonth(guildId, capture(monthSlot)) } returns emptyList()

        command.handle(DefaultCommandContext(event), user, 5)

        val expected = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        assertEquals(expected, monthSlot.captured)
    }

    @Test
    fun `server with games attaches a contrib select menu component`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "server"
        every { activityTrackingService.isGuildTrackingEnabled(guildId) } returns true
        stubMonth(null)
        every { userService.listGuildUsers(guildId) } returns emptyList()
        val thisMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        every { rollupService.forGuildMonth(guildId, thisMonth) } returns listOf(
            rollupFor("Halo", 600, thisMonth),
            rollupFor("Tetris", 300, thisMonth),
        )
        val rowSlot = slot<ActionRow>()
        every { CommandTest.webhookMessageCreateAction.addComponents(capture(rowSlot)) } returns
            CommandTest.webhookMessageCreateAction

        command.handle(DefaultCommandContext(event), user, 5)

        verify(atLeast = 1) { CommandTest.webhookMessageCreateAction.addComponents(any<ActionRow>()) }
        val componentId = rowSlot.captured.components.first().uniqueId.toString()
        // We can't introspect StringSelectMenu component id directly from JDA's
        // ActionRow without casting; the smoke check above is that addComponents
        // was called at all. Deeper menu-id assertions live in ActivityContribMenuTest.
        assertNotNull(componentId)
    }

    @Test
    fun `server with no games does not attach a select menu`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "server"
        every { activityTrackingService.isGuildTrackingEnabled(guildId) } returns true
        stubMonth(null)
        every { userService.listGuildUsers(guildId) } returns emptyList()
        every { rollupService.forGuildMonth(any(), any()) } returns emptyList()

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { CommandTest.webhookMessageCreateAction.addComponents(any<ActionRow>()) }
    }
}

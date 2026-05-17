package bot.toby.menu.menus

import bot.toby.menu.DefaultMenuContext
import database.dto.ActivityMonthlyRollupDto
import database.dto.UserDto
import database.service.ActivityMonthlyRollupService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ActivityContribMenuTest {

    private val guildId = 42L
    private val month: LocalDate = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)

    private lateinit var rollupService: ActivityMonthlyRollupService
    private lateinit var userService: UserService
    private lateinit var menu: ActivityContribMenu
    private lateinit var ctx: DefaultMenuContext
    private lateinit var event: StringSelectInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var guild: Guild
    private lateinit var sendAction: WebhookMessageCreateAction<*>

    @BeforeEach
    fun setup() {
        rollupService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        menu = ActivityContribMenu(rollupService, userService)

        event = mockk(relaxed = true)
        hook = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        sendAction = mockk(relaxed = true)
        ctx = mockk(relaxed = true)

        every { ctx.event } returns event
        every { ctx.guild } returns guild
        every { event.hook } returns hook
        every { event.deferReply(true) } returns mockk(relaxed = true)
        every { hook.sendMessage(any<String>()) } returns sendAction
        every { hook.sendMessageEmbeds(any<MessageEmbed>()) } returns sendAction
        every { sendAction.setEphemeral(any()) } returns sendAction
    }

    @Test
    fun `name is the routing prefix`() {
        assertEquals("activitycontrib", menu.name)
    }

    @Test
    fun `componentId parses back to the right month and guild`() {
        every { event.componentId } returns "activitycontrib:$guildId:${month.toEpochDay()}"
        every { event.selectedOptions } returns listOf(SelectOption.of("Halo", "Halo"))
        every { userService.listGuildUsers(guildId) } returns emptyList()
        val monthSlot = slot<LocalDate>()
        val guildSlot = slot<Long>()
        every { rollupService.forGuildMonth(capture(guildSlot), capture(monthSlot)) } returns
            listOf(rollup(1L, "Halo", 600))

        menu.handle(ctx, 0)

        assertEquals(guildId, guildSlot.captured)
        assertEquals(month, monthSlot.captured)
    }

    @Test
    fun `lists top 8 contributors sorted descending`() {
        every { event.componentId } returns "activitycontrib:$guildId:${month.toEpochDay()}"
        every { event.selectedOptions } returns listOf(SelectOption.of("Halo", "Halo"))
        every { userService.listGuildUsers(guildId) } returns emptyList()
        val rows = (1..10).map { i -> rollup(i.toLong(), "Halo", (11 - i) * 100L) }
        every { rollupService.forGuildMonth(guildId, month) } returns rows

        val embedSlot = slot<MessageEmbed>()
        every { hook.sendMessageEmbeds(capture(embedSlot)) } returns sendAction

        menu.handle(ctx, 0)

        val description = embedSlot.captured.description ?: ""
        val lines = description.lines().filter { it.startsWith("• ") }
        assertEquals(8, lines.size, "should cap at TOP_CONTRIBUTORS_LIMIT")
        // The top entry has the most playtime — discord id 1 with 1000 seconds.
        assertTrue(lines.first().contains("16m"), "expected top contributor's time in line: ${lines.first()}")
    }

    @Test
    fun `filters out opted-out users`() {
        every { event.componentId } returns "activitycontrib:$guildId:${month.toEpochDay()}"
        every { event.selectedOptions } returns listOf(SelectOption.of("Halo", "Halo"))
        every { userService.listGuildUsers(guildId) } returns listOf(
            UserDto(discordId = 7L, guildId = guildId).apply { activityTrackingOptOut = true },
            UserDto(discordId = 8L, guildId = guildId)
        )
        every { rollupService.forGuildMonth(guildId, month) } returns listOf(
            rollup(7L, "Halo", 9_999),
            rollup(8L, "Halo", 100),
        )

        val embedSlot = slot<MessageEmbed>()
        every { hook.sendMessageEmbeds(capture(embedSlot)) } returns sendAction

        menu.handle(ctx, 0)

        val description = embedSlot.captured.description ?: ""
        val lines = description.lines().filter { it.startsWith("• ") }
        assertEquals(1, lines.size, "opted-out user must not appear")
    }

    @Test
    fun `empty result shows a friendly message`() {
        every { event.componentId } returns "activitycontrib:$guildId:${month.toEpochDay()}"
        every { event.selectedOptions } returns listOf(SelectOption.of("Halo", "Halo"))
        every { userService.listGuildUsers(guildId) } returns emptyList()
        every { rollupService.forGuildMonth(guildId, month) } returns emptyList()

        val embedSlot = slot<MessageEmbed>()
        every { hook.sendMessageEmbeds(capture(embedSlot)) } returns sendAction

        menu.handle(ctx, 0)

        assertTrue(
            (embedSlot.captured.description ?: "").contains("No contributors", ignoreCase = true)
        )
    }

    @Test
    fun `malformed componentId replies with a graceful message`() {
        every { event.componentId } returns "activitycontrib:onlyone"
        every { event.selectedOptions } returns listOf(SelectOption.of("Halo", "Halo"))

        menu.handle(ctx, 0)

        verify { hook.sendMessage(match<String> { it.contains("expired", ignoreCase = true) }) }
    }

    @Test
    fun `non-numeric epoch day replies with a graceful message`() {
        every { event.componentId } returns "activitycontrib:$guildId:notanumber"
        every { event.selectedOptions } returns listOf(SelectOption.of("Halo", "Halo"))

        menu.handle(ctx, 0)

        verify { hook.sendMessage(match<String> { it.contains("expired", ignoreCase = true) }) }
    }

    private fun rollup(discordId: Long, name: String, seconds: Long) = ActivityMonthlyRollupDto(
        discordId = discordId,
        guildId = guildId,
        monthStart = month,
        activityName = name,
        seconds = seconds,
    )
}

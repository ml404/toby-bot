package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.DefaultCommandContext
import database.dto.guild.TitleDto
import database.dto.user.UserDto
import database.dto.guild.UserOwnedTitleDto
import database.service.guild.TitleService
import database.service.user.UserService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.service.TitleRoleResult
import web.service.TitleRoleService

internal class TitleCommandTest : CommandTest {
    private lateinit var titleService: TitleService
    private lateinit var userService: UserService
    private lateinit var titleRoleService: TitleRoleService
    private lateinit var command: TitleCommand

    private val discordId = 1L
    private val guildId = 1L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        titleService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        titleRoleService = mockk(relaxed = true)
        command = TitleCommand(titleService, userService, titleRoleService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun stringOpt(value: String): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asString } returns value
        return o
    }

    @Test
    fun `buy deducts credits and records purchase when user has enough`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 500L }
        every { event.subcommandName } returns "buy"
        every { event.getOption("title") } returns stringOpt("⭐ Comrade")
        every { titleService.getByLabel("⭐ Comrade") } returns TitleDto(id = 7L, label = "⭐ Comrade", cost = 100L)
        every { titleService.owns(discordId, 7L) } returns false

        command.handle(DefaultCommandContext(event), user, 5)

        assertEquals(400L, user.socialCredit)
        verify(exactly = 1) { userService.updateUser(user) }
        verify(exactly = 1) { titleService.recordPurchase(discordId, 7L) }
    }

    @Test
    fun `buy rejects when balance is below cost`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 50L }
        every { event.subcommandName } returns "buy"
        every { event.getOption("title") } returns stringOpt("Expensive")
        every { titleService.getByLabel("Expensive") } returns TitleDto(id = 1L, label = "Expensive", cost = 1_000L)
        every { titleService.owns(discordId, 1L) } returns false

        command.handle(DefaultCommandContext(event), user, 5)

        assertEquals(50L, user.socialCredit)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }

    @Test
    fun `buy rejects when title is already owned`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 500L }
        every { event.subcommandName } returns "buy"
        every { event.getOption("title") } returns stringOpt("⭐ Comrade")
        every { titleService.getByLabel("⭐ Comrade") } returns TitleDto(id = 7L, label = "⭐ Comrade", cost = 100L)
        every { titleService.owns(discordId, 7L) } returns true

        command.handle(DefaultCommandContext(event), user, 5)

        assertEquals(500L, user.socialCredit)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }

    @Test
    fun `buy rejects when actor level is below required level`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 100_000L
            xp = 0L
        }
        every { event.subcommandName } returns "buy"
        every { event.getOption("title") } returns stringOpt("🌱 Sprout")
        every { titleService.getByLabel("🌱 Sprout") } returns
            TitleDto(id = 42L, label = "🌱 Sprout", cost = 200L, requiredLevel = 5)
        every { titleService.owns(discordId, 42L) } returns false

        command.handle(DefaultCommandContext(event), user, 5)

        assertEquals(100_000L, user.socialCredit)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }

    @Test
    fun `buy succeeds when actor level meets required level`() {
        // xp 1000 -> level 5+ (cumulative to level 5 is 100+155+220+295+380=1150; so 1200 xp = lvl 5).
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 500L
            xp = 1_200L
        }
        every { event.subcommandName } returns "buy"
        every { event.getOption("title") } returns stringOpt("🌱 Sprout")
        every { titleService.getByLabel("🌱 Sprout") } returns
            TitleDto(id = 42L, label = "🌱 Sprout", cost = 200L, requiredLevel = 5)
        every { titleService.owns(discordId, 42L) } returns false

        command.handle(DefaultCommandContext(event), user, 5)

        assertEquals(300L, user.socialCredit)
        verify(exactly = 1) { userService.updateUser(user) }
        verify(exactly = 1) { titleService.recordPurchase(discordId, 42L) }
    }

    @Test
    fun `buy rejects unknown title label`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 500L }
        every { event.subcommandName } returns "buy"
        every { event.getOption("title") } returns stringOpt("Unknown")
        every { titleService.getByLabel("Unknown") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }

    @Test
    fun `equip sets activeTitleId when user owns the title and role service succeeds`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 100L }
        val title = TitleDto(id = 7L, label = "⭐ Comrade", cost = 100L)
        every { event.subcommandName } returns "equip"
        every { event.getOption("title") } returns stringOpt("⭐ Comrade")
        every { titleService.getByLabel("⭐ Comrade") } returns title
        every { titleService.owns(discordId, 7L) } returns true
        every { titleService.listOwned(discordId) } returns listOf(
            UserOwnedTitleDto(discordId = discordId, titleId = 7L)
        )
        every { titleRoleService.equip(guild, member, title, setOf(7L)) } returns TitleRoleResult.Ok

        command.handle(DefaultCommandContext(event), user, 5)

        assertEquals(7L, user.activeTitleId)
        verify(exactly = 1) { userService.updateUser(user) }
    }

    @Test
    fun `equip rejects when user does not own the title`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "equip"
        every { event.getOption("title") } returns stringOpt("⭐ Comrade")
        every { titleService.getByLabel("⭐ Comrade") } returns TitleDto(id = 7L, label = "⭐ Comrade", cost = 100L)
        every { titleService.owns(discordId, 7L) } returns false

        command.handle(DefaultCommandContext(event), user, 5)

        assertNull(user.activeTitleId)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `equip rolls back activeTitleId when role service fails`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        val title = TitleDto(id = 7L, label = "⭐ Comrade", cost = 100L)
        every { event.subcommandName } returns "equip"
        every { event.getOption("title") } returns stringOpt("⭐ Comrade")
        every { titleService.getByLabel("⭐ Comrade") } returns title
        every { titleService.owns(discordId, 7L) } returns true
        every { titleService.listOwned(discordId) } returns listOf(
            UserOwnedTitleDto(discordId = discordId, titleId = 7L)
        )
        every { titleRoleService.equip(guild, member, title, any()) } returns TitleRoleResult.Error("no Manage Roles")

        command.handle(DefaultCommandContext(event), user, 5)

        assertNull(user.activeTitleId)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `unequip clears activeTitleId when role service succeeds`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { activeTitleId = 7L }
        every { event.subcommandName } returns "unequip"
        every { titleService.listOwned(discordId) } returns listOf(
            UserOwnedTitleDto(discordId = discordId, titleId = 7L)
        )
        every { titleRoleService.unequip(guild, member, setOf(7L)) } returns TitleRoleResult.Ok

        command.handle(DefaultCommandContext(event), user, 5)

        assertNull(user.activeTitleId)
        verify(exactly = 1) { userService.updateUser(user) }
    }

    @Test
    fun `unequip is a no-op when no active title`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "unequip"

        command.handle(DefaultCommandContext(event), user, 5)

        assertNull(user.activeTitleId)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `buildShopBody marks gated titles with a lock chip and leaves ungated lines clean`() {
        val titles = listOf(
            TitleDto(id = 1L, label = "⭐ Comrade", cost = 100L, requiredLevel = 0).apply {
                description = "Standard issue."
            },
            TitleDto(id = 2L, label = "🌱 Sprout", cost = 200L, requiredLevel = 5).apply {
                description = "Found their footing."
            },
            TitleDto(id = 3L, label = "🐉 Wyrmlord", cost = 500_000L, requiredLevel = 125).apply {
                description = "Older than the channel logs."
            },
        )

        val body = TitleCommand.buildShopBody(titles)

        assertTrue(body.contains("**⭐ Comrade** · 100 credits — Standard issue.")) {
            "ungated entry should render without a lock chip:\n$body"
        }
        assertTrue(body.contains("**🌱 Sprout** · 200 credits · 🔒 Lvl 5 — Found their footing.")) {
            "gated entry should advertise its level requirement next to the cost:\n$body"
        }
        assertTrue(body.contains("**🐉 Wyrmlord** · 500000 credits · 🔒 Lvl 125 — Older than the channel logs.")) {
            "high-tier gated entry should advertise its level requirement:\n$body"
        }
        val comradeLine = body.lineSequence().single { it.contains("Comrade") }
        assertFalse(comradeLine.contains("🔒")) {
            "ungated entry must not show a lock chip:\n$comradeLine"
        }
    }

    @Test
    fun `buildShopBody returns a friendly placeholder when the catalog is empty`() {
        assertEquals("No titles are available right now.", TitleCommand.buildShopBody(emptyList()))
    }

    @Test
    fun `buildShopBody omits the dash when description is null or blank`() {
        val titles = listOf(
            TitleDto(id = 1L, label = "🌱 Sprout", cost = 200L, requiredLevel = 5),
            TitleDto(id = 2L, label = "🪙 Coin", cost = 50L, requiredLevel = 0).apply { description = "" },
            TitleDto(id = 3L, label = "🌀 Whirl", cost = 75L, requiredLevel = 0).apply { description = "   " },
        )

        val body = TitleCommand.buildShopBody(titles)

        val lines = body.lines()
        assertEquals("**🌱 Sprout** · 200 credits · 🔒 Lvl 5", lines[0])
        assertEquals("**🪙 Coin** · 50 credits", lines[1])
        assertEquals("**🌀 Whirl** · 75 credits", lines[2])
    }
}

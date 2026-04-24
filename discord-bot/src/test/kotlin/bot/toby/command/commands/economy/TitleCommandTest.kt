package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.DefaultCommandContext
import database.dto.TitleDto
import database.dto.UserDto
import database.dto.UserOwnedTitleDto
import database.service.TitleService
import database.service.UserService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}

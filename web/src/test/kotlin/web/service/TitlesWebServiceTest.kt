package web.service

import database.dto.TitleDto
import database.dto.UserDto
import database.dto.UserOwnedTitleDto
import database.service.EconomyTradeService
import database.service.TitleService
import database.service.TobyCoinMarketService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TitlesWebServiceTest {

    private lateinit var jda: JDA
    private lateinit var userService: UserService
    private lateinit var titleService: TitleService
    private lateinit var titleRoleService: TitleRoleService
    private lateinit var introWebService: IntroWebService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var service: TitlesWebService

    private lateinit var guild: Guild

    private val guildId = 222L
    private val plainUserId = 102L

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        titleRoleService = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        service = TitlesWebService(
            jda,
            userService,
            titleService,
            titleRoleService,
            introWebService,
            marketService,
            tradeService
        )

        every { jda.getGuildById(guildId) } returns guild
        every { guild.id } returns guildId.toString()
        every { guild.name } returns "Test Guild"
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun mockMember(id: Long): Member {
        val m = mockk<Member>(relaxed = true)
        every { m.idLong } returns id
        every { m.id } returns id.toString()
        every { guild.getMemberById(id) } returns m
        return m
    }

    // ---- isMember ----

    @Test
    fun `isMember true when guild and member exist`() {
        mockMember(plainUserId)
        assertTrue(service.isMember(plainUserId, guildId))
    }

    @Test
    fun `isMember false when guild missing`() {
        every { jda.getGuildById(guildId) } returns null
        assertEquals(false, service.isMember(plainUserId, guildId))
    }

    @Test
    fun `isMember false when member not in guild`() {
        every { guild.getMemberById(plainUserId) } returns null
        assertEquals(false, service.isMember(plainUserId, guildId))
    }

    // ---- getTitlesForGuild ----

    @Test
    fun `getTitlesForGuild returns catalog with ownership, equipped flag, and balance`() {
        val titles = listOf(
            TitleDto(id = 1L, label = "A", cost = 100L, description = "d1"),
            TitleDto(id = 2L, label = "B", cost = 500L, description = null, colorHex = "#FFD700", hoisted = true)
        )
        val owned = listOf(UserOwnedTitleDto(discordId = plainUserId, titleId = 1L))
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply {
            socialCredit = 350L
            activeTitleId = 1L
        }
        every { titleService.listAll() } returns titles
        every { titleService.listOwned(plainUserId) } returns owned
        every { userService.getUserById(plainUserId, guildId) } returns actor

        val view = service.getTitlesForGuild(guildId, plainUserId)
        assertEquals(2, view.catalog.size)
        assertEquals(setOf(1L), view.ownedTitleIds)
        assertEquals(1L, view.equippedTitleId)
        assertEquals(350L, view.balance)
    }

    // ---- buyTitle ----

    @Test
    fun `buyTitle deducts credits and records purchase`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 500L }
        every { titleService.getById(1L) } returns title
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.owns(plainUserId, 1L) } returns false

        val err = service.buyTitle(plainUserId, guildId, 1L)
        assertNull(err)
        assertEquals(400L, actor.socialCredit)
        verify(exactly = 1) { userService.updateUser(actor) }
        verify(exactly = 1) { titleService.recordPurchase(plainUserId, 1L) }
    }

    @Test
    fun `buyTitle rejects when user already owns the title`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 500L }
        every { titleService.getById(1L) } returns title
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.owns(plainUserId, 1L) } returns true

        val err = service.buyTitle(plainUserId, guildId, 1L)
        assertEquals("You already own this title.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }

    @Test
    fun `buyTitle rejects when credits insufficient`() {
        val title = TitleDto(id = 1L, label = "A", cost = 1_000L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 50L }
        every { titleService.getById(1L) } returns title
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.owns(plainUserId, 1L) } returns false

        val err = service.buyTitle(plainUserId, guildId, 1L)
        assertNotNull(err)
        assertTrue(err!!.contains("Not enough credits"))
        assertEquals(50L, actor.socialCredit)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `buyTitle rejects when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        val err = service.buyTitle(plainUserId, guildId, 1L)
        assertEquals("Bot is not in that server.", err)
    }

    @Test
    fun `buyTitle rejects when user has no profile`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        every { titleService.getById(1L) } returns title
        every { userService.getUserById(plainUserId, guildId) } returns null

        val err = service.buyTitle(plainUserId, guildId, 1L)
        assertEquals("You don't have a profile in that server yet.", err)
    }

    @Test
    fun `buyTitle rejects when title does not exist`() {
        every { titleService.getById(99L) } returns null
        val err = service.buyTitle(plainUserId, guildId, 99L)
        assertEquals("Title not found.", err)
    }

    // ---- equipTitle ----

    @Test
    fun `equipTitle sets activeTitleId when role service succeeds`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId)
        val member = mockMember(plainUserId)
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.getById(1L) } returns title
        every { titleService.owns(plainUserId, 1L) } returns true
        every { titleService.listOwned(plainUserId) } returns listOf(UserOwnedTitleDto(discordId = plainUserId, titleId = 1L))
        every { titleRoleService.equip(guild, member, title, setOf(1L)) } returns TitleRoleResult.Ok

        val err = service.equipTitle(plainUserId, guildId, 1L)
        assertNull(err)
        assertEquals(1L, actor.activeTitleId)
        verify(exactly = 1) { userService.updateUser(actor) }
    }

    @Test
    fun `equipTitle rejects when user does not own the title`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId)
        mockMember(plainUserId)
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.getById(1L) } returns title
        every { titleService.owns(plainUserId, 1L) } returns false

        val err = service.equipTitle(plainUserId, guildId, 1L)
        assertEquals("You don't own this title.", err)
        assertNull(actor.activeTitleId)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `equipTitle surfaces role service error and does not persist`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId)
        val member = mockMember(plainUserId)
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.getById(1L) } returns title
        every { titleService.owns(plainUserId, 1L) } returns true
        every { titleService.listOwned(plainUserId) } returns listOf(UserOwnedTitleDto(discordId = plainUserId, titleId = 1L))
        every { titleRoleService.equip(guild, member, title, any()) } returns TitleRoleResult.Error("no Manage Roles")

        val err = service.equipTitle(plainUserId, guildId, 1L)
        assertEquals("no Manage Roles", err)
        assertNull(actor.activeTitleId)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    // ---- unequipTitle ----

    @Test
    fun `unequipTitle clears activeTitleId when role service succeeds`() {
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply { activeTitleId = 1L }
        val member = mockMember(plainUserId)
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.listOwned(plainUserId) } returns listOf(UserOwnedTitleDto(discordId = plainUserId, titleId = 1L))
        every { titleRoleService.unequip(guild, member, setOf(1L)) } returns TitleRoleResult.Ok

        val err = service.unequipTitle(plainUserId, guildId)
        assertNull(err)
        assertNull(actor.activeTitleId)
        verify(exactly = 1) { userService.updateUser(actor) }
    }

    @Test
    fun `unequipTitle surfaces role service error`() {
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply { activeTitleId = 1L }
        val member = mockMember(plainUserId)
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.listOwned(plainUserId) } returns emptyList()
        every { titleRoleService.unequip(guild, member, any()) } returns TitleRoleResult.Error("bot has no Manage Roles")

        val err = service.unequipTitle(plainUserId, guildId)
        assertEquals("bot has no Manage Roles", err)
        assertEquals(1L, actor.activeTitleId)
        verify(exactly = 0) { userService.updateUser(any()) }
    }
}

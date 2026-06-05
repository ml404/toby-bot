package web.service

import database.dto.guild.TitleDto
import database.dto.user.UserDto
import database.dto.guild.UserOwnedTitleDto
import database.service.economy.EconomyTradeService
import database.service.guild.TitleService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.util.GuildMembership

/**
 * Covers branches in TitlesWebService NOT exercised by TitlesWebServiceTest or
 * TitlesWebServicePurchaseTest:
 * - getMemberGuilds (various null/skip paths, sorting, equippedTitle population)
 * - equipTitle guard branches (bot not in guild, member not in guild, actor missing, title missing)
 * - unequipTitle guard branches (bot not in guild, member not in guild, actor missing)
 * - getTitlesForGuild with null actor (zero balance/coins)
 */
class TitlesWebServiceMoreTest {

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
    private val userId = 102L

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
            jda = jda,
            userService = userService,
            titleService = titleService,
            titleRoleService = titleRoleService,
            introWebService = introWebService,
            marketService = marketService,
            tradeService = tradeService,
            membership = GuildMembership(jda),
        )

        every { jda.getGuildById(guildId) } returns guild
        every { guild.id } returns guildId.toString()
        every { guild.name } returns "Test Guild"
    }

    private fun mockMember(id: Long): Member {
        val m = mockk<Member>(relaxed = true)
        every { m.idLong } returns id
        every { m.id } returns id.toString()
        every { guild.getMemberById(id) } returns m
        return m
    }

    // ---- getMemberGuilds ----

    @Test
    fun `getMemberGuilds returns empty when introWebService has no mutual guilds`() {
        every { introWebService.getMutualGuilds("token") } returns emptyList()
        assertTrue(service.getMemberGuilds("token", userId).isEmpty())
    }

    @Test
    fun `getMemberGuilds skips guild with non-numeric id`() {
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo("not-a-number", "Bad", null)
        )
        assertTrue(service.getMemberGuilds("token", userId).isEmpty())
    }

    @Test
    fun `getMemberGuilds skips guild when user is not a member`() {
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo(guildId.toString(), "Guild", null)
        )
        every { guild.getMemberById(userId) } returns null
        assertTrue(service.getMemberGuilds("token", userId).isEmpty())
    }

    @Test
    fun `getMemberGuilds includes card with balance and no equipped title when user has no active title`() {
        mockMember(userId)
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo(guildId.toString(), "Test Guild", "hash1")
        )
        every { userService.getUserById(userId, guildId) } returns
            UserDto(userId, guildId).apply { socialCredit = 400L; activeTitleId = null }
        every { titleService.listOwned(userId) } returns emptyList()

        val result = service.getMemberGuilds("token", userId)
        assertEquals(1, result.size)
        assertEquals(400L, result[0].balance)
        assertNull(result[0].equippedTitle)
    }

    @Test
    fun `getMemberGuilds includes equipped title label when user has active title`() {
        mockMember(userId)
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo(guildId.toString(), "Test Guild", null)
        )
        every { userService.getUserById(userId, guildId) } returns
            UserDto(userId, guildId).apply { socialCredit = 100L; activeTitleId = 7L }
        every { titleService.getById(7L) } returns TitleDto(id = 7L, label = "Champion", cost = 500L)

        val result = service.getMemberGuilds("token", userId)
        assertEquals(1, result.size)
        assertEquals("Champion", result[0].equippedTitle)
    }

    @Test
    fun `getMemberGuilds uses null equippedTitle when activeTitleId not found`() {
        mockMember(userId)
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo(guildId.toString(), "Test Guild", null)
        )
        every { userService.getUserById(userId, guildId) } returns
            UserDto(userId, guildId).apply { socialCredit = 100L; activeTitleId = 999L }
        every { titleService.getById(999L) } returns null

        val result = service.getMemberGuilds("token", userId)
        assertEquals(1, result.size)
        assertNull(result[0].equippedTitle)
    }

    @Test
    fun `getMemberGuilds uses zero balance when user not found`() {
        mockMember(userId)
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo(guildId.toString(), "Test Guild", null)
        )
        every { userService.getUserById(userId, guildId) } returns null

        val result = service.getMemberGuilds("token", userId)
        assertEquals(1, result.size)
        assertEquals(0L, result[0].balance)
        assertNull(result[0].equippedTitle)
    }

    @Test
    fun `getMemberGuilds sorts guilds by name case-insensitively`() {
        val guild2 = mockk<Guild>(relaxed = true)
        every { guild2.getMemberById(userId) } returns mockk(relaxed = true)
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo(guildId.toString(), "Zeta", null),
            GuildInfo("300", "alpha", null),
        )
        every { jda.getGuildById(300L) } returns guild2
        every { guild.getMemberById(userId) } returns mockk(relaxed = true)
        every { userService.getUserById(any(), any()) } returns null

        val result = service.getMemberGuilds("token", userId)
        assertEquals(listOf("alpha", "Zeta"), result.map { it.name })
    }

    // ---- equipTitle guard branches ----

    @Test
    fun `equipTitle returns error when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        val err = service.equipTitle(userId, guildId, 1L)
        assertEquals("Bot is not in that server.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `equipTitle returns error when user is not a member of the guild`() {
        every { guild.getMemberById(userId) } returns null
        val err = service.equipTitle(userId, guildId, 1L)
        assertEquals("You are not a member of that server.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `equipTitle returns error when actor has no profile`() {
        mockMember(userId)
        every { userService.getUserById(userId, guildId) } returns null
        val err = service.equipTitle(userId, guildId, 1L)
        assertEquals("No profile in that server.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `equipTitle returns error when title not found`() {
        mockMember(userId)
        every { userService.getUserById(userId, guildId) } returns
            UserDto(userId, guildId)
        every { titleService.getById(1L) } returns null
        val err = service.equipTitle(userId, guildId, 1L)
        assertEquals("Title not found.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `equipTitle returns error when user does not own the title`() {
        mockMember(userId)
        every { userService.getUserById(userId, guildId) } returns UserDto(userId, guildId)
        every { titleService.getById(1L) } returns TitleDto(id = 1L, label = "A", cost = 100L)
        every { titleService.owns(userId, 1L) } returns false
        val err = service.equipTitle(userId, guildId, 1L)
        assertEquals("You don't own this title.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    // ---- unequipTitle guard branches ----

    @Test
    fun `unequipTitle returns error when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        val err = service.unequipTitle(userId, guildId)
        assertEquals("Bot is not in that server.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `unequipTitle returns error when user is not a member of the guild`() {
        every { guild.getMemberById(userId) } returns null
        val err = service.unequipTitle(userId, guildId)
        assertEquals("You are not a member of that server.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `unequipTitle returns error when actor has no profile`() {
        mockMember(userId)
        every { userService.getUserById(userId, guildId) } returns null
        val err = service.unequipTitle(userId, guildId)
        assertEquals("No profile in that server.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    // ---- getTitlesForGuild: null actor branch ----

    @Test
    fun `getTitlesForGuild returns zero balance and coins when actor not found`() {
        every { titleService.listAll() } returns emptyList()
        every { titleService.listOwned(userId) } returns emptyList()
        every { userService.getUserById(userId, guildId) } returns null
        every { marketService.getMarket(guildId) } returns null

        val view = service.getTitlesForGuild(guildId, userId)
        assertEquals(0L, view.balance)
        assertEquals(0L, view.tobyCoins)
        assertNull(view.equippedTitleId)
        assertEquals(0.0, view.marketPrice)
    }

    @Test
    fun `getTitlesForGuild returns empty catalog when titleService throws`() {
        every { titleService.listAll() } throws RuntimeException("DB down")
        every { titleService.listOwned(userId) } returns emptyList()
        every { userService.getUserById(userId, guildId) } returns null
        every { marketService.getMarket(guildId) } returns null

        // The 'safely' wrapper must swallow the exception and return the default
        val view = service.getTitlesForGuild(guildId, userId)
        assertTrue(view.catalog.isEmpty())
    }

    @Test
    fun `getTitlesForGuild returns empty ownedTitleIds when listOwned throws`() {
        every { titleService.listAll() } returns listOf(TitleDto(id = 1L, label = "A", cost = 10L))
        every { titleService.listOwned(userId) } throws RuntimeException("DB down")
        every { userService.getUserById(userId, guildId) } returns null
        every { marketService.getMarket(guildId) } returns null

        val view = service.getTitlesForGuild(guildId, userId)
        assertTrue(view.ownedTitleIds.isEmpty())
    }
}

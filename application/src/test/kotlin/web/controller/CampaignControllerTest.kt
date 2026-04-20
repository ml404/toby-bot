package web.controller

import database.dto.CampaignDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.CampaignDetail
import web.service.CampaignWebService
import web.service.EndResult
import web.service.GuildCampaignInfo
import web.service.JoinResult
import web.service.KickResult
import web.service.LeaveResult
import web.service.SetAliveResult
import web.service.SetCharacterResult

class CampaignControllerTest {

    private lateinit var campaignWebService: CampaignWebService
    private lateinit var controller: CampaignController

    private val mockUser = mockk<OAuth2User>(relaxed = true)
    private val mockClient = mockk<OAuth2AuthorizedClient>(relaxed = true)
    private val mockModel = mockk<Model>(relaxed = true)
    private val mockRa = mockk<RedirectAttributes>(relaxed = true)

    private val discordId = "1"
    private val guildId = 100L

    private fun makeCampaign() = CampaignDto(
        id = 10L, guildId = guildId, channelId = guildId, dmDiscordId = 1L, name = "Test Campaign"
    )

    @BeforeEach
    fun setup() {
        campaignWebService = mockk(relaxed = true)
        controller = CampaignController(campaignWebService)

        every { mockUser.getAttribute<String>("id") } returns discordId
        every { mockUser.getAttribute<String>("username") } returns "TestUser"
        every { mockClient.accessToken } returns mockk<OAuth2AccessToken> {
            every { tokenValue } returns "mock-token"
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    // campaignList

    @Test
    fun `campaignList returns campaign view with guild list`() {
        val guilds = listOf(GuildCampaignInfo("100", "MyGuild", null, null))
        every { campaignWebService.getMutualGuildsWithCampaigns("mock-token") } returns guilds

        val view = controller.campaignList(mockClient, mockUser, mockModel)

        assertEquals("campaign", view)
        verify { mockModel.addAttribute("guilds", guilds) }
        verify { mockModel.addAttribute("username", "TestUser") }
    }

    @Test
    fun `campaignList passes empty list when no mutual guilds`() {
        every { campaignWebService.getMutualGuildsWithCampaigns("mock-token") } returns emptyList()

        val view = controller.campaignList(mockClient, mockUser, mockModel)

        assertEquals("campaign", view)
        verify { mockModel.addAttribute("guilds", emptyList<GuildCampaignInfo>()) }
    }

    // campaignDetail

    @Test
    fun `campaignDetail returns campaignDetail view for known guild`() {
        every { campaignWebService.getGuildName(guildId) } returns "MyGuild"
        val campaign = makeCampaign()
        val detail = CampaignDetail(
            campaign = campaign,
            players = emptyList(),
            dmName = "DungeonMaster",
            isCurrentUserPlayer = false,
            currentUserCharacterId = 42L
        )
        every { campaignWebService.getCampaignDetail(guildId, 1L) } returns detail

        val view = controller.campaignDetail(guildId, mockUser, mockModel, mockRa)

        assertEquals("campaignDetail", view)
        verify { mockModel.addAttribute("campaign", campaign) }
        verify { mockModel.addAttribute("dmName", "DungeonMaster") }
        verify { mockModel.addAttribute("isUserDm", true) }
        verify { mockModel.addAttribute("isUserPlayer", false) }
        verify { mockModel.addAttribute("currentUserCharacterId", 42L) }
    }

    @Test
    fun `campaignDetail redirects when bot not in guild`() {
        every { campaignWebService.getGuildName(guildId) } returns null

        val view = controller.campaignDetail(guildId, mockUser, mockModel, mockRa)

        assertEquals("redirect:/dnd/campaign", view)
        verify { mockRa.addFlashAttribute("error", "Bot is not in that server.") }
    }

    @Test
    fun `campaignDetail redirects when user id missing`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val view = controller.campaignDetail(guildId, mockUser, mockModel, mockRa)

        assertEquals("redirect:/dnd/campaign", view)
    }

    @Test
    fun `campaignDetail shows page with null campaign when none active`() {
        every { campaignWebService.getGuildName(guildId) } returns "MyGuild"
        every { campaignWebService.getCampaignDetail(guildId, 1L) } returns null

        val view = controller.campaignDetail(guildId, mockUser, mockModel, mockRa)

        assertEquals("campaignDetail", view)
        verify { mockModel.addAttribute("campaign", null) }
        verify { mockModel.addAttribute("isUserDm", false) }
        verify { mockModel.addAttribute("isUserPlayer", false) }
    }

    // createCampaign

    @Test
    fun `createCampaign redirects to detail page on success`() {
        every { campaignWebService.getGuildName(guildId) } returns "MyGuild"
        every { campaignWebService.createCampaign(guildId, 1L, "New Campaign") } returns makeCampaign()

        val view = controller.createCampaign(guildId, "New Campaign", mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `createCampaign sets error flash when campaign already exists`() {
        every { campaignWebService.getGuildName(guildId) } returns "MyGuild"
        every { campaignWebService.createCampaign(guildId, 1L, "Dup") } returns null

        val view = controller.createCampaign(guildId, "Dup", mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify { mockRa.addFlashAttribute("error", "A campaign is already active in this server.") }
    }

    @Test
    fun `createCampaign redirects to list when user id missing`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val view = controller.createCampaign(guildId, "Test", mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign", view)
    }

    @Test
    fun `createCampaign redirects with error when bot not in guild`() {
        every { campaignWebService.getGuildName(guildId) } returns null

        val view = controller.createCampaign(guildId, "Test", mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign", view)
        verify { mockRa.addFlashAttribute("error", "Bot is not in that server.") }
    }

    // joinCampaign

    @Test
    fun `joinCampaign redirects on success with no flash`() {
        every { campaignWebService.joinCampaign(guildId, 1L) } returns JoinResult.JOINED

        val view = controller.joinCampaign(guildId, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `joinCampaign sets error when no active campaign`() {
        every { campaignWebService.joinCampaign(guildId, 1L) } returns JoinResult.NO_ACTIVE_CAMPAIGN

        controller.joinCampaign(guildId, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "No active campaign in this server.") }
    }

    @Test
    fun `joinCampaign sets error when DM tries to join`() {
        every { campaignWebService.joinCampaign(guildId, 1L) } returns JoinResult.IS_DM

        controller.joinCampaign(guildId, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "You are the DM and cannot join as a player.") }
    }

    @Test
    fun `joinCampaign sets error when already joined`() {
        every { campaignWebService.joinCampaign(guildId, 1L) } returns JoinResult.ALREADY_JOINED

        controller.joinCampaign(guildId, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "You are already in this campaign.") }
    }

    @Test
    fun `joinCampaign redirects to list when user id missing`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val view = controller.joinCampaign(guildId, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign", view)
    }

    // leaveCampaign

    @Test
    fun `leaveCampaign redirects on success`() {
        every { campaignWebService.leaveCampaign(guildId, 1L) } returns LeaveResult.LEFT

        val view = controller.leaveCampaign(guildId, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `leaveCampaign sets error when no active campaign`() {
        every { campaignWebService.leaveCampaign(guildId, 1L) } returns LeaveResult.NO_ACTIVE_CAMPAIGN

        controller.leaveCampaign(guildId, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "No active campaign in this server.") }
    }

    @Test
    fun `leaveCampaign sets error when not a player`() {
        every { campaignWebService.leaveCampaign(guildId, 1L) } returns LeaveResult.NOT_A_PLAYER

        controller.leaveCampaign(guildId, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "You are not in this campaign.") }
    }

    // setLinkedCharacter

    @Test
    fun `setLinkedCharacter redirects on update success`() {
        every { campaignWebService.setLinkedCharacter(guildId, 1L, "12345") } returns SetCharacterResult.UPDATED

        val view = controller.setLinkedCharacter(guildId, "12345", mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `setLinkedCharacter redirects on clear`() {
        every { campaignWebService.setLinkedCharacter(guildId, 1L, "") } returns SetCharacterResult.CLEARED

        val view = controller.setLinkedCharacter(guildId, "", mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `setLinkedCharacter sets error on invalid input`() {
        every { campaignWebService.setLinkedCharacter(guildId, 1L, "bad") } returns SetCharacterResult.INVALID

        controller.setLinkedCharacter(guildId, "bad", mockUser, mockRa)

        verify {
            mockRa.addFlashAttribute(
                "error",
                "Could not extract a valid character ID. Paste a D&D Beyond URL or numeric ID."
            )
        }
    }

    @Test
    fun `setLinkedCharacter redirects to list when user id missing`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val view = controller.setLinkedCharacter(guildId, "12345", mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign", view)
    }

    // endCampaign

    @Test
    fun `endCampaign redirects with no flash on success`() {
        every { campaignWebService.endCampaign(guildId, 1L) } returns EndResult.ENDED

        val view = controller.endCampaign(guildId, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `endCampaign sets error when no active campaign`() {
        every { campaignWebService.endCampaign(guildId, 1L) } returns EndResult.NO_ACTIVE_CAMPAIGN

        controller.endCampaign(guildId, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "No active campaign in this server.") }
    }

    @Test
    fun `endCampaign sets error when not DM`() {
        every { campaignWebService.endCampaign(guildId, 1L) } returns EndResult.NOT_DM

        controller.endCampaign(guildId, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Only the Dungeon Master can end the campaign.") }
    }

    // kickPlayer

    @Test
    fun `kickPlayer redirects with no flash on success`() {
        every { campaignWebService.kickPlayer(guildId, 1L, 99L) } returns KickResult.KICKED

        val view = controller.kickPlayer(guildId, 99L, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `kickPlayer sets error when not DM`() {
        every { campaignWebService.kickPlayer(guildId, 1L, 99L) } returns KickResult.NOT_DM

        controller.kickPlayer(guildId, 99L, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Only the Dungeon Master can kick players.") }
    }

    @Test
    fun `kickPlayer sets error when target is DM`() {
        every { campaignWebService.kickPlayer(guildId, 1L, 1L) } returns KickResult.CANNOT_KICK_DM

        controller.kickPlayer(guildId, 1L, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "The DM can't be kicked.") }
    }

    @Test
    fun `kickPlayer sets error when target is not a player`() {
        every { campaignWebService.kickPlayer(guildId, 1L, 99L) } returns KickResult.NOT_A_PLAYER

        controller.kickPlayer(guildId, 99L, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "That user isn't in the campaign.") }
    }

    // setPlayerAlive

    @Test
    fun `setPlayerAlive redirects with no flash on success`() {
        every { campaignWebService.setPlayerAlive(guildId, 1L, 99L, false) } returns SetAliveResult.UPDATED

        val view = controller.setPlayerAlive(guildId, 99L, false, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `setPlayerAlive sets error when not DM`() {
        every { campaignWebService.setPlayerAlive(guildId, 1L, 99L, true) } returns SetAliveResult.NOT_DM

        controller.setPlayerAlive(guildId, 99L, true, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Only the Dungeon Master can change player status.") }
    }

    @Test
    fun `setPlayerAlive sets error when target is not a player`() {
        every { campaignWebService.setPlayerAlive(guildId, 1L, 99L, false) } returns SetAliveResult.NOT_A_PLAYER

        controller.setPlayerAlive(guildId, 99L, false, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "That user isn't in the campaign.") }
    }

    @Test
    fun `setPlayerAlive redirects to list when user id missing`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val view = controller.setPlayerAlive(guildId, 99L, false, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign", view)
    }
}

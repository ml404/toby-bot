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
import web.service.GuildCampaignInfo

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
        val detail = CampaignDetail(campaign, emptyList(), "DungeonMaster")
        every { campaignWebService.getCampaignDetail(guildId, 1L) } returns detail

        val view = controller.campaignDetail(guildId, mockUser, mockModel, mockRa)

        assertEquals("campaignDetail", view)
        verify { mockModel.addAttribute("campaign", campaign) }
        verify { mockModel.addAttribute("dmName", "DungeonMaster") }
        verify { mockModel.addAttribute("isUserDm", true) }
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
}

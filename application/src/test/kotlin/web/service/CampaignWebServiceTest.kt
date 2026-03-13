package web.service

import database.dto.CampaignDto
import database.dto.CampaignPlayerDto
import database.dto.CampaignPlayerId
import database.service.CampaignPlayerService
import database.service.CampaignService
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CampaignWebServiceTest {

    private lateinit var campaignService: CampaignService
    private lateinit var campaignPlayerService: CampaignPlayerService
    private lateinit var introWebService: IntroWebService
    private lateinit var jda: JDA
    private lateinit var service: CampaignWebService

    private val guildId = 100L
    private val dmDiscordId = 1L
    private val playerDiscordId = 2L

    private fun makeCampaign() = CampaignDto(
        id = 10L,
        guildId = guildId,
        channelId = guildId,
        dmDiscordId = dmDiscordId,
        name = "Test Campaign"
    )

    @BeforeEach
    fun setup() {
        campaignService = mockk(relaxed = true)
        campaignPlayerService = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        service = CampaignWebService(campaignService, campaignPlayerService, introWebService, jda)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    // getGuildName

    @Test
    fun `getGuildName returns name when guild found`() {
        every { jda.getGuildById(guildId) } returns mockk<Guild> { every { name } returns "MyGuild" }
        assertEquals("MyGuild", service.getGuildName(guildId))
    }

    @Test
    fun `getGuildName returns null when guild not found`() {
        every { jda.getGuildById(guildId) } returns null
        assertNull(service.getGuildName(guildId))
    }

    // getMutualGuildsWithCampaigns

    @Test
    fun `getMutualGuildsWithCampaigns includes active campaign when present`() {
        val guild = GuildInfo("100", "TestGuild", null)
        every { introWebService.getMutualGuilds("token") } returns listOf(guild)
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign

        val result = service.getMutualGuildsWithCampaigns("token")

        assertEquals(1, result.size)
        assertEquals("TestGuild", result[0].name)
        assertEquals(campaign, result[0].activeCampaign)
    }

    @Test
    fun `getMutualGuildsWithCampaigns sets activeCampaign null when none exists`() {
        val guild = GuildInfo("100", "TestGuild", null)
        every { introWebService.getMutualGuilds("token") } returns listOf(guild)
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null

        val result = service.getMutualGuildsWithCampaigns("token")

        assertNull(result[0].activeCampaign)
    }

    // getCampaignDetail

    @Test
    fun `getCampaignDetail returns null when no active campaign`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertNull(service.getCampaignDetail(guildId, dmDiscordId))
    }

    @Test
    fun `getCampaignDetail returns detail with players`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign

        val dmMember = mockk<Member> { every { effectiveName } returns "DungeonMaster" }
        val playerMember = mockk<Member> { every { effectiveName } returns "HeroPlayer" }
        val jdaGuild = mockk<Guild> {
            every { getMemberById(dmDiscordId) } returns dmMember
            every { getMemberById(playerDiscordId) } returns playerMember
        }
        every { jda.getGuildById(guildId) } returns jdaGuild

        val player = CampaignPlayerDto(
            id = CampaignPlayerId(campaign.id, playerDiscordId),
            guildId = guildId,
            characterId = 42L,
            alive = true
        )
        every { campaignPlayerService.getPlayersForCampaign(campaign.id) } returns listOf(player)

        val detail = service.getCampaignDetail(guildId, dmDiscordId)

        assertNotNull(detail)
        assertEquals("DungeonMaster", detail!!.dmName)
        assertEquals(1, detail.players.size)
        assertEquals("HeroPlayer", detail.players[0].displayName)
        assertEquals(42L, detail.players[0].characterId)
        assertTrue(detail.players[0].alive)
    }

    @Test
    fun `getCampaignDetail isDm returns true for DM`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { jda.getGuildById(guildId) } returns mockk(relaxed = true)
        every { campaignPlayerService.getPlayersForCampaign(campaign.id) } returns emptyList()

        val detail = service.getCampaignDetail(guildId, dmDiscordId)

        assertTrue(detail!!.isDm(dmDiscordId))
        assertFalse(detail.isDm(playerDiscordId))
    }

    @Test
    fun `getCampaignDetail returns empty players list when none joined`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { jda.getGuildById(guildId) } returns mockk(relaxed = true)
        every { campaignPlayerService.getPlayersForCampaign(campaign.id) } returns emptyList()

        val detail = service.getCampaignDetail(guildId, dmDiscordId)

        assertTrue(detail!!.players.isEmpty())
    }

    // createCampaign

    @Test
    fun `createCampaign creates and returns campaign when none active`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        val saved = makeCampaign()
        every { campaignService.createCampaign(any()) } returns saved

        val result = service.createCampaign(guildId, dmDiscordId, "Test Campaign")

        assertNotNull(result)
        assertEquals(saved, result)
        verify {
            campaignService.createCampaign(match {
                it.guildId == guildId && it.dmDiscordId == dmDiscordId && it.name == "Test Campaign"
            })
        }
    }

    @Test
    fun `createCampaign returns null when campaign already active`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()

        val result = service.createCampaign(guildId, dmDiscordId, "Another Campaign")

        assertNull(result)
        verify(exactly = 0) { campaignService.createCampaign(any()) }
    }
}

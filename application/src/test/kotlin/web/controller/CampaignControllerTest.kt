package web.controller

import database.dto.CampaignDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.AddNoteResult
import web.service.AdhocMonster
import web.service.AnnotateRollResult
import web.service.CampaignDetail
import web.service.CampaignEventBroadcaster
import web.service.CampaignWebService
import web.service.DeleteNoteResult
import web.service.DeleteTemplateResult
import web.service.EndResult
import web.service.GuildCampaignInfo
import web.service.InitiativeRollRequest
import web.service.JoinResult
import web.service.KickResult
import web.service.LeaveResult
import web.service.MonsterTemplateView
import web.service.NarrateResult
import web.service.RollInitiativeResult
import web.service.SaveTemplateResult
import web.service.SessionEventView
import web.service.SetAliveResult
import web.service.SetCharacterResult
import java.time.LocalDateTime

class CampaignControllerTest {

    private lateinit var campaignWebService: CampaignWebService
    private lateinit var campaignEventBroadcaster: CampaignEventBroadcaster
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
        campaignEventBroadcaster = mockk(relaxed = true)
        controller = CampaignController(campaignWebService, campaignEventBroadcaster)

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

    // addNote

    @Test
    fun `addNote redirects with no flash on success`() {
        every { campaignWebService.addNote(guildId, 1L, "hi") } returns AddNoteResult.ADDED

        val view = controller.addNote(guildId, "hi", mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `addNote sets error when not a participant`() {
        every { campaignWebService.addNote(guildId, 1L, "hi") } returns AddNoteResult.NOT_PARTICIPANT

        controller.addNote(guildId, "hi", mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Only the DM and campaign players can add notes.") }
    }

    @Test
    fun `addNote sets error on empty body`() {
        every { campaignWebService.addNote(guildId, 1L, "  ") } returns AddNoteResult.EMPTY_BODY

        controller.addNote(guildId, "  ", mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Note body can't be empty.") }
    }

    @Test
    fun `addNote sets error on body too long`() {
        every { campaignWebService.addNote(guildId, 1L, any()) } returns AddNoteResult.BODY_TOO_LONG

        controller.addNote(guildId, "x".repeat(3000), mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Note is too long (max 2000 characters).") }
    }

    @Test
    fun `addNote redirects to list when user id missing`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val view = controller.addNote(guildId, "hi", mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign", view)
    }

    // deleteNote

    @Test
    fun `deleteNote redirects with no flash on success`() {
        every { campaignWebService.deleteNote(guildId, 1L, 42L) } returns DeleteNoteResult.DELETED

        val view = controller.deleteNote(guildId, 42L, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `deleteNote sets error when not allowed`() {
        every { campaignWebService.deleteNote(guildId, 1L, 42L) } returns DeleteNoteResult.NOT_ALLOWED

        controller.deleteNote(guildId, 42L, mockUser, mockRa)

        verify {
            mockRa.addFlashAttribute(
                "error",
                "You can only delete your own notes (or any note if you're the DM)."
            )
        }
    }

    @Test
    fun `deleteNote sets error when note not found`() {
        every { campaignWebService.deleteNote(guildId, 1L, 42L) } returns DeleteNoteResult.NOT_FOUND

        controller.deleteNote(guildId, 42L, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "That note doesn't exist.") }
    }

    // listEvents

    @Test
    fun `listEvents returns empty list when user id missing`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val result = controller.listEvents(guildId, null, 100, mockUser)

        assertEquals(emptyList<SessionEventView>(), result)
    }

    @Test
    fun `listEvents delegates to web service`() {
        val events = listOf(
            SessionEventView(
                id = 1L, type = "ROLL", actorDiscordId = 7L, actorName = "Dave",
                refEventId = null, payload = mapOf("total" to 14),
                createdAt = LocalDateTime.now()
            )
        )
        every { campaignWebService.listRecentEvents(guildId, null, 100) } returns events

        val result = controller.listEvents(guildId, null, 100, mockUser)

        assertEquals(events, result)
    }

    @Test
    fun `listEvents forwards since cursor`() {
        every { campaignWebService.listRecentEvents(guildId, 42L, 50) } returns emptyList()

        controller.listEvents(guildId, 42L, 50, mockUser)

        verify { campaignWebService.listRecentEvents(guildId, 42L, 50) }
    }

    // streamEvents (SSE)

    @Test
    fun `streamEvents subscribes via broadcaster for active campaign`() {
        val campaignId = 77L
        every { campaignWebService.getActiveCampaignId(guildId) } returns campaignId
        every { campaignEventBroadcaster.subscribe(campaignId) } returns
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter()

        controller.streamEvents(guildId, mockUser)

        verify { campaignEventBroadcaster.subscribe(campaignId) }
    }

    @Test
    fun `streamEvents returns a completed emitter when user id missing`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val emitter = controller.streamEvents(guildId, mockUser)

        assertNotNull(emitter)
        verify(exactly = 0) { campaignEventBroadcaster.subscribe(any()) }
    }

    @Test
    fun `streamEvents returns a completed emitter when no active campaign`() {
        every { campaignWebService.getActiveCampaignId(guildId) } returns null

        val emitter = controller.streamEvents(guildId, mockUser)

        assertNotNull(emitter)
        verify(exactly = 0) { campaignEventBroadcaster.subscribe(any()) }
    }

    // annotateRoll

    @Test
    fun `annotateRoll redirects on success`() {
        every {
            campaignWebService.annotateRoll(guildId, 1L, 42L, "HIT", null)
        } returns AnnotateRollResult.ANNOTATED

        val view = controller.annotateRoll(guildId, 42L, "HIT", null, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `annotateRoll sets error when not DM`() {
        every {
            campaignWebService.annotateRoll(guildId, 1L, 42L, "HIT", null)
        } returns AnnotateRollResult.NOT_DM

        controller.annotateRoll(guildId, 42L, "HIT", null, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Only the Dungeon Master can annotate rolls.") }
    }

    @Test
    fun `annotateRoll sets error when referenced event is not a roll`() {
        every {
            campaignWebService.annotateRoll(guildId, 1L, 42L, "HIT", null)
        } returns AnnotateRollResult.NOT_A_ROLL

        controller.annotateRoll(guildId, 42L, "HIT", null, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "You can only mark Hit/Miss on a roll.") }
    }

    @Test
    fun `annotateRoll sets error when kind is invalid`() {
        every {
            campaignWebService.annotateRoll(guildId, 1L, 42L, "FOO", null)
        } returns AnnotateRollResult.INVALID_KIND

        controller.annotateRoll(guildId, 42L, "FOO", null, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Annotation kind must be HIT or MISS.") }
    }

    @Test
    fun `annotateRoll redirects to list when user id missing`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val view = controller.annotateRoll(guildId, 42L, "HIT", null, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign", view)
    }

    // narrate

    @Test
    fun `narrate redirects on success`() {
        every {
            campaignWebService.narrate(guildId, 1L, "beat")
        } returns NarrateResult.NARRATED

        val view = controller.narrate(guildId, "beat", mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `narrate sets error when not DM`() {
        every {
            campaignWebService.narrate(guildId, 1L, "beat")
        } returns NarrateResult.NOT_DM

        controller.narrate(guildId, "beat", mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Only the Dungeon Master can narrate.") }
    }

    @Test
    fun `narrate sets error on empty body`() {
        every {
            campaignWebService.narrate(guildId, 1L, "")
        } returns NarrateResult.EMPTY_BODY

        controller.narrate(guildId, "", mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Narration can't be empty.") }
    }

    @Test
    fun `narrate redirects to list when user id missing`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val view = controller.narrate(guildId, "beat", mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign", view)
    }

    // monster templates

    @Test
    fun `listMonsterTemplates delegates to service`() {
        val templates = listOf(MonsterTemplateView(1L, "Goblin", 2, 7, 15))
        every { campaignWebService.listTemplatesForDm(1L) } returns templates

        val result = controller.listMonsterTemplates(mockUser)

        assertEquals(templates, result)
    }

    @Test
    fun `listMonsterTemplates returns empty when user id missing`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val result = controller.listMonsterTemplates(mockUser)

        assertEquals(emptyList<MonsterTemplateView>(), result)
    }

    @Test
    fun `saveMonsterTemplate redirects on success`() {
        every {
            campaignWebService.saveTemplate(1L, null, "Goblin", 2, 7, 15)
        } returns SaveTemplateResult.SAVED

        val view = controller.saveMonsterTemplate(guildId, null, "Goblin", 2, 7, 15, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `saveMonsterTemplate sets error when name blank`() {
        every {
            campaignWebService.saveTemplate(1L, null, "", 0, null, null)
        } returns SaveTemplateResult.NAME_BLANK

        controller.saveMonsterTemplate(guildId, null, "", 0, null, null, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Monster name can't be empty.") }
    }

    @Test
    fun `saveMonsterTemplate sets error when not owner`() {
        every {
            campaignWebService.saveTemplate(1L, 99L, "X", 0, null, null)
        } returns SaveTemplateResult.NOT_OWNER

        controller.saveMonsterTemplate(guildId, 99L, "X", 0, null, null, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "You can only edit your own templates.") }
    }

    @Test
    fun `deleteMonsterTemplate redirects on success`() {
        every { campaignWebService.deleteTemplate(1L, 99L) } returns DeleteTemplateResult.DELETED

        val view = controller.deleteMonsterTemplate(guildId, 99L, mockUser, mockRa)

        assertEquals("redirect:/dnd/campaign/$guildId", view)
    }

    @Test
    fun `deleteMonsterTemplate sets error when not owner`() {
        every { campaignWebService.deleteTemplate(1L, 99L) } returns DeleteTemplateResult.NOT_OWNER

        controller.deleteMonsterTemplate(guildId, 99L, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "You can only delete your own templates.") }
    }

    // rollInitiative

    @Test
    fun `rollInitiative redirects on success`() {
        every {
            campaignWebService.rollInitiative(guildId, 1L, match { it.playerDiscordIds == listOf(7L) })
        } returns RollInitiativeResult.ROLLED

        val view = controller.rollInitiative(
            guildId,
            playerDiscordIds = listOf(7L),
            templateIds = null,
            templateQtys = null,
            adhocNames = null,
            adhocMods = null,
            user = mockUser,
            ra = mockRa
        )

        assertEquals("redirect:/dnd/campaign/$guildId", view)
        verify(exactly = 0) { mockRa.addFlashAttribute(any<String>(), any()) }
    }

    @Test
    fun `rollInitiative builds adhoc monsters from parallel arrays`() {
        every {
            campaignWebService.rollInitiative(
                guildId,
                1L,
                match<InitiativeRollRequest> { req ->
                    req.adhocMonsters == listOf(
                        AdhocMonster("Bugbear", 1),
                        AdhocMonster("Kobold", 2)
                    )
                }
            )
        } returns RollInitiativeResult.ROLLED

        controller.rollInitiative(
            guildId,
            playerDiscordIds = null,
            templateIds = null,
            templateQtys = null,
            adhocNames = listOf("Bugbear", "", "Kobold"),
            adhocMods = listOf(1, 0, 2),
            user = mockUser,
            ra = mockRa
        )
    }

    @Test
    fun `rollInitiative expands templateId and templateQty into repeated template ids`() {
        every {
            campaignWebService.rollInitiative(
                guildId,
                1L,
                match<InitiativeRollRequest> { req ->
                    req.templateIds == listOf(10L, 10L, 20L)
                }
            )
        } returns RollInitiativeResult.ROLLED

        controller.rollInitiative(
            guildId,
            playerDiscordIds = null,
            templateIds = listOf(10L, 20L, 30L),
            templateQtys = listOf(2, 1, 0),
            adhocNames = null,
            adhocMods = null,
            user = mockUser,
            ra = mockRa
        )
    }

    @Test
    fun `rollInitiative sets error when not DM`() {
        every {
            campaignWebService.rollInitiative(guildId, 1L, any())
        } returns RollInitiativeResult.NOT_DM

        controller.rollInitiative(guildId, listOf(7L), null, null, null, null, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Only the Dungeon Master can roll initiative here.") }
    }

    @Test
    fun `rollInitiative sets error when roster empty`() {
        every {
            campaignWebService.rollInitiative(guildId, 1L, any())
        } returns RollInitiativeResult.EMPTY_ROSTER

        controller.rollInitiative(guildId, null, null, null, null, null, mockUser, mockRa)

        verify { mockRa.addFlashAttribute("error", "Pick at least one player or monster before rolling.") }
    }

    @Test
    fun `rollInitiative sets error when template missing`() {
        every {
            campaignWebService.rollInitiative(guildId, 1L, any())
        } returns RollInitiativeResult.TEMPLATE_NOT_FOUND

        controller.rollInitiative(guildId, null, listOf(77L), listOf(1), null, null, mockUser, mockRa)

        verify {
            mockRa.addFlashAttribute("error", "One of the selected monster templates couldn't be found.")
        }
    }
}

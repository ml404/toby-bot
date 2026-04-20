package web.service

import database.dto.CampaignDto
import database.dto.CampaignEventDto
import database.dto.CampaignPlayerDto
import database.dto.CampaignPlayerId
import database.dto.MonsterTemplateDto
import database.dto.SessionNoteDto
import database.dto.UserDto
import database.service.CampaignEventService
import database.service.CampaignPlayerService
import database.service.CampaignService
import database.service.CharacterSheetService
import database.service.MonsterTemplateService
import database.service.SessionNoteService
import database.service.UserService
import java.time.LocalDateTime
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
    private lateinit var userService: UserService
    private lateinit var characterSheetService: CharacterSheetService
    private lateinit var sessionNoteService: SessionNoteService
    private lateinit var campaignEventService: CampaignEventService
    private lateinit var monsterTemplateService: MonsterTemplateService
    private lateinit var initiativeStore: InitiativeStore
    private lateinit var sessionLog: SessionLogPublisher
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
        userService = mockk(relaxed = true)
        characterSheetService = mockk(relaxed = true)
        sessionNoteService = mockk(relaxed = true)
        campaignEventService = mockk(relaxed = true)
        monsterTemplateService = mockk(relaxed = true)
        initiativeStore = mockk(relaxed = true)
        sessionLog = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        service = CampaignWebService(
            campaignService,
            campaignPlayerService,
            introWebService,
            userService,
            characterSheetService,
            sessionNoteService,
            campaignEventService,
            monsterTemplateService,
            initiativeStore,
            sessionLog,
            jda
        )
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
        every { characterSheetService.getSheet(42L) } returns null
        every { userService.getUserById(dmDiscordId, guildId) } returns UserDto(dmDiscordId, guildId)

        val detail = service.getCampaignDetail(guildId, dmDiscordId)

        assertNotNull(detail)
        assertEquals("DungeonMaster", detail!!.dmName)
        assertEquals(1, detail.players.size)
        assertEquals("HeroPlayer", detail.players[0].displayName)
        assertEquals(42L, detail.players[0].characterId)
        assertTrue(detail.players[0].alive)
        assertFalse(detail.isCurrentUserPlayer)
    }

    @Test
    fun `getCampaignDetail hydrates cached character summary when available`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign

        val jdaGuild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns jdaGuild

        val player = CampaignPlayerDto(
            id = CampaignPlayerId(campaign.id, playerDiscordId),
            guildId = guildId,
            characterId = 42L,
            alive = true
        )
        every { campaignPlayerService.getPlayersForCampaign(campaign.id) } returns listOf(player)
        every { characterSheetService.getSheet(42L) } returns """
            {
              "name": "Eeyore",
              "race": { "fullName": "Hill Dwarf", "baseName": "Dwarf" },
              "classes": [
                { "level": 3, "definition": { "name": "Fighter" } },
                { "level": 2, "definition": { "name": "Rogue" }, "subclassDefinition": { "name": "Assassin" } }
              ]
            }
        """.trimIndent()
        every { userService.getUserById(dmDiscordId, guildId) } returns null

        val detail = service.getCampaignDetail(guildId, dmDiscordId)!!
        val info = detail.players.single()

        assertEquals("Eeyore", info.characterName)
        assertEquals("Hill Dwarf", info.characterRace)
        assertEquals("Fighter, Rogue (Assassin)", info.characterClasses)
        assertEquals(5, info.characterLevel)
    }

    @Test
    fun `getCampaignDetail flags current user as player when present`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { jda.getGuildById(guildId) } returns mockk(relaxed = true)
        val player = CampaignPlayerDto(
            id = CampaignPlayerId(campaign.id, playerDiscordId),
            guildId = guildId,
            characterId = null,
            alive = true
        )
        every { campaignPlayerService.getPlayersForCampaign(campaign.id) } returns listOf(player)
        every { userService.getUserById(playerDiscordId, guildId) } returns UserDto(playerDiscordId, guildId).apply {
            dndBeyondCharacterId = 77L
        }

        val detail = service.getCampaignDetail(guildId, playerDiscordId)!!

        assertTrue(detail.isCurrentUserPlayer)
        assertEquals(77L, detail.currentUserCharacterId)
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

    // joinCampaign

    @Test
    fun `joinCampaign returns NO_ACTIVE_CAMPAIGN when none exists`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(JoinResult.NO_ACTIVE_CAMPAIGN, service.joinCampaign(guildId, playerDiscordId))
    }

    @Test
    fun `joinCampaign returns IS_DM when caller is DM`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        assertEquals(JoinResult.IS_DM, service.joinCampaign(guildId, dmDiscordId))
    }

    @Test
    fun `joinCampaign returns ALREADY_JOINED when player exists`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(CampaignPlayerId(campaign.id, playerDiscordId)) } returns
            CampaignPlayerDto(id = CampaignPlayerId(campaign.id, playerDiscordId), guildId = guildId)

        assertEquals(JoinResult.ALREADY_JOINED, service.joinCampaign(guildId, playerDiscordId))
        verify(exactly = 0) { campaignPlayerService.addPlayer(any()) }
    }

    @Test
    fun `joinCampaign adds player with user's linked character when available`() {
        val campaign = makeCampaign()
        val playerId = CampaignPlayerId(campaign.id, playerDiscordId)
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(playerId) } returns null
        every { userService.getUserById(playerDiscordId, guildId) } returns
            UserDto(playerDiscordId, guildId).apply { dndBeyondCharacterId = 55L }

        assertEquals(JoinResult.JOINED, service.joinCampaign(guildId, playerDiscordId))
        verify {
            campaignPlayerService.addPlayer(match {
                it.id == playerId && it.characterId == 55L && it.guildId == guildId
            })
        }
    }

    @Test
    fun `joinCampaign adds player with null character when user has no link`() {
        val campaign = makeCampaign()
        val playerId = CampaignPlayerId(campaign.id, playerDiscordId)
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(playerId) } returns null
        every { userService.getUserById(playerDiscordId, guildId) } returns null

        assertEquals(JoinResult.JOINED, service.joinCampaign(guildId, playerDiscordId))
        verify { campaignPlayerService.addPlayer(match { it.characterId == null }) }
    }

    // leaveCampaign

    @Test
    fun `leaveCampaign returns NO_ACTIVE_CAMPAIGN when none exists`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(LeaveResult.NO_ACTIVE_CAMPAIGN, service.leaveCampaign(guildId, playerDiscordId))
    }

    @Test
    fun `leaveCampaign returns NOT_A_PLAYER when user not in campaign`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(CampaignPlayerId(campaign.id, playerDiscordId)) } returns null

        assertEquals(LeaveResult.NOT_A_PLAYER, service.leaveCampaign(guildId, playerDiscordId))
        verify(exactly = 0) { campaignPlayerService.removePlayer(any()) }
    }

    @Test
    fun `leaveCampaign removes player and returns LEFT`() {
        val campaign = makeCampaign()
        val playerId = CampaignPlayerId(campaign.id, playerDiscordId)
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(playerId) } returns
            CampaignPlayerDto(id = playerId, guildId = guildId)

        assertEquals(LeaveResult.LEFT, service.leaveCampaign(guildId, playerDiscordId))
        verify { campaignPlayerService.removePlayer(playerId) }
    }

    // setLinkedCharacter

    @Test
    fun `setLinkedCharacter clears link when input is blank`() {
        val user = UserDto(playerDiscordId, guildId).apply { dndBeyondCharacterId = 123L }
        every { userService.getUserById(playerDiscordId, guildId) } returns user

        assertEquals(SetCharacterResult.CLEARED, service.setLinkedCharacter(guildId, playerDiscordId, "   "))
        assertNull(user.dndBeyondCharacterId)
        verify { userService.updateUser(user) }
    }

    @Test
    fun `setLinkedCharacter returns INVALID for non-numeric input`() {
        val user = UserDto(playerDiscordId, guildId)
        every { userService.getUserById(playerDiscordId, guildId) } returns user

        assertEquals(SetCharacterResult.INVALID, service.setLinkedCharacter(guildId, playerDiscordId, "not-a-url"))
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `setLinkedCharacter extracts id from URL and updates user`() {
        val user = UserDto(playerDiscordId, guildId)
        every { userService.getUserById(playerDiscordId, guildId) } returns user

        assertEquals(
            SetCharacterResult.UPDATED,
            service.setLinkedCharacter(guildId, playerDiscordId, "https://www.dndbeyond.com/characters/48690485")
        )
        assertEquals(48690485L, user.dndBeyondCharacterId)
        verify { userService.updateUser(user) }
    }

    @Test
    fun `setLinkedCharacter creates user when none exists`() {
        every { userService.getUserById(playerDiscordId, guildId) } returns null
        val created = UserDto(playerDiscordId, guildId)
        every { userService.createNewUser(any()) } returns created

        assertEquals(
            SetCharacterResult.UPDATED,
            service.setLinkedCharacter(guildId, playerDiscordId, "12345")
        )
        verify { userService.createNewUser(any()) }
        verify { userService.updateUser(match { it.dndBeyondCharacterId == 12345L }) }
    }

    // endCampaign

    @Test
    fun `endCampaign returns NO_ACTIVE_CAMPAIGN when none exists`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(EndResult.NO_ACTIVE_CAMPAIGN, service.endCampaign(guildId, dmDiscordId))
        verify(exactly = 0) { campaignService.deactivateCampaignForGuild(any()) }
    }

    @Test
    fun `endCampaign returns NOT_DM when requester is not the DM`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        assertEquals(EndResult.NOT_DM, service.endCampaign(guildId, playerDiscordId))
        verify(exactly = 0) { campaignService.deactivateCampaignForGuild(any()) }
    }

    @Test
    fun `endCampaign deactivates and returns ENDED for DM`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        assertEquals(EndResult.ENDED, service.endCampaign(guildId, dmDiscordId))
        verify { campaignService.deactivateCampaignForGuild(guildId) }
    }

    // kickPlayer

    @Test
    fun `kickPlayer returns NO_ACTIVE_CAMPAIGN when none exists`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(KickResult.NO_ACTIVE_CAMPAIGN, service.kickPlayer(guildId, dmDiscordId, playerDiscordId))
    }

    @Test
    fun `kickPlayer returns NOT_DM when requester is not the DM`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        assertEquals(KickResult.NOT_DM, service.kickPlayer(guildId, playerDiscordId, playerDiscordId))
        verify(exactly = 0) { campaignPlayerService.removePlayer(any()) }
    }

    @Test
    fun `kickPlayer refuses to kick the DM`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        assertEquals(KickResult.CANNOT_KICK_DM, service.kickPlayer(guildId, dmDiscordId, dmDiscordId))
        verify(exactly = 0) { campaignPlayerService.removePlayer(any()) }
    }

    @Test
    fun `kickPlayer returns NOT_A_PLAYER when target is not in campaign`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(CampaignPlayerId(campaign.id, playerDiscordId)) } returns null

        assertEquals(KickResult.NOT_A_PLAYER, service.kickPlayer(guildId, dmDiscordId, playerDiscordId))
        verify(exactly = 0) { campaignPlayerService.removePlayer(any()) }
    }

    @Test
    fun `kickPlayer removes the player and returns KICKED`() {
        val campaign = makeCampaign()
        val playerId = CampaignPlayerId(campaign.id, playerDiscordId)
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(playerId) } returns
            CampaignPlayerDto(id = playerId, guildId = guildId)

        assertEquals(KickResult.KICKED, service.kickPlayer(guildId, dmDiscordId, playerDiscordId))
        verify { campaignPlayerService.removePlayer(playerId) }
    }

    // setPlayerAlive

    @Test
    fun `setPlayerAlive returns NO_ACTIVE_CAMPAIGN when none exists`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(
            SetAliveResult.NO_ACTIVE_CAMPAIGN,
            service.setPlayerAlive(guildId, dmDiscordId, playerDiscordId, false)
        )
    }

    @Test
    fun `setPlayerAlive returns NOT_DM when requester is not the DM`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        assertEquals(
            SetAliveResult.NOT_DM,
            service.setPlayerAlive(guildId, playerDiscordId, playerDiscordId, false)
        )
        verify(exactly = 0) { campaignPlayerService.updatePlayer(any()) }
    }

    @Test
    fun `setPlayerAlive returns NOT_A_PLAYER when target is not in campaign`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(CampaignPlayerId(campaign.id, playerDiscordId)) } returns null

        assertEquals(
            SetAliveResult.NOT_A_PLAYER,
            service.setPlayerAlive(guildId, dmDiscordId, playerDiscordId, false)
        )
        verify(exactly = 0) { campaignPlayerService.updatePlayer(any()) }
    }

    @Test
    fun `setPlayerAlive toggles alive and returns UPDATED`() {
        val campaign = makeCampaign()
        val playerId = CampaignPlayerId(campaign.id, playerDiscordId)
        val existing = CampaignPlayerDto(id = playerId, guildId = guildId, alive = true)
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(playerId) } returns existing

        assertEquals(
            SetAliveResult.UPDATED,
            service.setPlayerAlive(guildId, dmDiscordId, playerDiscordId, false)
        )
        assertFalse(existing.alive)
        verify { campaignPlayerService.updatePlayer(existing) }
    }

    // addNote

    @Test
    fun `addNote rejects empty body`() {
        assertEquals(AddNoteResult.EMPTY_BODY, service.addNote(guildId, dmDiscordId, "   "))
        verify(exactly = 0) { sessionNoteService.createNote(any()) }
    }

    @Test
    fun `addNote rejects body longer than max`() {
        val body = "x".repeat(CampaignWebService.MAX_NOTE_BODY_LENGTH + 1)
        assertEquals(AddNoteResult.BODY_TOO_LONG, service.addNote(guildId, dmDiscordId, body))
        verify(exactly = 0) { sessionNoteService.createNote(any()) }
    }

    @Test
    fun `addNote returns NO_ACTIVE_CAMPAIGN when none exists`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(AddNoteResult.NO_ACTIVE_CAMPAIGN, service.addNote(guildId, dmDiscordId, "hello"))
    }

    @Test
    fun `addNote rejects non-participant`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(CampaignPlayerId(campaign.id, 99L)) } returns null

        assertEquals(AddNoteResult.NOT_PARTICIPANT, service.addNote(guildId, 99L, "hi"))
        verify(exactly = 0) { sessionNoteService.createNote(any()) }
    }

    @Test
    fun `addNote allows DM and persists trimmed body`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign

        assertEquals(AddNoteResult.ADDED, service.addNote(guildId, dmDiscordId, "  party reached the tavern  "))
        verify {
            sessionNoteService.createNote(match {
                it.campaignId == campaign.id &&
                    it.authorDiscordId == dmDiscordId &&
                    it.body == "party reached the tavern"
            })
        }
    }

    @Test
    fun `addNote allows existing player`() {
        val campaign = makeCampaign()
        val playerId = CampaignPlayerId(campaign.id, playerDiscordId)
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(playerId) } returns
            CampaignPlayerDto(id = playerId, guildId = guildId)

        assertEquals(AddNoteResult.ADDED, service.addNote(guildId, playerDiscordId, "loot!"))
        verify { sessionNoteService.createNote(any()) }
    }

    // deleteNote

    @Test
    fun `deleteNote returns NO_ACTIVE_CAMPAIGN when none exists`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(DeleteNoteResult.NO_ACTIVE_CAMPAIGN, service.deleteNote(guildId, dmDiscordId, 1L))
    }

    @Test
    fun `deleteNote returns NOT_FOUND when note missing`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { sessionNoteService.getNoteById(42L) } returns null

        assertEquals(DeleteNoteResult.NOT_FOUND, service.deleteNote(guildId, dmDiscordId, 42L))
    }

    @Test
    fun `deleteNote returns NOT_FOUND when note belongs to another campaign`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { sessionNoteService.getNoteById(42L) } returns SessionNoteDto(
            id = 42L,
            campaignId = campaign.id + 1,
            authorDiscordId = dmDiscordId,
            body = "stale"
        )

        assertEquals(DeleteNoteResult.NOT_FOUND, service.deleteNote(guildId, dmDiscordId, 42L))
        verify(exactly = 0) { sessionNoteService.deleteNoteById(any()) }
    }

    @Test
    fun `deleteNote rejects non-author non-DM`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { sessionNoteService.getNoteById(42L) } returns SessionNoteDto(
            id = 42L,
            campaignId = campaign.id,
            authorDiscordId = playerDiscordId,
            body = "by player"
        )

        assertEquals(DeleteNoteResult.NOT_ALLOWED, service.deleteNote(guildId, 999L, 42L))
        verify(exactly = 0) { sessionNoteService.deleteNoteById(any()) }
    }

    @Test
    fun `deleteNote allows author`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { sessionNoteService.getNoteById(42L) } returns SessionNoteDto(
            id = 42L,
            campaignId = campaign.id,
            authorDiscordId = playerDiscordId,
            body = "mine"
        )

        assertEquals(DeleteNoteResult.DELETED, service.deleteNote(guildId, playerDiscordId, 42L))
        verify { sessionNoteService.deleteNoteById(42L) }
    }

    @Test
    fun `deleteNote allows DM to delete any note`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { sessionNoteService.getNoteById(42L) } returns SessionNoteDto(
            id = 42L,
            campaignId = campaign.id,
            authorDiscordId = playerDiscordId,
            body = "player's note"
        )

        assertEquals(DeleteNoteResult.DELETED, service.deleteNote(guildId, dmDiscordId, 42L))
        verify { sessionNoteService.deleteNoteById(42L) }
    }

    // getCampaignDetail notes hydration

    @Test
    fun `getCampaignDetail hydrates notes with author names and delete permissions`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign

        val dmMember = mockk<Member> { every { effectiveName } returns "DM" }
        val playerMember = mockk<Member> { every { effectiveName } returns "Player" }
        val jdaGuild = mockk<Guild> {
            every { getMemberById(dmDiscordId) } returns dmMember
            every { getMemberById(playerDiscordId) } returns playerMember
        }
        every { jda.getGuildById(guildId) } returns jdaGuild
        every { campaignPlayerService.getPlayersForCampaign(campaign.id) } returns emptyList()

        val now = LocalDateTime.now()
        every { sessionNoteService.getNotesForCampaign(campaign.id) } returns listOf(
            SessionNoteDto(id = 1, campaignId = campaign.id, authorDiscordId = dmDiscordId, body = "dm", createdAt = now),
            SessionNoteDto(id = 2, campaignId = campaign.id, authorDiscordId = playerDiscordId, body = "pl", createdAt = now)
        )

        val asDm = service.getCampaignDetail(guildId, dmDiscordId)!!
        assertEquals(2, asDm.notes.size)
        assertTrue(asDm.notes.all { it.canDelete }, "DM should be able to delete every note")
        assertEquals("DM", asDm.notes[0].authorName)
        assertEquals("Player", asDm.notes[1].authorName)

        val asPlayer = service.getCampaignDetail(guildId, playerDiscordId)!!
        assertFalse(asPlayer.notes[0].canDelete, "Player can't delete DM's note")
        assertTrue(asPlayer.notes[1].canDelete, "Player can delete their own note")
    }

    // listRecentEvents + CampaignDetail.recentEvents

    @Test
    fun `listRecentEvents returns empty when no active campaign`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertTrue(service.listRecentEvents(guildId, null, 50).isEmpty())
    }

    @Test
    fun `listRecentEvents delegates to listRecent when no since cursor`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        val now = LocalDateTime.now()
        every { campaignEventService.listRecent(campaign.id, 50) } returns listOf(
            CampaignEventDto(
                id = 1, campaignId = campaign.id, eventType = "ROLL",
                actorDiscordId = 7L, actorName = "Dave",
                payload = """{"sides":20,"count":1,"modifier":0,"total":17,"rawTotal":17}""",
                createdAt = now
            )
        )

        val out = service.listRecentEvents(guildId, null, 50)

        assertEquals(1, out.size)
        assertEquals(1L, out[0].id)
        assertEquals("ROLL", out[0].type)
        assertEquals("Dave", out[0].actorName)
        assertEquals(20, out[0].payload["sides"])
        assertEquals(17, out[0].payload["total"])
    }

    @Test
    fun `listRecentEvents delegates to listSince when cursor provided`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignEventService.listSince(campaign.id, 42L, 30) } returns emptyList()

        service.listRecentEvents(guildId, 42L, 30)

        verify { campaignEventService.listSince(campaign.id, 42L, 30) }
        verify(exactly = 0) { campaignEventService.listRecent(any(), any()) }
    }

    @Test
    fun `listRecentEvents tolerates malformed payload json`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignEventService.listRecent(campaign.id, any()) } returns listOf(
            CampaignEventDto(
                id = 1, campaignId = campaign.id, eventType = "ROLL",
                payload = "not-valid-json",
                createdAt = LocalDateTime.now()
            )
        )

        val out = service.listRecentEvents(guildId, null, 100)
        assertEquals(1, out.size)
        assertTrue(out[0].payload.isEmpty(), "malformed payload falls back to empty map")
    }

    @Test
    fun `getCampaignDetail populates recentEvents`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { jda.getGuildById(guildId) } returns mockk(relaxed = true)
        every { campaignPlayerService.getPlayersForCampaign(campaign.id) } returns emptyList()
        every { sessionNoteService.getNotesForCampaign(campaign.id) } returns emptyList()
        every { campaignEventService.listRecent(campaign.id, 100) } returns listOf(
            CampaignEventDto(
                id = 9, campaignId = campaign.id, eventType = "ROLL",
                payload = """{"total":12}""", createdAt = LocalDateTime.now()
            )
        )

        val detail = service.getCampaignDetail(guildId, dmDiscordId)!!
        assertEquals(1, detail.recentEvents.size)
        assertEquals(9L, detail.recentEvents[0].id)
    }

    // annotateRoll

    @Test
    fun `annotateRoll rejects unknown kind`() {
        assertEquals(
            AnnotateRollResult.INVALID_KIND,
            service.annotateRoll(guildId, dmDiscordId, 1L, "BOGUS", null)
        )
    }

    @Test
    fun `annotateRoll returns NO_ACTIVE_CAMPAIGN when none exists`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(
            AnnotateRollResult.NO_ACTIVE_CAMPAIGN,
            service.annotateRoll(guildId, dmDiscordId, 1L, "HIT", null)
        )
    }

    @Test
    fun `annotateRoll returns NOT_DM when requester is not the DM`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        assertEquals(
            AnnotateRollResult.NOT_DM,
            service.annotateRoll(guildId, playerDiscordId, 1L, "HIT", null)
        )
    }

    @Test
    fun `annotateRoll returns NOT_FOUND when event id doesn't exist`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignEventService.getById(99L) } returns null
        assertEquals(
            AnnotateRollResult.NOT_FOUND,
            service.annotateRoll(guildId, dmDiscordId, 99L, "HIT", null)
        )
    }

    @Test
    fun `annotateRoll returns NOT_FOUND when event belongs to a different campaign`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignEventService.getById(99L) } returns CampaignEventDto(
            id = 99L, campaignId = 999L, eventType = "ROLL", payload = "{}"
        )
        assertEquals(
            AnnotateRollResult.NOT_FOUND,
            service.annotateRoll(guildId, dmDiscordId, 99L, "HIT", null)
        )
    }

    @Test
    fun `annotateRoll returns NOT_A_ROLL when referenced event isn't a roll`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignEventService.getById(99L) } returns CampaignEventDto(
            id = 99L, campaignId = campaign.id, eventType = "PLAYER_JOINED", payload = "{}"
        )
        assertEquals(
            AnnotateRollResult.NOT_A_ROLL,
            service.annotateRoll(guildId, dmDiscordId, 99L, "HIT", null)
        )
    }

    @Test
    fun `annotateRoll publishes HIT referencing the roll`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignEventService.getById(99L) } returns CampaignEventDto(
            id = 99L, campaignId = campaign.id, eventType = "ROLL", payload = "{}"
        )

        assertEquals(
            AnnotateRollResult.ANNOTATED,
            service.annotateRoll(guildId, dmDiscordId, 99L, "hit", "goblin")
        )
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.HIT,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { it["target"] == "goblin" },
                refEventId = 99L
            )
        }
    }

    @Test
    fun `annotateRoll publishes MISS without target when target is blank`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignEventService.getById(99L) } returns CampaignEventDto(
            id = 99L, campaignId = campaign.id, eventType = "ROLL", payload = "{}"
        )

        service.annotateRoll(guildId, dmDiscordId, 99L, "MISS", "  ")

        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.MISS,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { it.isEmpty() },
                refEventId = 99L
            )
        }
    }

    // narrate

    @Test
    fun `narrate rejects empty body`() {
        assertEquals(NarrateResult.EMPTY_BODY, service.narrate(guildId, dmDiscordId, "   "))
    }

    @Test
    fun `narrate rejects body over the length cap`() {
        val long = "x".repeat(CampaignWebService.MAX_NARRATE_BODY_LENGTH + 1)
        assertEquals(NarrateResult.BODY_TOO_LONG, service.narrate(guildId, dmDiscordId, long))
    }

    @Test
    fun `narrate returns NO_ACTIVE_CAMPAIGN when none exists`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(
            NarrateResult.NO_ACTIVE_CAMPAIGN,
            service.narrate(guildId, dmDiscordId, "Something happens.")
        )
    }

    @Test
    fun `narrate returns NOT_DM when requester is not the DM`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        assertEquals(
            NarrateResult.NOT_DM,
            service.narrate(guildId, playerDiscordId, "Something happens.")
        )
    }

    @Test
    fun `narrate publishes DM_NOTE for DM`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()

        assertEquals(
            NarrateResult.NARRATED,
            service.narrate(guildId, dmDiscordId, "  A dragon lands.  ")
        )
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.DM_NOTE,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { it["body"] == "A dragon lands." },
                refEventId = null
            )
        }
    }

    // saveTemplate / deleteTemplate

    @Test
    fun `saveTemplate rejects blank name`() {
        assertEquals(
            SaveTemplateResult.NAME_BLANK,
            service.saveTemplate(dmDiscordId, id = null, name = "   ", initiativeModifier = 0, maxHp = null, ac = null)
        )
    }

    @Test
    fun `saveTemplate rejects name past length cap`() {
        val long = "x".repeat(CampaignWebService.MAX_TEMPLATE_NAME_LENGTH + 1)
        assertEquals(
            SaveTemplateResult.NAME_TOO_LONG,
            service.saveTemplate(dmDiscordId, id = null, name = long, initiativeModifier = 0, maxHp = null, ac = null)
        )
    }

    @Test
    fun `saveTemplate creates when id is null`() {
        assertEquals(
            SaveTemplateResult.SAVED,
            service.saveTemplate(dmDiscordId, id = null, name = "Goblin", initiativeModifier = 2, maxHp = 7, ac = 15)
        )
        verify {
            monsterTemplateService.save(match {
                it.id == 0L && it.dmDiscordId == dmDiscordId && it.name == "Goblin" &&
                    it.initiativeModifier == 2 && it.maxHp == 7 && it.ac == 15
            })
        }
    }

    @Test
    fun `saveTemplate returns NOT_FOUND when id doesn't exist`() {
        every { monsterTemplateService.getById(99L) } returns null
        assertEquals(
            SaveTemplateResult.NOT_FOUND,
            service.saveTemplate(dmDiscordId, id = 99L, name = "X", initiativeModifier = 0, maxHp = null, ac = null)
        )
    }

    @Test
    fun `saveTemplate returns NOT_OWNER when template belongs to another DM`() {
        every { monsterTemplateService.getById(99L) } returns MonsterTemplateDto(
            id = 99L, dmDiscordId = 999L, name = "Stolen"
        )
        assertEquals(
            SaveTemplateResult.NOT_OWNER,
            service.saveTemplate(dmDiscordId, id = 99L, name = "X", initiativeModifier = 0, maxHp = null, ac = null)
        )
    }

    @Test
    fun `deleteTemplate returns NOT_FOUND when missing`() {
        every { monsterTemplateService.getById(99L) } returns null
        assertEquals(DeleteTemplateResult.NOT_FOUND, service.deleteTemplate(dmDiscordId, 99L))
    }

    @Test
    fun `deleteTemplate returns NOT_OWNER when template belongs to another DM`() {
        every { monsterTemplateService.getById(99L) } returns MonsterTemplateDto(
            id = 99L, dmDiscordId = 999L, name = "Stolen"
        )
        assertEquals(DeleteTemplateResult.NOT_OWNER, service.deleteTemplate(dmDiscordId, 99L))
        verify(exactly = 0) { monsterTemplateService.deleteById(any()) }
    }

    @Test
    fun `deleteTemplate removes when caller owns it`() {
        every { monsterTemplateService.getById(99L) } returns MonsterTemplateDto(
            id = 99L, dmDiscordId = dmDiscordId, name = "Mine"
        )
        assertEquals(DeleteTemplateResult.DELETED, service.deleteTemplate(dmDiscordId, 99L))
        verify { monsterTemplateService.deleteById(99L) }
    }

    // rollInitiative

    @Test
    fun `rollInitiative returns NO_ACTIVE_CAMPAIGN when none exists`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(
            RollInitiativeResult.NO_ACTIVE_CAMPAIGN,
            service.rollInitiative(guildId, dmDiscordId, InitiativeRollRequest(playerDiscordIds = listOf(1L)))
        )
    }

    @Test
    fun `rollInitiative returns NOT_DM when caller is not the DM`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        assertEquals(
            RollInitiativeResult.NOT_DM,
            service.rollInitiative(guildId, playerDiscordId, InitiativeRollRequest(playerDiscordIds = listOf(1L)))
        )
    }

    @Test
    fun `rollInitiative returns EMPTY_ROSTER when nobody is picked`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        assertEquals(
            RollInitiativeResult.EMPTY_ROSTER,
            service.rollInitiative(guildId, dmDiscordId, InitiativeRollRequest())
        )
    }

    @Test
    fun `rollInitiative returns TEMPLATE_NOT_FOUND for missing template`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { monsterTemplateService.getById(77L) } returns null
        assertEquals(
            RollInitiativeResult.TEMPLATE_NOT_FOUND,
            service.rollInitiative(guildId, dmDiscordId, InitiativeRollRequest(templateIds = listOf(77L)))
        )
    }

    @Test
    fun `rollInitiative returns TEMPLATE_NOT_FOUND when template belongs to another DM`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = 999L, name = "Someone Else's Wolf"
        )
        assertEquals(
            RollInitiativeResult.TEMPLATE_NOT_FOUND,
            service.rollInitiative(guildId, dmDiscordId, InitiativeRollRequest(templateIds = listOf(77L)))
        )
    }

    @Test
    fun `rollInitiative seeds store and publishes INITIATIVE_ROLLED`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { userService.getUserById(playerDiscordId, guildId) } returns UserDto(playerDiscordId, guildId).apply {
            initiativeModifier = 3
        }
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin", initiativeModifier = 2
        )

        val result = service.rollInitiative(
            guildId,
            dmDiscordId,
            InitiativeRollRequest(
                playerDiscordIds = listOf(playerDiscordId),
                templateIds = listOf(77L),
                adhocMonsters = listOf(AdhocMonster("Bugbear", 1))
            )
        )

        assertEquals(RollInitiativeResult.ROLLED, result)
        verify { initiativeStore.seed(eq(guildId), match { it.size == 3 }) }
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.INITIATIVE_ROLLED,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { (it["entries"] as? List<*>)?.size == 3 },
                refEventId = null
            )
        }
    }
}

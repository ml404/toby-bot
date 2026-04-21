package web.service

import database.dto.CampaignDto
import database.dto.CampaignEventDto
import database.dto.CampaignPlayerDto
import database.dto.CampaignPlayerId
import database.dto.MonsterAttackDto
import database.dto.MonsterTemplateDto
import database.dto.SessionNoteDto
import database.dto.UserDto
import database.service.CampaignEventService
import database.service.CampaignPlayerService
import database.service.CampaignService
import database.service.CharacterSheetService
import database.service.MonsterAttackService
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
    private lateinit var monsterAttackService: MonsterAttackService
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
        monsterAttackService = mockk(relaxed = true)
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
            monsterAttackService,
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
            service.saveTemplate(dmDiscordId, id = null, name = "   ", initiativeModifier = 0, hpExpression = null, ac = null)
        )
    }

    @Test
    fun `saveTemplate rejects name past length cap`() {
        val long = "x".repeat(CampaignWebService.MAX_TEMPLATE_NAME_LENGTH + 1)
        assertEquals(
            SaveTemplateResult.NAME_TOO_LONG,
            service.saveTemplate(dmDiscordId, id = null, name = long, initiativeModifier = 0, hpExpression = null, ac = null)
        )
    }

    @Test
    fun `saveTemplate creates when id is null`() {
        assertEquals(
            SaveTemplateResult.SAVED,
            service.saveTemplate(dmDiscordId, id = null, name = "Goblin", initiativeModifier = 2, hpExpression = "7", ac = 15)
        )
        verify {
            monsterTemplateService.save(match {
                it.id == 0L && it.dmDiscordId == dmDiscordId && it.name == "Goblin" &&
                    it.initiativeModifier == 2 && it.hpExpression == "7" && it.ac == 15
            })
        }
    }

    @Test
    fun `saveTemplate accepts dice expression for HP`() {
        assertEquals(
            SaveTemplateResult.SAVED,
            service.saveTemplate(dmDiscordId, id = null, name = "Ogre", initiativeModifier = 0, hpExpression = "3d20+30", ac = 11)
        )
        verify {
            monsterTemplateService.save(match { it.hpExpression == "3d20+30" })
        }
    }

    @Test
    fun `saveTemplate rejects unparseable HP expression`() {
        assertEquals(
            SaveTemplateResult.INVALID_HP,
            service.saveTemplate(dmDiscordId, id = null, name = "Bad", initiativeModifier = 0, hpExpression = "garbage", ac = null)
        )
    }

    @Test
    fun `saveTemplate rejects HP dice expression past caps`() {
        assertEquals(
            SaveTemplateResult.INVALID_HP,
            service.saveTemplate(dmDiscordId, id = null, name = "Huge", initiativeModifier = 0, hpExpression = "99d20+30", ac = null)
        )
    }

    @Test
    fun `saveTemplate returns NOT_FOUND when id doesn't exist`() {
        every { monsterTemplateService.getById(99L) } returns null
        assertEquals(
            SaveTemplateResult.NOT_FOUND,
            service.saveTemplate(dmDiscordId, id = 99L, name = "X", initiativeModifier = 0, hpExpression = null, ac = null)
        )
    }

    @Test
    fun `saveTemplate returns NOT_OWNER when template belongs to another DM`() {
        every { monsterTemplateService.getById(99L) } returns MonsterTemplateDto(
            id = 99L, dmDiscordId = 999L, name = "Stolen"
        )
        assertEquals(
            SaveTemplateResult.NOT_OWNER,
            service.saveTemplate(dmDiscordId, id = 99L, name = "X", initiativeModifier = 0, hpExpression = null, ac = null)
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

    @Test
    fun `rollInitiative suffixes duplicate names with hash-index`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin", initiativeModifier = 2
        )

        service.rollInitiative(
            guildId,
            dmDiscordId,
            InitiativeRollRequest(
                templateIds = listOf(77L, 77L),
                adhocMonsters = listOf(
                    AdhocMonster("Goblin", 2),
                    AdhocMonster("Kobold", 1)
                )
            )
        )

        verify {
            initiativeStore.seed(
                eq(guildId),
                match { seeded ->
                    val names = seeded.map { it.name }.sorted()
                    names == listOf("Goblin #1", "Goblin #2", "Goblin #3", "Kobold")
                }
            )
        }
    }

    @Test
    fun `rollInitiative leaves unique names untouched`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin", initiativeModifier = 2
        )

        service.rollInitiative(
            guildId,
            dmDiscordId,
            InitiativeRollRequest(
                templateIds = listOf(77L),
                adhocMonsters = listOf(AdhocMonster("Bugbear", 1))
            )
        )

        verify {
            initiativeStore.seed(
                eq(guildId),
                match { seeded ->
                    seeded.map { it.name }.toSet() == setOf("Goblin", "Bugbear")
                }
            )
        }
    }

    @Test
    fun `rollInitiative uses literal HP from template hpExpression`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin",
            initiativeModifier = 2, hpExpression = "45"
        )

        service.rollInitiative(
            guildId,
            dmDiscordId,
            InitiativeRollRequest(templateIds = listOf(77L))
        )

        verify {
            initiativeStore.seed(
                eq(guildId),
                match { seeded ->
                    val goblin = seeded.single()
                    goblin.maxHp == 45 && goblin.currentHp == 45
                }
            )
        }
    }

    @Test
    fun `rollInitiative rolls HP independently per instance from dice expression`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Ogre",
            initiativeModifier = 0, hpExpression = "3d20+30"
        )

        service.rollInitiative(
            guildId,
            dmDiscordId,
            InitiativeRollRequest(templateIds = listOf(77L, 77L))
        )

        verify {
            initiativeStore.seed(
                eq(guildId),
                match { seeded ->
                    seeded.size == 2 && seeded.all {
                        val hp = it.maxHp
                        hp != null && hp in 33..90 && it.currentHp == hp
                    }
                }
            )
        }
    }

    @Test
    fun `rollInitiative threads templateId for template-spawned monsters and leaves it null for ad-hoc and players`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin", initiativeModifier = 2
        )
        every { userService.getUserById(playerDiscordId, guildId) } returns UserDto(playerDiscordId, guildId)

        service.rollInitiative(
            guildId,
            dmDiscordId,
            InitiativeRollRequest(
                playerDiscordIds = listOf(playerDiscordId),
                templateIds = listOf(77L),
                adhocMonsters = listOf(AdhocMonster("Bugbear", 1))
            )
        )

        verify {
            initiativeStore.seed(
                eq(guildId),
                match { seeded ->
                    val byName = seeded.associateBy { it.name }
                    byName["Goblin"]?.templateId == 77L &&
                        byName["Bugbear"]?.templateId == null &&
                        byName.values.single { it.kind == "PLAYER" }.templateId == null
                }
            )
        }
    }

    @Test
    fun `rollInitiative leaves null HP when template has no hpExpression`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin", initiativeModifier = 2
        )

        service.rollInitiative(
            guildId,
            dmDiscordId,
            InitiativeRollRequest(templateIds = listOf(77L))
        )

        verify {
            initiativeStore.seed(
                eq(guildId),
                match { seeded ->
                    val goblin = seeded.single()
                    goblin.maxHp == null && goblin.currentHp == null
                }
            )
        }
    }

    // rollDice

    @Test
    fun `rollDice rejects invalid sides`() {
        assertEquals(
            RollDiceResult.INVALID_SIDES,
            service.rollDice(guildId, playerDiscordId, count = 1, sides = 7, modifier = 0)
        )
    }

    @Test
    fun `rollDice rejects count below one`() {
        assertEquals(
            RollDiceResult.INVALID_COUNT,
            service.rollDice(guildId, playerDiscordId, count = 0, sides = 20, modifier = 0)
        )
    }

    @Test
    fun `rollDice rejects count above ceiling`() {
        assertEquals(
            RollDiceResult.INVALID_COUNT,
            service.rollDice(guildId, playerDiscordId, count = 99, sides = 20, modifier = 0)
        )
    }

    @Test
    fun `rollDice rejects modifier out of range`() {
        assertEquals(
            RollDiceResult.INVALID_MODIFIER,
            service.rollDice(guildId, playerDiscordId, count = 1, sides = 20, modifier = 500)
        )
    }

    @Test
    fun `rollDice rejects unparseable custom expression`() {
        assertEquals(
            RollDiceResult.INVALID_EXPRESSION,
            service.rollDice(guildId, playerDiscordId, 1, 20, 0, expression = "not a roll")
        )
    }

    @Test
    fun `rollDice returns NO_ACTIVE_CAMPAIGN when campaign missing`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(
            RollDiceResult.NO_ACTIVE_CAMPAIGN,
            service.rollDice(guildId, playerDiscordId, 1, 20, 0)
        )
    }

    @Test
    fun `rollDice rejects outsider (not DM, not player)`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { campaignPlayerService.getPlayer(CampaignPlayerId(campaign.id, 999L)) } returns null

        assertEquals(
            RollDiceResult.NOT_PARTICIPANT,
            service.rollDice(guildId, 999L, 1, 20, 0)
        )
    }

    @Test
    fun `rollDice allows the DM and publishes ROLL event`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign

        assertEquals(
            RollDiceResult.ROLLED,
            service.rollDice(guildId, dmDiscordId, count = 2, sides = 6, modifier = 3)
        )

        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.ROLL,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match {
                    it["count"] == 2 &&
                        it["sides"] == 6 &&
                        it["modifier"] == 3 &&
                        (it["rawTotal"] as Int) in 2..12 &&
                        (it["total"] as Int) == (it["rawTotal"] as Int) + 3
                }
            )
        }
    }

    @Test
    fun `rollDice allows an active player and publishes ROLL event`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every {
            campaignPlayerService.getPlayer(CampaignPlayerId(campaign.id, playerDiscordId))
        } returns CampaignPlayerDto(
            id = CampaignPlayerId(campaign.id, playerDiscordId),
            guildId = guildId
        )

        assertEquals(
            RollDiceResult.ROLLED,
            service.rollDice(guildId, playerDiscordId, count = 1, sides = 20, modifier = 0)
        )

        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.ROLL,
                actorDiscordId = playerDiscordId,
                actorName = any(),
                payload = match {
                    it["count"] == 1 &&
                        it["sides"] == 20 &&
                        it["modifier"] == 0 &&
                        (it["rawTotal"] as Int) in 1..20 &&
                        (it["total"] as Int) == it["rawTotal"] as Int
                }
            )
        }
    }

    @Test
    fun `rollDice parses custom expression and overrides pickers`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign

        assertEquals(
            RollDiceResult.ROLLED,
            service.rollDice(
                guildId, dmDiscordId,
                count = 1, sides = 20, modifier = 0,
                expression = "3d6+2"
            )
        )

        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.ROLL,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match {
                    it["count"] == 3 && it["sides"] == 6 && it["modifier"] == 2
                }
            )
        }
    }

    @Test
    fun `rollDice custom expression allows implicit count of one`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign

        assertEquals(
            RollDiceResult.ROLLED,
            service.rollDice(guildId, dmDiscordId, 1, 20, 0, expression = "d12-1")
        )

        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.ROLL,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match {
                    it["count"] == 1 && it["sides"] == 12 && it["modifier"] == -1
                }
            )
        }
    }

    // combat: attack

    @Test
    fun `attack rejects when no active combat`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns false

        val outcome = service.attack(guildId, dmDiscordId, "Goblin", 0)
        assertEquals(AttackResult.NO_ACTIVE_COMBAT, outcome.result)
    }

    @Test
    fun `attack rejects non-DM non-current-turn requester`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData(name = "Goblin", roll = 18, kind = "MONSTER", ac = 15)
        every { initiativeStore.currentEntries(guildId) } returns
            listOf(InitiativeEntryData("Goblin", 18, "MONSTER", ac = 15))

        val outcome = service.attack(guildId, playerDiscordId, "Goblin", 0)
        assertEquals(AttackResult.NOT_MY_TURN, outcome.result)
    }

    @Test
    fun `attack hits when roll + mod meets AC and publishes ATTACK_HIT`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData(name = "Goblin", roll = 18, kind = "MONSTER")
        every { initiativeStore.currentEntries(guildId) } returns listOf(
            InitiativeEntryData("Goblin", 18, "MONSTER"),
            // AC 1 + mod 0 → any d20 roll meets or beats it. Deterministic.
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 20, ac = 1)
        )

        val outcome = service.attack(guildId, dmDiscordId, "Alice", 0)
        assertEquals(AttackResult.HIT, outcome.result)
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.ATTACK_HIT,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match {
                    it["attacker"] == "Goblin" && it["target"] == "Alice" &&
                        it["modifier"] == 0 && it["targetAc"] == 1
                }
            )
        }
    }

    @Test
    fun `attack misses when roll + mod is below AC and publishes ATTACK_MISS`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData(name = "Goblin", roll = 18, kind = "MONSTER")
        every { initiativeStore.currentEntries(guildId) } returns listOf(
            InitiativeEntryData("Goblin", 18, "MONSTER"),
            // AC 30 + mod 0 → max d20 roll (20) is below 30. Always miss.
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 20, ac = 30)
        )

        val outcome = service.attack(guildId, dmDiscordId, "Alice", 0)
        assertEquals(AttackResult.MISS, outcome.result)
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.ATTACK_MISS,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { it["targetAc"] == 30 }
            )
        }
    }

    @Test
    fun `attack without AC publishes ATTACK_HIT unconditionally`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Goblin", 18, "MONSTER")
        every { initiativeStore.currentEntries(guildId) } returns listOf(
            InitiativeEntryData("Goblin", 18, "MONSTER"),
            InitiativeEntryData("Alice", 12, "PLAYER")
        )

        val outcome = service.attack(guildId, dmDiscordId, "Alice", -10)
        assertEquals(AttackResult.HIT, outcome.result)
    }

    @Test
    fun `attack rejects self-targeting`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Goblin", 18, "MONSTER")
        every { initiativeStore.currentEntries(guildId) } returns
            listOf(InitiativeEntryData("Goblin", 18, "MONSTER"))

        val outcome = service.attack(guildId, dmDiscordId, "Goblin", 0)
        assertEquals(AttackResult.CANT_TARGET_SELF, outcome.result)
    }

    @Test
    fun `attack rejects defeated target`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Goblin", 18, "MONSTER")
        every { initiativeStore.currentEntries(guildId) } returns listOf(
            InitiativeEntryData("Goblin", 18, "MONSTER"),
            InitiativeEntryData("Alice", 12, "PLAYER", defeated = true)
        )

        val outcome = service.attack(guildId, dmDiscordId, "Alice", 0)
        assertEquals(AttackResult.TARGET_DEFEATED, outcome.result)
    }

    @Test
    fun `attack rejects out-of-range modifier`() {
        val outcome = service.attack(guildId, dmDiscordId, "Alice", 99)
        assertEquals(AttackResult.INVALID_MODIFIER, outcome.result)
    }

    // combat: applyDamage

    @Test
    fun `applyDamage publishes DAMAGE_DEALT and returns APPLIED`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Goblin", 18, "MONSTER")
        every { initiativeStore.applyDamage(guildId, "Alice", 4) } returns
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 16)

        val result = service.applyDamage(guildId, dmDiscordId, "Alice", "4")
        assertEquals(ApplyDamageResult.APPLIED, result)
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.DAMAGE_DEALT,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { it["amount"] == 4 && it["remainingHp"] == 16 && !it.containsKey("expression") }
            )
        }
    }

    @Test
    fun `applyDamage publishes PARTICIPANT_DEFEATED when hp reaches 0`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Goblin", 18, "MONSTER")
        every { initiativeStore.applyDamage(guildId, "Alice", 99) } returns
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 0, defeated = true)

        val result = service.applyDamage(guildId, dmDiscordId, "Alice", "99")
        assertEquals(ApplyDamageResult.DEFEATED, result)
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.PARTICIPANT_DEFEATED,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { it["target"] == "Alice" }
            )
        }
    }

    @Test
    fun `applyDamage rejects negative amount`() {
        assertEquals(
            ApplyDamageResult.INVALID_AMOUNT,
            service.applyDamage(guildId, dmDiscordId, "Alice", "-1")
        )
    }

    @Test
    fun `applyDamage rejects unparseable amount`() {
        assertEquals(
            ApplyDamageResult.INVALID_AMOUNT,
            service.applyDamage(guildId, dmDiscordId, "Alice", "nonsense")
        )
    }

    @Test
    fun `applyDamage rejects integer above max cap`() {
        assertEquals(
            ApplyDamageResult.INVALID_AMOUNT,
            service.applyDamage(guildId, dmDiscordId, "Alice", (CampaignWebService.MAX_DAMAGE_AMOUNT + 1).toString())
        )
    }

    @Test
    fun `applyDamage rolls dice expression and includes expression + rolls in payload`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Goblin", 18, "MONSTER")
        every { initiativeStore.applyDamage(guildId, "Alice", any()) } returns
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 10)

        val result = service.applyDamage(guildId, dmDiscordId, "Alice", "2d6+3")
        assertEquals(ApplyDamageResult.APPLIED, result)
        verify {
            initiativeStore.applyDamage(guildId, "Alice", match {
                // 2d6+3: min 5, max 15. Server rolled a value in that range.
                it in 5..15
            })
        }
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.DAMAGE_DEALT,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match {
                    it["expression"] == "2d6+3" &&
                        (it["rolls"] as? List<*>)?.size == 2 &&
                        (it["amount"] as Int) in 5..15
                }
            )
        }
    }

    @Test
    fun `applyDamage rejects dice expression that exceeds caps`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Goblin", 18, "MONSTER")

        assertEquals(
            ApplyDamageResult.INVALID_AMOUNT,
            service.applyDamage(guildId, dmDiscordId, "Alice", "99d6")
        )
        verify(exactly = 0) { initiativeStore.applyDamage(any(), any(), any()) }
    }

    @Test
    fun `applyDamage returns TARGET_NOT_FOUND when store can't find target`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Goblin", 18, "MONSTER")
        every { initiativeStore.applyDamage(guildId, "Nobody", 4) } returns null

        assertEquals(
            ApplyDamageResult.TARGET_NOT_FOUND,
            service.applyDamage(guildId, dmDiscordId, "Nobody", "4")
        )
    }

    // combat: applyHeal

    private fun stubHealActive(campaign: CampaignDto, entries: List<InitiativeEntryData>) {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Cleric", 15, "PLAYER")
        every { initiativeStore.currentEntries(guildId) } returns entries
    }

    @Test
    fun `applyHeal publishes HEAL_APPLIED and returns APPLIED for integer amount`() {
        val campaign = makeCampaign()
        stubHealActive(campaign, listOf(
            InitiativeEntryData("Cleric", 15, "PLAYER"),
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 10)
        ))
        every { initiativeStore.applyHeal(guildId, "Alice", 5) } returns
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 15)

        val result = service.applyHeal(guildId, dmDiscordId, "Alice", "5")
        assertEquals(ApplyHealResult.APPLIED, result)
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.HEAL_APPLIED,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match {
                    it["healer"] == "Cleric" && it["target"] == "Alice" &&
                        it["amount"] == 5 && it["remainingHp"] == 15 &&
                        it["maxHp"] == 20 && it["revived"] == false
                }
            )
        }
    }

    @Test
    fun `applyHeal rolls dice expression and includes expression + rolls in payload`() {
        val campaign = makeCampaign()
        stubHealActive(campaign, listOf(
            InitiativeEntryData("Cleric", 15, "PLAYER"),
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 10)
        ))
        every { initiativeStore.applyHeal(guildId, "Alice", any()) } returns
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 14)

        val result = service.applyHeal(guildId, dmDiscordId, "Alice", "1d8+2")
        assertEquals(ApplyHealResult.APPLIED, result)
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.HEAL_APPLIED,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match {
                    it["expression"] == "1d8+2" &&
                        (it["rolls"] as? List<*>)?.size == 1
                }
            )
        }
    }

    @Test
    fun `applyHeal marks revived when defeated target HP rises above 0`() {
        val campaign = makeCampaign()
        stubHealActive(campaign, listOf(
            InitiativeEntryData("Cleric", 15, "PLAYER"),
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 0, defeated = true)
        ))
        every { initiativeStore.applyHeal(guildId, "Alice", 8) } returns
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 8, defeated = false)

        val result = service.applyHeal(guildId, dmDiscordId, "Alice", "8")
        assertEquals(ApplyHealResult.REVIVED, result)
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.HEAL_APPLIED,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { it["revived"] == true }
            )
        }
    }

    @Test
    fun `applyHeal rejects target without HP tracked`() {
        val campaign = makeCampaign()
        stubHealActive(campaign, listOf(
            InitiativeEntryData("Cleric", 15, "PLAYER"),
            InitiativeEntryData("Alice", 12, "PLAYER")
        ))

        val result = service.applyHeal(guildId, dmDiscordId, "Alice", "5")
        assertEquals(ApplyHealResult.TARGET_HAS_NO_HP, result)
        verify(exactly = 0) { initiativeStore.applyHeal(any(), any(), any()) }
    }

    @Test
    fun `applyHeal returns TARGET_NOT_FOUND when target missing from tracker`() {
        val campaign = makeCampaign()
        stubHealActive(campaign, listOf(
            InitiativeEntryData("Cleric", 15, "PLAYER")
        ))

        val result = service.applyHeal(guildId, dmDiscordId, "Nobody", "5")
        assertEquals(ApplyHealResult.TARGET_NOT_FOUND, result)
    }

    @Test
    fun `applyHeal rejects invalid amount`() {
        assertEquals(
            ApplyHealResult.INVALID_AMOUNT,
            service.applyHeal(guildId, dmDiscordId, "Alice", "not a number")
        )
    }

    @Test
    fun `applyHeal rejects when no active campaign`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns null
        assertEquals(
            ApplyHealResult.NO_ACTIVE_CAMPAIGN,
            service.applyHeal(guildId, dmDiscordId, "Alice", "5")
        )
    }

    @Test
    fun `applyHeal rejects when no active combat`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns false

        assertEquals(
            ApplyHealResult.NO_ACTIVE_COMBAT,
            service.applyHeal(guildId, dmDiscordId, "Alice", "5")
        )
    }

    @Test
    fun `applyHeal rejects when requester is not current turn or DM`() {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Goblin", 15, "MONSTER")
        every { initiativeStore.currentEntries(guildId) } returns listOf(
            InitiativeEntryData("Goblin", 15, "MONSTER"),
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 10)
        )

        val result = service.applyHeal(guildId, playerDiscordId, "Alice", "5")
        assertEquals(ApplyHealResult.NOT_ATTACKER, result)
    }

    // saveAttack / deleteAttack

    @Test
    fun `saveAttack rejects when template missing`() {
        every { monsterTemplateService.getById(77L) } returns null
        assertEquals(
            SaveAttackResult.TEMPLATE_NOT_FOUND,
            service.saveAttack(dmDiscordId, 77L, attackId = null, name = "Bite", toHitModifier = 5, damageExpression = "2d6+3")
        )
    }

    @Test
    fun `saveAttack rejects non-owner`() {
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = 999L, name = "Someone Else's"
        )
        assertEquals(
            SaveAttackResult.NOT_OWNER,
            service.saveAttack(dmDiscordId, 77L, attackId = null, name = "Bite", toHitModifier = 5, damageExpression = "2d6+3")
        )
    }

    @Test
    fun `saveAttack rejects blank name`() {
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin"
        )
        assertEquals(
            SaveAttackResult.NAME_BLANK,
            service.saveAttack(dmDiscordId, 77L, attackId = null, name = "   ", toHitModifier = 5, damageExpression = "1d6")
        )
    }

    @Test
    fun `saveAttack rejects modifier outside caps`() {
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin"
        )
        assertEquals(
            SaveAttackResult.INVALID_MODIFIER,
            service.saveAttack(dmDiscordId, 77L, attackId = null, name = "Bite", toHitModifier = 99, damageExpression = "1d6")
        )
    }

    @Test
    fun `saveAttack rejects unparseable damage expression`() {
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin"
        )
        assertEquals(
            SaveAttackResult.INVALID_DAMAGE,
            service.saveAttack(dmDiscordId, 77L, attackId = null, name = "Bite", toHitModifier = 0, damageExpression = "garbage")
        )
    }

    @Test
    fun `saveAttack rejects when per-template cap reached`() {
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin"
        )
        every { monsterAttackService.countByTemplate(77L) } returns CampaignWebService.MAX_ATTACKS_PER_TEMPLATE.toLong()
        assertEquals(
            SaveAttackResult.TOO_MANY,
            service.saveAttack(dmDiscordId, 77L, attackId = null, name = "Bite", toHitModifier = 5, damageExpression = "2d6+3")
        )
    }

    @Test
    fun `saveAttack creates when id is null and persists trimmed values`() {
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin"
        )
        every { monsterAttackService.countByTemplate(77L) } returns 0L

        assertEquals(
            SaveAttackResult.SAVED,
            service.saveAttack(dmDiscordId, 77L, attackId = null, name = "  Bite  ", toHitModifier = 5, damageExpression = " 2d6+3 ")
        )
        verify {
            monsterAttackService.save(match {
                it.monsterTemplateId == 77L && it.name == "Bite" &&
                    it.toHitModifier == 5 && it.damageExpression == "2d6+3"
            })
        }
    }

    @Test
    fun `saveAttack updates existing and skips the cap check`() {
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin"
        )
        every { monsterAttackService.getById(42L) } returns MonsterAttackDto(
            id = 42L, monsterTemplateId = 77L, name = "Old", toHitModifier = 1, damageExpression = "1d4"
        )
        every { monsterAttackService.countByTemplate(77L) } returns CampaignWebService.MAX_ATTACKS_PER_TEMPLATE.toLong()

        assertEquals(
            SaveAttackResult.SAVED,
            service.saveAttack(dmDiscordId, 77L, attackId = 42L, name = "Bite", toHitModifier = 5, damageExpression = "2d6+3")
        )
        verify {
            monsterAttackService.save(match {
                it.id == 42L && it.name == "Bite" && it.damageExpression == "2d6+3"
            })
        }
    }

    @Test
    fun `saveAttack rejects updating across templates`() {
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin"
        )
        every { monsterAttackService.getById(42L) } returns MonsterAttackDto(
            id = 42L, monsterTemplateId = 999L, name = "Other", toHitModifier = 0, damageExpression = "1d4"
        )
        assertEquals(
            SaveAttackResult.ATTACK_TEMPLATE_MISMATCH,
            service.saveAttack(dmDiscordId, 77L, attackId = 42L, name = "Bite", toHitModifier = 5, damageExpression = "2d6+3")
        )
    }

    @Test
    fun `deleteAttack rejects missing attack`() {
        every { monsterAttackService.getById(42L) } returns null
        assertEquals(DeleteAttackResult.ATTACK_NOT_FOUND, service.deleteAttack(dmDiscordId, 77L, 42L))
    }

    @Test
    fun `deleteAttack rejects mismatched template`() {
        every { monsterAttackService.getById(42L) } returns MonsterAttackDto(
            id = 42L, monsterTemplateId = 999L, name = "Bite", toHitModifier = 5, damageExpression = "2d6+3"
        )
        assertEquals(
            DeleteAttackResult.ATTACK_TEMPLATE_MISMATCH,
            service.deleteAttack(dmDiscordId, 77L, 42L)
        )
    }

    @Test
    fun `deleteAttack rejects non-owner`() {
        every { monsterAttackService.getById(42L) } returns MonsterAttackDto(
            id = 42L, monsterTemplateId = 77L, name = "Bite", toHitModifier = 5, damageExpression = "2d6+3"
        )
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = 999L, name = "Someone Else's"
        )
        assertEquals(DeleteAttackResult.NOT_OWNER, service.deleteAttack(dmDiscordId, 77L, 42L))
    }

    @Test
    fun `deleteAttack removes when caller owns the template`() {
        every { monsterAttackService.getById(42L) } returns MonsterAttackDto(
            id = 42L, monsterTemplateId = 77L, name = "Bite", toHitModifier = 5, damageExpression = "2d6+3"
        )
        every { monsterTemplateService.getById(77L) } returns MonsterTemplateDto(
            id = 77L, dmDiscordId = dmDiscordId, name = "Goblin"
        )
        assertEquals(DeleteAttackResult.DELETED, service.deleteAttack(dmDiscordId, 77L, 42L))
        verify { monsterAttackService.deleteById(42L) }
    }

    // monsterAttack

    private fun stubMonsterCombat(attack: MonsterAttackDto, target: InitiativeEntryData) {
        val campaign = makeCampaign()
        every { campaignService.getActiveCampaignForGuild(guildId) } returns campaign
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Goblin", 18, "MONSTER", templateId = 77L)
        every { initiativeStore.currentEntries(guildId) } returns listOf(
            InitiativeEntryData("Goblin", 18, "MONSTER", templateId = 77L),
            target
        )
        every { monsterAttackService.getById(attack.id) } returns attack
    }

    @Test
    fun `monsterAttack rejects non-DM requester`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        val outcome = service.monsterAttack(guildId, playerDiscordId, 42L, "Alice")
        assertEquals(MonsterAttackResult.NOT_DM, outcome.result)
    }

    @Test
    fun `monsterAttack rejects when current entry is not a monster`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Alice", 18, "PLAYER")
        val outcome = service.monsterAttack(guildId, dmDiscordId, 42L, "Bob")
        assertEquals(MonsterAttackResult.CURRENT_NOT_MONSTER, outcome.result)
    }

    @Test
    fun `monsterAttack rejects when current monster has no templateId`() {
        every { campaignService.getActiveCampaignForGuild(guildId) } returns makeCampaign()
        every { initiativeStore.isActive(guildId) } returns true
        every { initiativeStore.currentEntry(guildId) } returns
            InitiativeEntryData("Adhoc", 18, "MONSTER", templateId = null)
        val outcome = service.monsterAttack(guildId, dmDiscordId, 42L, "Alice")
        assertEquals(MonsterAttackResult.NO_TEMPLATE, outcome.result)
    }

    @Test
    fun `monsterAttack rejects attack from different template`() {
        val attack = MonsterAttackDto(
            id = 42L, monsterTemplateId = 999L, name = "Bite", toHitModifier = 5, damageExpression = "1d6"
        )
        stubMonsterCombat(attack, InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 20, ac = 10))
        val outcome = service.monsterAttack(guildId, dmDiscordId, 42L, "Alice")
        assertEquals(MonsterAttackResult.ATTACK_TEMPLATE_MISMATCH, outcome.result)
    }

    @Test
    fun `monsterAttack rejects missing target`() {
        val attack = MonsterAttackDto(
            id = 42L, monsterTemplateId = 77L, name = "Bite", toHitModifier = 5, damageExpression = "1d6"
        )
        stubMonsterCombat(attack, InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 20, ac = 10))
        val outcome = service.monsterAttack(guildId, dmDiscordId, 42L, "Ghost")
        assertEquals(MonsterAttackResult.TARGET_NOT_FOUND, outcome.result)
    }

    @Test
    fun `monsterAttack on hit publishes ATTACK_HIT and DAMAGE_DEALT and applies damage`() {
        val attack = MonsterAttackDto(
            id = 42L, monsterTemplateId = 77L, name = "Bite", toHitModifier = 20, damageExpression = "1d4"
        )
        // AC 1 + mod 20 guarantees hit regardless of d20 roll.
        stubMonsterCombat(attack, InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 20, ac = 1))
        every { initiativeStore.applyDamage(guildId, "Alice", any()) } returns
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 15)

        val outcome = service.monsterAttack(guildId, dmDiscordId, 42L, "Alice")
        assertEquals(MonsterAttackResult.HIT, outcome.result)
        assertEquals("Bite", outcome.attackName)
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.ATTACK_HIT,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { it["attackName"] == "Bite" && it["attacker"] == "Goblin" && it["target"] == "Alice" },
                refEventId = null
            )
        }
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.DAMAGE_DEALT,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { it["attackName"] == "Bite" && it["target"] == "Alice" },
                refEventId = null
            )
        }
        verify { initiativeStore.applyDamage(guildId, "Alice", any()) }
    }

    @Test
    fun `monsterAttack on miss publishes only ATTACK_MISS and does not damage`() {
        val attack = MonsterAttackDto(
            id = 42L, monsterTemplateId = 77L, name = "Bite", toHitModifier = 0, damageExpression = "1d4"
        )
        // AC 30 + any d20 + mod 0 never reaches 30.
        stubMonsterCombat(attack, InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 20, ac = 30))

        val outcome = service.monsterAttack(guildId, dmDiscordId, 42L, "Alice")
        assertEquals(MonsterAttackResult.MISS, outcome.result)
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.ATTACK_MISS,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { it["attackName"] == "Bite" && it["targetAc"] == 30 },
                refEventId = null
            )
        }
        verify(exactly = 0) { initiativeStore.applyDamage(any(), any(), any()) }
    }

    @Test
    fun `monsterAttack publishes PARTICIPANT_DEFEATED when damage drops target`() {
        val attack = MonsterAttackDto(
            id = 42L, monsterTemplateId = 77L, name = "Bite", toHitModifier = 20, damageExpression = "1d4"
        )
        stubMonsterCombat(attack, InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 1, ac = 1))
        every { initiativeStore.applyDamage(guildId, "Alice", any()) } returns
            InitiativeEntryData("Alice", 12, "PLAYER", maxHp = 20, currentHp = 0, defeated = true)

        val outcome = service.monsterAttack(guildId, dmDiscordId, 42L, "Alice")
        assertEquals(MonsterAttackResult.HIT, outcome.result)
        assertEquals(true, outcome.targetDefeated)
        verify {
            sessionLog.publish(
                guildId = guildId,
                type = common.events.CampaignEventType.PARTICIPANT_DEFEATED,
                actorDiscordId = dmDiscordId,
                actorName = any(),
                payload = match { it["target"] == "Alice" },
                refEventId = null
            )
        }
    }
}

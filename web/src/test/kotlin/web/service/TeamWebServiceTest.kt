package web.service

import database.dto.guild.TeamPresetDto
import database.dto.guild.TeamSplitSessionDto
import database.service.guild.TeamPresetService
import database.service.guild.TeamSplitSessionService
import database.service.guild.encodeAssignments
import database.service.guild.encodeTeamNames
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.util.GuildMembership

class TeamWebServiceTest {

    private lateinit var presetService: TeamPresetService
    private lateinit var sessionService: TeamSplitSessionService
    private lateinit var introWebService: IntroWebService
    private lateinit var guildMembership: GuildMembership
    private lateinit var jda: JDA
    private lateinit var service: TeamWebService

    private val guildId = 999L

    @BeforeEach
    fun setup() {
        presetService = mockk(relaxed = true)
        sessionService = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        guildMembership = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        service = TeamWebService(presetService, sessionService, introWebService, guildMembership, jda)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `isMember delegates to GuildMembership`() {
        every { guildMembership.isMember(42L, guildId) } returns true
        assertTrue(service.isMember(42L, guildId))
        verify { guildMembership.isMember(42L, guildId) }
    }

    @Test
    fun `upsertPreset rejects empty name`() {
        val err = service.upsertPreset(guildId, "   ", listOf("111"), 42L)
        assertEquals("Preset name is required.", err)
        verify(exactly = 0) { presetService.upsertPreset(any(), any(), any(), any()) }
    }

    @Test
    fun `upsertPreset rejects empty member list`() {
        val err = service.upsertPreset(guildId, "Crew", emptyList(), 42L)
        assertEquals("Pick at least one member.", err)
    }

    @Test
    fun `upsertPreset rejects unknown members`() {
        val guild = guildMock {
            every { getMemberById(111L) } returns memberMock(111L)
            every { getMemberById(999L) } returns null
        }
        every { jda.getGuildById(guildId) } returns guild
        val err = service.upsertPreset(guildId, "Crew", listOf("111", "999"), 42L)
        assertNotNull(err)
        assertTrue(err!!.contains("not in this server"))
    }

    @Test
    fun `upsertPreset persists valid input and returns null on success`() {
        val guild = guildMock {
            every { getMemberById(any<Long>()) } answers { memberMock(it.invocation.args[0] as Long) }
        }
        every { jda.getGuildById(guildId) } returns guild

        val err = service.upsertPreset(guildId, "Crew", listOf("111", "222"), 42L)

        assertNull(err)
        verify {
            presetService.upsertPreset(
                guildId = guildId,
                name = "Crew",
                memberIds = listOf(111L, 222L),
                createdByDiscordId = 42L,
            )
        }
    }

    @Test
    fun `deletePreset enforces guild ownership`() {
        every { presetService.getById(7L) } returns TeamPresetDto(
            id = 7L, guildId = 100L, name = "x", createdByDiscordId = 42L,
        )
        // 100L != guildId — should refuse without deleting
        val err = service.deletePreset(7L, guildId)
        assertEquals("Preset not found.", err)
        verify(exactly = 0) { presetService.deletePreset(any()) }
    }

    @Test
    fun `listRecentSessions decodes assignments and team names`() {
        val guild = guildMock {
            every { getMemberById(111L) } returns memberMock(111L, "Alice")
            every { getMemberById(222L) } returns memberMock(222L, "Bob")
        }
        every { jda.getGuildById(guildId) } returns guild
        val session = TeamSplitSessionDto(
            guildId = guildId, requesterDiscordId = 111L,
            memberIds = "111,222", teamCount = 2,
            assignments = encodeAssignments(listOf(listOf(111L), listOf(222L))),
            teamNames = encodeTeamNames(listOf("Red", "Blue")),
            lastAction = TeamSplitSessionDto.ACTION_CONFIRMED,
        )
        every {
            sessionService.recentForGuild(guildId, TeamWebService.RECENT_SESSIONS_LIMIT)
        } returns listOf(session)

        val out = service.listRecentSessions(guildId)
        assertEquals(1, out.size)
        assertEquals("confirmed", out[0].status)
        assertEquals(listOf("Red", "Blue"), out[0].teams.map { it.name })
        assertEquals(listOf(listOf("Alice"), listOf("Bob")), out[0].teams.map { it.members })
    }

    @Test
    fun `getMutualGuilds delegates to IntroWebService`() {
        val mockGuilds = listOf(GuildInfo("1", "g", null))
        every { introWebService.getMutualGuilds("token") } returns mockGuilds
        assertEquals(mockGuilds, service.getMutualGuilds("token"))
    }

    @Test
    fun `getGuildMembers filters bots and sorts alphabetically`() {
        val human = memberMock(111L, "Bob")
        val bot = memberMock(222L, "BotName", isBot = true)
        val anotherHuman = memberMock(333L, "alice")
        val guild = mockk<Guild>(relaxed = true) {
            every { members } returns listOf(human, bot, anotherHuman)
        }
        every { jda.getGuildById(guildId) } returns guild

        val result = service.getGuildMembers(guildId)
        assertEquals(listOf("alice", "Bob"), result.map { it.name })
        assertFalse(result.any { it.name == "BotName" })
    }

    private fun memberMock(id: Long, displayName: String = "Name $id", isBot: Boolean = false): Member {
        val u = mockk<User>(relaxed = true) {
            every { this@mockk.isBot } returns isBot
            every { name } returns displayName
        }
        return mockk(relaxed = true) {
            every { idLong } returns id
            every { this@mockk.id } returns id.toString()
            every { user } returns u
            every { effectiveName } returns displayName
            every { effectiveAvatarUrl } returns "https://example/$id.png"
        }
    }

    private fun guildMock(block: Guild.() -> Unit): Guild {
        return mockk<Guild>(relaxed = true) { block(this) }
    }
}

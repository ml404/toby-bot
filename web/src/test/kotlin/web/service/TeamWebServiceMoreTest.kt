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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.util.GuildMembership

/**
 * Additional coverage for [TeamWebService] — branches the existing test misses:
 * name-too-long, too-many-members, bot-not-in-guild, non-numeric ids,
 * duplicate ids, deletePreset success path, getGuildName, listPresets
 * (guild/JDA fallback), listRecentSessions with null guild.
 *
 * No Spring context, no Docker, no network — all dependencies mocked.
 */
class TeamWebServiceMoreTest {

    private lateinit var presetService: TeamPresetService
    private lateinit var sessionService: TeamSplitSessionService
    private lateinit var introWebService: IntroWebService
    private lateinit var guildMembership: GuildMembership
    private lateinit var jda: JDA
    private lateinit var service: TeamWebService

    private val guildId = 777L

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

    // ─── upsertPreset validation ───────────────────────────────────────

    @Test
    fun `upsertPreset rejects name that exceeds PRESET_NAME_MAX characters`() {
        val longName = "A".repeat(TeamWebService.PRESET_NAME_MAX + 1)
        val err = service.upsertPreset(guildId, longName, listOf("111"), 42L)
        assertNotNull(err)
        assertTrue(err!!.contains("too long"))
        verify(exactly = 0) { presetService.upsertPreset(any(), any(), any(), any()) }
    }

    @Test
    fun `upsertPreset rejects member list exceeding MAX_MEMBERS_PER_PRESET`() {
        val ids = (1..TeamWebService.MAX_MEMBERS_PER_PRESET + 1).map { it.toString() }
        val guild = guildMock {
            every { getMemberById(any<Long>()) } answers { memberMock(it.invocation.args[0] as Long) }
        }
        every { jda.getGuildById(guildId) } returns guild

        val err = service.upsertPreset(guildId, "Big Crew", ids, 42L)
        assertNotNull(err)
        assertTrue(err!!.contains("Too many members"))
        verify(exactly = 0) { presetService.upsertPreset(any(), any(), any(), any()) }
    }

    @Test
    fun `upsertPreset returns error when bot is not in the guild`() {
        every { jda.getGuildById(guildId) } returns null

        val err = service.upsertPreset(guildId, "Crew", listOf("111"), 42L)
        assertNotNull(err)
        assertTrue(err!!.contains("Bot is not in that server"))
    }

    @Test
    fun `upsertPreset silently drops non-numeric member ids`() {
        // "abc" is not a valid Long — mapNotNull filters it. Only "111" remains.
        val guild = guildMock {
            every { getMemberById(111L) } returns memberMock(111L)
        }
        every { jda.getGuildById(guildId) } returns guild

        val err = service.upsertPreset(guildId, "Crew", listOf("abc", "111"), 42L)

        assertNull(err)
        // Should be called with only the valid id
        verify {
            presetService.upsertPreset(
                guildId = guildId,
                name = "Crew",
                memberIds = listOf(111L),
                createdByDiscordId = 42L,
            )
        }
    }

    @Test
    fun `upsertPreset deduplicates repeated member ids`() {
        val guild = guildMock {
            every { getMemberById(111L) } returns memberMock(111L)
        }
        every { jda.getGuildById(guildId) } returns guild

        val err = service.upsertPreset(guildId, "Crew", listOf("111", "111", "111"), 42L)

        assertNull(err)
        verify {
            presetService.upsertPreset(
                guildId = guildId,
                name = "Crew",
                memberIds = listOf(111L),
                createdByDiscordId = 42L,
            )
        }
    }

    @Test
    fun `upsertPreset returns empty-list error after filtering all non-numeric ids`() {
        // All IDs are non-numeric — after mapNotNull the list is empty.
        val err = service.upsertPreset(guildId, "Crew", listOf("foo", "bar"), 42L)
        assertEquals("Pick at least one member.", err)
    }

    @Test
    fun `upsertPreset trims whitespace from name before validation`() {
        // "  " is blank after trim — should fail with "required" not "too long"
        val err = service.upsertPreset(guildId, "  ", listOf("111"), 42L)
        assertEquals("Preset name is required.", err)
    }

    @Test
    fun `upsertPreset name at exactly PRESET_NAME_MAX length is accepted`() {
        val exactName = "X".repeat(TeamWebService.PRESET_NAME_MAX)
        val guild = guildMock {
            every { getMemberById(any<Long>()) } answers { memberMock(it.invocation.args[0] as Long) }
        }
        every { jda.getGuildById(guildId) } returns guild

        val err = service.upsertPreset(guildId, exactName, listOf("111"), 42L)
        assertNull(err)
    }

    // ─── deletePreset ─────────────────────────────────────────────────

    @Test
    fun `deletePreset returns error when preset does not exist`() {
        every { presetService.getById(99L) } returns null
        val err = service.deletePreset(99L, guildId)
        assertEquals("Preset not found.", err)
        verify(exactly = 0) { presetService.deletePreset(any()) }
    }

    @Test
    fun `deletePreset succeeds and returns null for matching guild`() {
        every { presetService.getById(5L) } returns TeamPresetDto(
            id = 5L, guildId = guildId, name = "Crew", createdByDiscordId = 42L,
        )

        val err = service.deletePreset(5L, guildId)

        assertNull(err)
        verify { presetService.deletePreset(5L) }
    }

    @Test
    fun `deletePreset rejects when preset belongs to a different guild`() {
        every { presetService.getById(5L) } returns TeamPresetDto(
            id = 5L, guildId = 9999L, name = "Crew", createdByDiscordId = 42L,
        )

        val err = service.deletePreset(5L, guildId)

        assertEquals("Preset not found.", err)
        verify(exactly = 0) { presetService.deletePreset(any()) }
    }

    // ─── getGuildName ─────────────────────────────────────────────────

    @Test
    fun `getGuildName returns the guild name when bot is in the guild`() {
        val guild = mockk<Guild>(relaxed = true) {
            every { name } returns "My Server"
        }
        every { jda.getGuildById(guildId) } returns guild

        assertEquals("My Server", service.getGuildName(guildId))
    }

    @Test
    fun `getGuildName returns null when bot is not in the guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertNull(service.getGuildName(guildId))
    }

    // ─── getGuildMembers ──────────────────────────────────────────────

    @Test
    fun `getGuildMembers returns empty list when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertTrue(service.getGuildMembers(guildId).isEmpty())
    }

    // ─── listPresets ──────────────────────────────────────────────────

    @Test
    fun `listPresets returns empty when no presets for guild`() {
        every { presetService.listForGuild(guildId) } returns emptyList()
        assertTrue(service.listPresets(guildId).isEmpty())
    }

    @Test
    fun `listPresets resolves member name via guild getMemberById`() {
        val guild = guildMock {
            every { getMemberById(111L) } returns memberMock(111L, "GuildName")
        }
        every { jda.getGuildById(guildId) } returns guild
        every { presetService.listForGuild(guildId) } returns listOf(
            TeamPresetDto(id = 1L, guildId = guildId, name = "MyPreset",
                createdByDiscordId = 42L, memberIds = "111")
        )

        val results = service.listPresets(guildId)

        assertEquals(1, results.size)
        assertEquals("MyPreset", results[0].name)
        assertEquals(1, results[0].members.size)
        assertEquals("GuildName", results[0].members[0].name)
        assertEquals("111", results[0].members[0].id)
    }

    @Test
    fun `listPresets falls back to jda getUserById when member not in guild`() {
        val guild = guildMock {
            every { getMemberById(222L) } returns null
        }
        val user = mockk<User>(relaxed = true) { every { name } returns "GlobalName" }
        every { jda.getGuildById(guildId) } returns guild
        every { jda.getUserById(222L) } returns user
        every { presetService.listForGuild(guildId) } returns listOf(
            TeamPresetDto(id = 2L, guildId = guildId, name = "Preset2",
                createdByDiscordId = 42L, memberIds = "222")
        )

        val results = service.listPresets(guildId)

        assertEquals(1, results[0].members.size)
        assertEquals("GlobalName", results[0].members[0].name)
    }

    @Test
    fun `listPresets shows Unknown (id) when member is fully unresolvable`() {
        val guild = guildMock {
            every { getMemberById(333L) } returns null
        }
        every { jda.getGuildById(guildId) } returns guild
        every { jda.getUserById(333L) } returns null
        every { presetService.listForGuild(guildId) } returns listOf(
            TeamPresetDto(id = 3L, guildId = guildId, name = "Preset3",
                createdByDiscordId = 42L, memberIds = "333")
        )

        val results = service.listPresets(guildId)

        assertEquals("Unknown (333)", results[0].members[0].name)
    }

    @Test
    fun `listPresets works when guild is null (bot left the server)`() {
        every { jda.getGuildById(guildId) } returns null
        every { jda.getUserById(444L) } returns null
        every { presetService.listForGuild(guildId) } returns listOf(
            TeamPresetDto(id = 4L, guildId = guildId, name = "OldPreset",
                createdByDiscordId = 42L, memberIds = "444")
        )

        // Should not throw — falls back to "Unknown (444)"
        val results = service.listPresets(guildId)

        assertEquals(1, results.size)
        assertEquals("Unknown (444)", results[0].members[0].name)
    }

    @Test
    fun `listPresets includes metadata fields`() {
        val guild = guildMock {
            every { getMemberById(any<Long>()) } answers { memberMock(it.invocation.args[0] as Long) }
        }
        every { jda.getGuildById(guildId) } returns guild
        every { presetService.listForGuild(guildId) } returns listOf(
            TeamPresetDto(id = 7L, guildId = guildId, name = "Alpha",
                createdByDiscordId = 99L, memberIds = "111,222")
        )

        val results = service.listPresets(guildId)

        assertEquals(1, results.size)
        val vm = results[0]
        assertEquals(7L, vm.id)
        assertEquals("Alpha", vm.name)
        assertEquals(2, vm.memberCount)
        assertEquals(99L, vm.createdByDiscordId)
    }

    // ─── listRecentSessions ───────────────────────────────────────────

    @Test
    fun `listRecentSessions returns empty when no recent sessions`() {
        every { sessionService.recentForGuild(guildId, TeamWebService.RECENT_SESSIONS_LIMIT) } returns emptyList()
        assertTrue(service.listRecentSessions(guildId).isEmpty())
    }

    @Test
    fun `listRecentSessions falls back to jda getUserById for requester when guild is null`() {
        every { jda.getGuildById(guildId) } returns null
        val user = mockk<User>(relaxed = true) { every { name } returns "RequesterName" }
        every { jda.getUserById(555L) } returns user
        every { sessionService.recentForGuild(guildId, TeamWebService.RECENT_SESSIONS_LIMIT) } returns listOf(
            TeamSplitSessionDto(
                guildId = guildId, requesterDiscordId = 555L,
                memberIds = "555", teamCount = 1,
                assignments = encodeAssignments(listOf(listOf(555L))),
                teamNames = encodeTeamNames(listOf("Solo")),
                lastAction = TeamSplitSessionDto.ACTION_CONFIRMED,
            )
        )

        val results = service.listRecentSessions(guildId)

        assertEquals(1, results.size)
        assertEquals("RequesterName", results[0].requester)
    }

    @Test
    fun `listRecentSessions uses Unknown when requester unresolvable`() {
        every { jda.getGuildById(guildId) } returns null
        every { jda.getUserById(666L) } returns null
        every { sessionService.recentForGuild(guildId, TeamWebService.RECENT_SESSIONS_LIMIT) } returns listOf(
            TeamSplitSessionDto(
                guildId = guildId, requesterDiscordId = 666L,
                memberIds = "666", teamCount = 1,
                assignments = encodeAssignments(listOf(listOf(666L))),
                teamNames = encodeTeamNames(listOf("X")),
                lastAction = TeamSplitSessionDto.ACTION_CONFIRMED,
            )
        )

        val results = service.listRecentSessions(guildId)
        assertEquals("Unknown", results[0].requester)
    }

    @Test
    fun `listRecentSessions falls back to jda getUserById for team member when not in guild`() {
        val guild = guildMock {
            every { getMemberById(777L) } returns null
            every { getMemberById(888L) } returns memberMock(888L, "Present")
        }
        val user = mockk<User>(relaxed = true) { every { name } returns "GlobeUser" }
        every { jda.getGuildById(guildId) } returns guild
        every { jda.getUserById(777L) } returns user
        // requester resolved via guild
        every { guild.getMemberById(888L) } returns memberMock(888L, "Present")
        every { sessionService.recentForGuild(guildId, TeamWebService.RECENT_SESSIONS_LIMIT) } returns listOf(
            TeamSplitSessionDto(
                guildId = guildId, requesterDiscordId = 888L,
                memberIds = "777,888", teamCount = 2,
                assignments = encodeAssignments(listOf(listOf(777L), listOf(888L))),
                teamNames = encodeTeamNames(listOf("TeamA", "TeamB")),
                lastAction = TeamSplitSessionDto.ACTION_CONFIRMED,
            )
        )

        val results = service.listRecentSessions(guildId)

        assertEquals(1, results.size)
        val teamA = results[0].teams[0]
        assertEquals("TeamA", teamA.name)
        assertEquals(listOf("GlobeUser"), teamA.members)
    }

    @Test
    fun `listRecentSessions uses Unknown for team member fully unresolvable`() {
        val guild = guildMock {
            every { getMemberById(any<Long>()) } returns null
        }
        every { jda.getGuildById(guildId) } returns guild
        every { jda.getUserById(any<Long>()) } returns null
        every { sessionService.recentForGuild(guildId, TeamWebService.RECENT_SESSIONS_LIMIT) } returns listOf(
            TeamSplitSessionDto(
                guildId = guildId, requesterDiscordId = 100L,
                memberIds = "999", teamCount = 1,
                assignments = encodeAssignments(listOf(listOf(999L))),
                teamNames = encodeTeamNames(listOf("Lost")),
                lastAction = TeamSplitSessionDto.ACTION_CONFIRMED,
            )
        )

        val results = service.listRecentSessions(guildId)
        assertEquals("Unknown (999)", results[0].teams[0].members[0])
    }

    @Test
    fun `listRecentSessions uses fallback team name when teamNames list is short`() {
        val guild = guildMock {
            every { getMemberById(any<Long>()) } answers { memberMock(it.invocation.args[0] as Long) }
        }
        every { jda.getGuildById(guildId) } returns guild
        // encodeTeamNames with only 1 name but 2 teams
        every { sessionService.recentForGuild(guildId, TeamWebService.RECENT_SESSIONS_LIMIT) } returns listOf(
            TeamSplitSessionDto(
                guildId = guildId, requesterDiscordId = 1L,
                memberIds = "1,2", teamCount = 2,
                assignments = encodeAssignments(listOf(listOf(1L), listOf(2L))),
                teamNames = encodeTeamNames(listOf("Red")), // only 1 name
                lastAction = TeamSplitSessionDto.ACTION_CONFIRMED,
            )
        )

        val results = service.listRecentSessions(guildId)
        assertEquals(2, results[0].teams.size)
        assertEquals("Red", results[0].teams[0].name)
        assertEquals("Team 2", results[0].teams[1].name) // fallback
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private fun memberMock(id: Long, displayName: String = "Name $id"): Member {
        val u = mockk<User>(relaxed = true) {
            every { isBot } returns false
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

    private fun guildMock(block: Guild.() -> Unit): Guild = mockk<Guild>(relaxed = true) { block(this) }
}

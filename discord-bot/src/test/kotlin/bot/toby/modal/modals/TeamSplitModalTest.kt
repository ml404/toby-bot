package bot.toby.modal.modals

import core.modal.ModalContext
import database.dto.TeamPresetDto
import database.dto.TeamSplitSessionDto
import database.service.TeamPresetService
import database.service.TeamSplitSessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class TeamSplitModalTest {

    private lateinit var teamPresetService: TeamPresetService
    private lateinit var teamSplitSessionService: TeamSplitSessionService
    private lateinit var modal: TeamSplitModal
    private lateinit var ctx: ModalContext
    private lateinit var event: ModalInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var guild: Guild
    private val errorSlot = slot<String>()

    @BeforeEach
    fun setup() {
        teamPresetService = mockk(relaxed = true)
        teamSplitSessionService = mockk(relaxed = true)
        modal = TeamSplitModal(teamPresetService, teamSplitSessionService)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true)
        guild = mockk(relaxed = true) {
            every { idLong } returns 100L
            every { maxBitrate } returns 96000
        }

        val user = mockk<User>(relaxed = true) {
            every { idLong } returns 42L
        }
        every { event.user } returns user
        every { event.hook } returns hook

        ctx = mockk {
            every { this@mockk.event } returns this@TeamSplitModalTest.event
            every { this@mockk.guild } returns this@TeamSplitModalTest.guild
        }

        // hook + the WebhookMessageCreateAction it returns are relaxed mocks,
        // so the fluent setEphemeral / addComponents / queue chain just works.
        // The capture-slot stub overrides the relaxed default for sendMessage(String)
        // to record what was sent.
        @Suppress("UNCHECKED_CAST")
        val sendAction = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessage(capture(errorSlot)) } returns sendAction

        // Default: empty modal values; individual tests override the ones they need.
        every { event.getValue(TeamSplitModal.FIELD_PRESET_NAME) } returns null
        every { event.getValue(TeamSplitModal.FIELD_MEMBERS) } returns null
        every { event.getValue(TeamSplitModal.FIELD_TEAM_COUNT) } returns mockk { every { asString } returns "2" }
        every { event.getValue(TeamSplitModal.FIELD_NAME_STRATEGY) } returns mockk { every { asString } returns "prefix" }
        every { event.getValue(TeamSplitModal.FIELD_NAMES) } returns mockk { every { asString } returns "Team" }
    }

    @Test
    fun `name is team_split`() {
        assertEquals("team_split", modal.name)
    }

    @Test
    fun `errors when no members provided and no preset named`() {
        modal.handle(ctx, 0)
        assertTrue(errorSlot.captured.contains("at least 2 members"))
    }

    @Test
    fun `errors when preset name is unknown`() {
        every { event.getValue(TeamSplitModal.FIELD_PRESET_NAME) } returns mockk { every { asString } returns "ghosts" }
        every { teamPresetService.getByName(100L, "ghosts") } returns null

        modal.handle(ctx, 0)

        assertTrue(errorSlot.captured.contains("No preset named 'ghosts'"))
    }

    @Test
    fun `parseMemberIds extracts mentions and bare snowflakes and dedupes`() {
        val ids = TeamSplitModal.parseMemberIds("<@111111111111111111> <@!222222222222222222> 111111111111111111 333333333333333333")
        assertEquals(listOf(111111111111111111L, 222222222222222222L, 333333333333333333L), ids)
    }

    @Test
    fun `parseMemberIds yields empty on blank input`() {
        assertTrue(TeamSplitModal.parseMemberIds("").isEmpty())
        assertTrue(TeamSplitModal.parseMemberIds("   ").isEmpty())
    }

    @Test
    fun `errors when team count exceeds resolved member count`() {
        val members = listOf(memberMock(111L, "A"), memberMock(222L, "B"))
        every { event.getValue(TeamSplitModal.FIELD_MEMBERS) } returns mockk {
            every { asString } returns "<@111> <@222>"
        }
        every { event.getValue(TeamSplitModal.FIELD_TEAM_COUNT) } returns mockk { every { asString } returns "5" }
        every { guild.getMemberById(111L) } returns members[0]
        every { guild.getMemberById(222L) } returns members[1]

        modal.handle(ctx, 0)

        assertTrue(errorSlot.captured.contains("larger than the member count"))
    }

    @Test
    fun `errors when list-strategy name count mismatches team count`() {
        val members = listOf(memberMock(111L, "A"), memberMock(222L, "B"))
        every { event.getValue(TeamSplitModal.FIELD_MEMBERS) } returns mockk {
            every { asString } returns "<@111> <@222>"
        }
        every { event.getValue(TeamSplitModal.FIELD_NAME_STRATEGY) } returns mockk { every { asString } returns "list" }
        every { event.getValue(TeamSplitModal.FIELD_NAMES) } returns mockk { every { asString } returns "Red,Blue,Green" }
        every { guild.getMemberById(111L) } returns members[0]
        every { guild.getMemberById(222L) } returns members[1]

        modal.handle(ctx, 0)

        assertTrue(errorSlot.captured.contains("wrong number of entries"))
    }

    @Test
    fun `persists session before sending preview embed`() {
        val members = listOf(memberMock(111L, "Alice"), memberMock(222L, "Bob"))
        every { event.getValue(TeamSplitModal.FIELD_MEMBERS) } returns mockk {
            every { asString } returns "<@111> <@222>"
        }
        every { guild.getMemberById(111L) } returns members[0]
        every { guild.getMemberById(222L) } returns members[1]
        val sessionId = UUID.randomUUID()
        every {
            teamSplitSessionService.createSession(
                guildId = 100L, requesterDiscordId = 42L,
                memberIds = any(), teamCount = 2,
                assignments = any(), teamNames = any(),
            )
        } returns TeamSplitSessionDto(id = sessionId, guildId = 100L, requesterDiscordId = 42L, teamCount = 2)

        modal.handle(ctx, 0)

        verify {
            teamSplitSessionService.createSession(
                guildId = 100L, requesterDiscordId = 42L,
                memberIds = match { it.size == 2 && it.containsAll(listOf(111L, 222L)) },
                teamCount = 2,
                assignments = any(),
                teamNames = match { it == listOf("Team 1", "Team 2") },
            )
        }
        verify { hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg<MessageEmbed>()) }
    }

    @Test
    fun `unions preset members with pasted members and dedupes`() {
        val preset = TeamPresetDto(id = 1L, guildId = 100L, name = "core", createdByDiscordId = 42L).apply {
            memberIdList = listOf(111L, 222L)
        }
        every { event.getValue(TeamSplitModal.FIELD_PRESET_NAME) } returns mockk { every { asString } returns "core" }
        every { teamPresetService.getByName(100L, "core") } returns preset
        every { event.getValue(TeamSplitModal.FIELD_MEMBERS) } returns mockk {
            // 222 overlaps with preset; 333 is new
            every { asString } returns "<@222> <@333>"
        }
        every { guild.getMemberById(111L) } returns memberMock(111L, "A")
        every { guild.getMemberById(222L) } returns memberMock(222L, "B")
        every { guild.getMemberById(333L) } returns memberMock(333L, "C")
        val captured = slot<List<Long>>()
        every {
            teamSplitSessionService.createSession(
                guildId = any(), requesterDiscordId = any(),
                memberIds = capture(captured), teamCount = any(),
                assignments = any(), teamNames = any(),
            )
        } returns TeamSplitSessionDto(guildId = 100L)

        modal.handle(ctx, 0)

        assertNotNull(captured.captured)
        assertEquals(listOf(111L, 222L, 333L).sorted(), captured.captured.sorted())
    }

    private fun memberMock(id: Long, displayName: String): Member {
        val u = mockk<User>(relaxed = true) {
            every { isBot } returns false
        }
        return mockk(relaxed = true) {
            every { idLong } returns id
            every { user } returns u
            every { effectiveName } returns displayName
        }
    }
}

package bot.toby.button.buttons

import bot.toby.command.commands.misc.TeamCommand
import core.button.ButtonContext
import database.dto.TeamSplitSessionDto
import database.dto.UserDto
import database.service.TeamSplitSessionService
import database.service.encodeAssignments
import database.service.encodeTeamNames
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class TeamRerollButtonTest {

    private lateinit var sessionService: TeamSplitSessionService
    private lateinit var button: TeamRerollButton
    private lateinit var ctx: ButtonContext
    private lateinit var event: ButtonInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var guild: Guild
    private lateinit var requesterDto: UserDto
    private val sessionId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        sessionService = mockk(relaxed = true)
        button = TeamRerollButton(sessionService)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true) {
            every { componentId } returns "${TeamCommand.BUTTON_REROLL}:$sessionId"
            every { deferEdit() } returns mockk(relaxed = true)
            every { this@mockk.hook } returns this@TeamRerollButtonTest.hook
        }
        guild = mockk(relaxed = true)
        ctx = mockk {
            every { this@mockk.event } returns this@TeamRerollButtonTest.event
            every { this@mockk.guild } returns this@TeamRerollButtonTest.guild
        }
        requesterDto = mockk(relaxed = true)
    }

    @Test
    fun `updates assignments and edits embed in place keeping the same buttons`() {
        val members = listOf(111L, 222L, 333L, 444L).map { memberMock(it) }
        members.forEach { every { guild.getMemberById(it.idLong) } returns it }

        val sessionDto = TeamSplitSessionDto(
            id = sessionId, guildId = 100L, requesterDiscordId = 42L,
            memberIds = "111,222,333,444", teamCount = 2,
            assignments = encodeAssignments(listOf(listOf(111L, 222L), listOf(333L, 444L))),
            teamNames = encodeTeamNames(listOf("Red", "Blue")),
            lastAction = TeamSplitSessionDto.ACTION_CREATED,
        )
        every { sessionService.getSession(sessionId) } returns sessionDto

        @Suppress("UNCHECKED_CAST")
        val editAction = mockk<WebhookMessageEditAction<net.dv8tion.jda.api.entities.Message>>(relaxed = true)
        every { hook.editOriginalEmbeds(*anyVararg()) } returns editAction
        every { editAction.setComponents(*anyVararg()) } returns editAction
        every { editAction.queue() } just Runs

        val captured = slot<List<List<Long>>>()
        every { sessionService.updateAssignments(sessionId, capture(captured)) } returns sessionDto

        button.handle(ctx, requesterDto, 0)

        // New assignments must still total 4 members across 2 teams.
        val flat = captured.captured.flatten()
        assert(flat.size == 4)
        assert(flat.toSet() == setOf(111L, 222L, 333L, 444L))
        assert(captured.captured.size == 2)
        verify { hook.editOriginalEmbeds(*anyVararg()) }
    }

    @Test
    fun `confirmed session is rejected with an ephemeral message`() {
        val sessionDto = TeamSplitSessionDto(
            id = sessionId, guildId = 100L, requesterDiscordId = 42L,
            memberIds = "111,222", teamCount = 2,
            assignments = encodeAssignments(listOf(listOf(111L), listOf(222L))),
            teamNames = encodeTeamNames(listOf("A", "B")),
            lastAction = TeamSplitSessionDto.ACTION_CONFIRMED,
        )
        every { sessionService.getSession(sessionId) } returns sessionDto

        val msg = slot<String>()
        @Suppress("UNCHECKED_CAST")
        val sendAction = mockk<net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction<net.dv8tion.jda.api.entities.Message>>(relaxed = true)
        every { hook.sendMessage(capture(msg)) } returns sendAction
        every { sendAction.setEphemeral(true) } returns sendAction
        every { sendAction.queue() } just Runs

        button.handle(ctx, requesterDto, 0)

        verify(exactly = 0) { sessionService.updateAssignments(any(), any()) }
        assert(msg.captured.contains("Already confirmed", ignoreCase = true))
    }

    private fun memberMock(id: Long): Member {
        val u = mockk<User>(relaxed = true) { every { isBot } returns false }
        return mockk(relaxed = true) {
            every { idLong } returns id
            every { user } returns u
            every { effectiveName } returns "Name $id"
        }
    }
}

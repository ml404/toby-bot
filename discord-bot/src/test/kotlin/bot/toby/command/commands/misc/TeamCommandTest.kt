package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import bot.toby.modal.modals.TeamSplitModal
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class TeamCommandTest : CommandTest {
    private lateinit var teamCommand: TeamCommand

    @BeforeEach
    fun beforeEach() {
        setUpCommonMocks()
        teamCommand = TeamCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun `split subcommand opens the team-split modal as the first response`() {
        val ctx = DefaultCommandContext(event)
        val modalCallback = mockk<ModalCallbackAction>(relaxed = true)
        val captured = slot<Modal>()
        every { event.subcommandName } returns TeamCommand.SUB_SPLIT
        every { event.getOption("members") } returns null
        every { event.replyModal(capture(captured)) } returns modalCallback
        every { modalCallback.queue() } just Runs

        teamCommand.handle(ctx, requestingUserDto, 0)

        // The modal must be the FIRST reply — Discord rejects replyModal
        // after deferReply.
        verify(exactly = 0) { event.deferReply() }
        verify { event.replyModal(any<Modal>()) }
        assertEquals(TeamSplitModal.MODAL_NAME, captured.captured.id)
    }

    @Test
    fun `cleanup subcommand deletes only matching team channels`() {
        val ctx = DefaultCommandContext(event)
        val teamChannel: GuildChannel = mockk(relaxed = true) {
            every { name } returns "Team 1"
        }
        val unrelatedChannel: GuildChannel = mockk(relaxed = true) {
            every { name } returns "general"
        }
        val deleteAction = mockk<AuditableRestAction<Void>>(relaxed = true)
        every { teamChannel.delete() } returns deleteAction
        every { deleteAction.queue() } just Runs

        every { event.subcommandName } returns TeamCommand.SUB_CLEANUP
        every { guild.channels } returns listOf(teamChannel, unrelatedChannel)

        teamCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { teamChannel.delete() }
        verify(exactly = 0) { unrelatedChannel.delete() }
        verify { event.deferReply() }
    }

    @Test
    fun `split helper distributes remainder across the first teams instead of dropping members`() {
        val members = (1..7).map { mockk<Member>(relaxed = true) }
        val groups = TeamCommand.split(members, 3)
        // 7 members, 3 teams — expect group sizes 3, 2, 2 (no member dropped).
        val sizes = groups.map { it.size }.sortedDescending()
        assertEquals(listOf(3, 2, 2), sizes)
        val total = groups.sumOf { it.size }
        assertEquals(7, total)
        // All inputs must appear in the output exactly once.
        val flat = groups.flatten()
        assertTrue(members.all { it in flat })
    }
}

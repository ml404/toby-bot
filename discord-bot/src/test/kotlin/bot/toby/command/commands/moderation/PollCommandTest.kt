package bot.toby.command.commands.moderation

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import bot.toby.modal.modals.PollModal
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class PollCommandTest : CommandTest {
    private lateinit var pollCommand: PollCommand
    private val modalCallback: ModalCallbackAction = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        pollCommand = PollCommand()
        every { event.replyModal(any<Modal>()) } returns modalCallback
        every { modalCallback.queue() } just runs
    }

    @AfterEach
    fun teardown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun `opens the poll modal`() {
        val ctx = DefaultCommandContext(event)
        val captured = slot<Modal>()

        pollCommand.handle(ctx, requestingUserDto, 0)

        verify { event.replyModal(capture(captured)) }
        assertEquals(PollModal.MODAL_NAME, captured.captured.id)
    }
}

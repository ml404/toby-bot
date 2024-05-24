package toby.command.commands.misc

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.interactionHook
import toby.command.CommandTest.Companion.requestingUserDto

internal class ChCommandTest : CommandTest {
    lateinit var command: ChCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        command = ChCommand()
        // Mock OptionMapping for the MESSAGE option
        val messageOption = mockk<OptionMapping>()
        every { messageOption.asString } returns "hello world"
        every { event.getOption("message") } returns messageOption

        // Mock the event to return the MESSAGE option
        every { event.getOption(any()) } returns messageOption
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testHandle() {
        // Create a CommandContext
        val ctx = CommandContext(event)

        // Mock requestingUserDto
        val deleteDelay = 0 // Set your desired deleteDelay

        // Test the handle method
        command.handle(ctx, requestingUserDto, deleteDelay)

        // Verify that the message was sent with the expected content
        // You can use verify to check if event.hook.sendMessage(...) was called with the expected message content.
        verify(exactly = 1) { event.hook.sendMessage("Oh! I think you mean: 'chello chorld'") }
    }
}

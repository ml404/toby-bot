package toby.command.commands.misc

import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.jpa.dto.UserDto

internal class ChCommandTest : CommandTest {
    var command: ChCommand? = null

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        command = ChCommand()
        // Mock OptionMapping for the MESSAGE option
        val messageOption = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`(messageOption.asString).thenReturn("hello world")
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("message")).thenReturn(messageOption)

        // Mock the event to return the MESSAGE option
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption(ArgumentMatchers.anyString()))
            .thenReturn(messageOption)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testHandle() {
        // Create a CommandContext
        val ctx = CommandContext(CommandTest.event)

        // Mock requestingUserDto
        val requestingUserDto = UserDto() // You can set the user as needed
        val deleteDelay = 0 // Set your desired deleteDelay

        // Test the handle method
        command!!.handle(ctx, requestingUserDto, deleteDelay)

        // Verify that the message was sent with the expected content
        // You can use Mockito.verify() to check if event.getHook().sendMessage(...) was called with the expected message content.
        // For example:
        Mockito.verify(CommandTest.event.hook)
            .sendMessage("Oh! I think you mean: 'chello chorld'")
    }
}
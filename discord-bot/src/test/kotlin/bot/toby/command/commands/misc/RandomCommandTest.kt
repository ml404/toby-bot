package bot.toby.command.commands.misc

import database.dto.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import bot.toby.command.CommandContext
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event

class RandomCommandTest : CommandTest {
    private lateinit var randomCommand: RandomCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        randomCommand = RandomCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testHandleCommandWithList() {
        // Mock the list of options provided by the user
        val listOption = mockk<OptionMapping>()
        every { listOption.asString } returns "Option1,Option2,Option3"

        // Mock the event's options to return the list option
        every { event.getOption("list") } returns listOption


        // Call the handle method with the event
        randomCommand.handle(
            CommandContext(event),
            mockk<UserDto>(),
            0
        )

        // Verify that the interactionHook's sendMessage method is called with a random option
        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun testHandleCommandWithoutList() {

        // Call the handle method with the event
        randomCommand.handle(
            CommandContext(event),
            mockk<UserDto>(),
            0
        )

        // Verify that the interactionHook's sendMessage method is called with the command's description
        verify(exactly = 1) { event.hook.sendMessage("Return one item from a list you provide with options separated by commas.") }
    }
}

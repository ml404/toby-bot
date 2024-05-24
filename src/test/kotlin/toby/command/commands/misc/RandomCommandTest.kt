package toby.command.commands.misc

import io.mockk.*
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.ICommand.Companion.deleteAfter
import toby.jpa.dto.UserDto

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
        every { CommandTest.event.getOption("list") } returns listOption

        // Mock ICommand's deleteOriginal and queueAfter
        every { deleteAfter(CommandTest.interactionHook, any()) } just Runs

        // Call the handle method with the event
        randomCommand.handle(
            CommandContext(CommandTest.event),
            mockk<UserDto>(),
            0
        )

        // Verify that the interactionHook's sendMessage method is called with a random option
        verify(exactly = 1) { CommandTest.interactionHook.sendMessage(any<String>()) }
    }

    @Test
    fun testHandleCommandWithoutList() {
        // Mock the event's options to be empty
        every { CommandTest.event.options } returns listOf()

        // Mock ICommand's deleteOriginal and queueAfter
        every { deleteAfter(CommandTest.interactionHook, any()) } just Runs

        // Call the handle method with the event
        randomCommand.handle(
            CommandContext(CommandTest.event),
            mockk<UserDto>(),
            0
        )

        // Verify that the interactionHook's sendMessage method is called with the command's description
        verify(exactly = 1) { CommandTest.interactionHook.sendMessage("Return one item from a list you provide with options separated by commas.") }
    }
}

package toby.command.commands.misc

import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.ICommand.Companion.deleteAfter
import toby.jpa.dto.UserDto

class RandomCommandTest : CommandTest {
    private var randomCommand: RandomCommand? = null

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
        val listOption = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`(listOption.asString).thenReturn("Option1,Option2,Option3")

        // Mock the event's options to return the list option
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("list")).thenReturn(listOption)

        // Mock ICommand's deleteOriginal and queueAfter
        deleteAfter(CommandTest.interactionHook, 0)

        // Call the handle method with the event
        randomCommand!!.handle(
            CommandContext(CommandTest.event),
            Mockito.mock<UserDto>(UserDto::class.java),
            0
        )

        // Verify that the interactionHook's sendMessage method is called with a random option
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.anyString()) // Note: This is just an example; the actual option may vary
    }

    @Test
    fun testHandleCommandWithoutList() {
        // Mock the event's options to be empty
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf())

        // Mock ICommand's deleteOriginal and queueAfter
        deleteAfter(CommandTest.interactionHook, 0)

        // Call the handle method with the event
        randomCommand!!.handle(
            CommandContext(CommandTest.event),
            Mockito.mock<UserDto>(UserDto::class.java),
            0
        )

        // Verify that the interactionHook's sendMessage method is called with the command's description
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage("Return one item from a list you provide with options separated by commas.")
    }
}

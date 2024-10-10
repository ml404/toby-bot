package bot.toby.button.buttons

import database.dto.UserDto
import io.mockk.*
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import bot.toby.button.ButtonContext
import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.command.CommandContext
import bot.toby.command.ICommand
import bot.toby.managers.CommandManager

class ResendLastRequestButtonTest : ButtonTest {

    private lateinit var mockCommand: ICommand
    private lateinit var commandManager: CommandManager
    private lateinit var resendButton: ResendLastRequestButton
    private lateinit var commandContext: CommandContext

    @BeforeEach
    override fun setup() {
        super.setup()

        // Initialize the mock command and its methods
        mockCommand = mockk {
            every { handle(any(), any(), any()) } just Runs
        }

        // Mock CommandManager
        commandManager = mockk {
            every { lastCommands } returns mutableMapOf(
                mockk<Guild>(relaxed = true) to Pair(mockCommand, mockk())
            )
        }

        resendButton = ResendLastRequestButton(commandManager)
    }

    @AfterEach
    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
        unmockkAll()
    }

    @Test
    fun `test handle ButtonInteractionEvent with resend last request`() {
        // Set up the event and its interactions
        every { event.deferEdit().queue() } just Runs

        // Mock lastCommands to return our mocked command and context
        val userDto = UserDto(6L, 1L)
        commandContext = mockk(relaxed = true)
        every { commandManager.lastCommands[event.guild] } returns Pair(mockCommand, commandContext)

        // Invoke the handle method on the ResendLastRequestButton
        resendButton.handle(ButtonContext(event), userDto, 5)

        // Verify interactions
        verify { mockCommand.handle(commandContext, userDto, 5) }
        verify { event.deferEdit().queue() }
    }
}
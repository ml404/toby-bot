package bot.toby.button.buttons

import bot.toby.button.ButtonContext
import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.dndHelper
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.ButtonTest.Companion.mockChannel
import bot.toby.command.commands.dnd.RollCommand
import bot.toby.managers.CommandManager
import io.mockk.*
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RollButtonTest : ButtonTest {

    private lateinit var rollCommand: RollCommand
    private lateinit var commandManager: CommandManager
    private lateinit var rollButton: RollButton

    @BeforeEach
    override fun setup() {
        super.setup()

        // Initialize RollCommand and mock its methods
        rollCommand = spyk(RollCommand(dndHelper))
        every { rollCommand.handleDiceRoll(any(), any(), any(), any()) } returns mockk<WebhookMessageCreateAction<Message>> {
            every { queue(any()) } just Runs
        }

        // Mock CommandManager
        commandManager = mockk {
            every { getCommand("roll") } returns rollCommand
        }

        rollButton = RollButton(commandManager)
    }

    @AfterEach
    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
        unmockkAll()
    }

    @Test
    fun `test handle ButtonInteractionEvent with roll command`() {
        // Set up the event componentId and mocks
        every { event.componentId } returns "roll:1,2,3"
        every { event.message.delete().queue() } just Runs

        every { event.hook.sendMessageEmbeds(any<MessageEmbed>()).addActionRow(any(), any(), any(), any(), any()) } returns mockk<WebhookMessageCreateAction<Message>> {
            every { queue(any()) } just Runs
        }

        // Invoke the handle method on the RollButton
        rollButton.handle(ButtonContext(event), bot.database.dto.UserDto(6L, 1L), 5)

        // Verify interactions
        verify { mockChannel.sendTyping().queue() }
        verify { rollCommand.handleDiceRoll(event, 1, 2, 3) }
    }
}

package toby.button.buttons

import io.mockk.*
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.InteractionHook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.button.ButtonContext
import toby.button.ButtonTest
import toby.button.ButtonTest.Companion.configService
import toby.button.ButtonTest.Companion.dndHelper
import toby.button.ButtonTest.Companion.event
import toby.button.ButtonTest.Companion.userService
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.*

class InitiativeNextButtonTest : ButtonTest {


    @BeforeEach
    override fun setup() {
        super.setup()
    }

    @AfterEach
    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
        unmockkAll()
    }

    @Test
    fun `test handle ButtonInteractionEvent with init_next`() {
        // Mock the event's component ID
        every { event.componentId } returns "init:next"

        // Mock the event's message and its delete method
        val mockMessage = mockk<Message> {
            every { delete() } returns mockk {
                every { queue() } just Runs
            }
        }
        every { event.message } returns mockMessage

        // Mock the event's hook and its deleteOriginal method
        val mockHook = mockk<InteractionHook> {
            every { deleteOriginal() } returns mockk {
                every { queue() } just Runs
            }
        }
        every { event.hook } returns mockHook

        // Mock configService and userService methods
        every { configService.getConfigByName(any(), any()) } returns ConfigDto("test", "1")
        every { userService.getUserById(any(), any()) } returns mockk(relaxed = true)

        // Mock clearInitiative method to just verify the call

        every { dndHelper.incrementTurnTable(any(), any(), any()) } just Runs

        // Invoke the handler
        InitiativeNextButton(dndHelper).handle(ButtonContext(event), UserDto(6L, 1L), 0)

        // Verify expected interactions
        verify(exactly = 1) { dndHelper.incrementTurnTable(mockHook, event, 0) }
    }
}
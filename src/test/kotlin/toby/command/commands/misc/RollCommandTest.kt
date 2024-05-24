package toby.command.commands.misc

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import toby.jpa.dto.UserDto

class RollCommandTest : CommandTest {
    lateinit var rollCommand: RollCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        every { CommandTest.interactionHook.sendMessageEmbeds(any(), *anyVararg()) } returns webhookMessageCreateAction
        rollCommand = RollCommand()
    }

    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testRollCommand() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = mockk<UserDto>()
        val deleteDelay = 0
        val reroll = Button.primary("resend_last_request", "Click to Reroll")
        val rollD20 = Button.primary("roll:20,1,0", "Roll D20")
        val rollD10 = Button.primary("roll:10,1,0", "Roll D10")
        val rollD6 = Button.primary("roll:6,1,0", "Roll D6")
        val rollD4 = Button.primary("roll:4,1,0", "Roll D4")

        val number = mockk<OptionMapping>()
        val amount = mockk<OptionMapping>()
        val modifier = mockk<OptionMapping>()
        every { CommandTest.event.options } returns listOf(number, amount, modifier)
        every { CommandTest.event.getOption("number") } returns number
        every { CommandTest.event.getOption("amount") } returns amount
        every { CommandTest.event.getOption("modifier") } returns modifier
        every { number.asInt } returns 6
        every { amount.asInt } returns 1
        every { modifier.asInt } returns 0
        every {
            webhookMessageCreateAction.addActionRow(
                reroll,
                rollD20,
                rollD10,
                rollD6,
                rollD4
            )
        } returns webhookMessageCreateAction

        // Act
        rollCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify(exactly = 1) { CommandTest.event.deferReply() }
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageEmbeds(any(), *anyVararg()) }
        verify(exactly = 1) {
            webhookMessageCreateAction.addActionRow(
                reroll,
                rollD20,
                rollD10,
                rollD6,
                rollD4
            )
        }
    }

    @Test
    fun testHandleDiceRoll() {
        every { webhookMessageCreateAction.addActionRow(any(), any(), any(), any(), any()) } returns webhookMessageCreateAction

        // Call the handleDiceRoll method
        rollCommand.handleDiceRoll(CommandTest.event, 6, 1, 0)

        // Perform verifications as needed
        verify(exactly = 1) { CommandTest.event.deferReply() }
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageEmbeds(any(), *anyVararg()) }
        verify(exactly = 1) {
            webhookMessageCreateAction.addActionRow(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }
}

package toby.command.commands.misc

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import toby.jpa.dto.UserDto

class RollCommandTest : CommandTest {
    lateinit var rollCommand: RollCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        every { event.hook.sendMessageEmbeds(any(), *anyVararg()) } returns webhookMessageCreateAction
        rollCommand = RollCommand(mockk())
    }

    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testRollCommand() {
        // Arrange
        val ctx = CommandContext(event)
        val userDto = mockk<UserDto>()
        val deleteDelay = 0

        val number = mockk<OptionMapping>()
        val amount = mockk<OptionMapping>()
        val modifier = mockk<OptionMapping>()
        every { event.options } returns listOf(number, amount, modifier)
        every { event.getOption("number") } returns number
        every { event.getOption("amount") } returns amount
        every { event.getOption("modifier") } returns modifier
        every { number.asInt } returns 6
        every { amount.asInt } returns 1
        every { modifier.asInt } returns 0
        every { webhookMessageCreateAction.addActionRow(any(), any(), any(), any(), any()) } returns webhookMessageCreateAction


        // Act
        rollCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 1) { event.hook.sendMessageEmbeds(any(), *anyVararg()) }
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

    @Test
    fun testHandleDiceRoll() {
        every { webhookMessageCreateAction.addActionRow(any(), any(), any(), any(), any()) } returns webhookMessageCreateAction

        // Call the handleDiceRoll method
        rollCommand.handleDiceRoll(event, 6, 1, 0)

        // Perform verifications as needed
        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 1) { event.hook.sendMessageEmbeds(any(), *anyVararg()) }
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

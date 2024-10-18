package bot.toby.command.commands.dnd

import bot.toby.command.CommandContextImpl
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.UserDtoHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RollCommandTest : CommandTest {
    private lateinit var rollCommand: RollCommand

    lateinit var userDtoHelper: UserDtoHelper
    lateinit var dndHelper: DnDHelper

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        userDtoHelper = mockk()
        dndHelper = DnDHelper(userDtoHelper)
        every { event.hook.sendMessageEmbeds(any(), *anyVararg()) } returns webhookMessageCreateAction
        rollCommand = RollCommand(dndHelper)
    }

    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testRollCommand() {
        // Arrange
        val ctx = CommandContextImpl(event)
        val userDto = mockk<database.dto.UserDto>()
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

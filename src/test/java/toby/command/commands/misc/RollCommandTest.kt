package toby.command.commands.misc

import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.kotlin.anyVararg
import toby.command.CommandContext
import toby.command.CommandTest
import toby.jpa.dto.UserDto

class RollCommandTest : CommandTest {
    private lateinit var rollCommand: RollCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        // Customize the behavior of sendMessageEmbeds
        Mockito.doReturn(CommandTest.webhookMessageCreateAction)
            .`when`(CommandTest.interactionHook)
            .sendMessageEmbeds(
                ArgumentMatchers.any(), anyVararg()

            )


        rollCommand = RollCommand()
    }

    fun tearDown() {
        tearDownCommonMocks()
    }


    @Test
    fun testRollCommand() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0
        val reroll = Button.primary("resend_last_request", "Click to Reroll")
        val rollD20 = Button.primary("roll" + ":" + "20, 1, 0", "Roll D20")
        val rollD10 = Button.primary("roll" + ":" + "10, 1, 0", "Roll D10")
        val rollD6 = Button.primary("roll" + ":" + "6, 1, 0", "Roll D6")
        val rollD4 = Button.primary("roll" + ":" + "4, 1, 0", "Roll D4")


        val number = Mockito.mock(OptionMapping::class.java)
        val amount = Mockito.mock(OptionMapping::class.java)
        val modifier = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`(CommandTest.event.options).thenReturn(listOf(number, amount, modifier))
        Mockito.`when`(CommandTest.event.getOption("number")).thenReturn(number)
        Mockito.`when`(CommandTest.event.getOption("amount")).thenReturn(amount)
        Mockito.`when`(CommandTest.event.getOption("modifier")).thenReturn(modifier)
        Mockito.`when`(number.asInt).thenReturn(6)
        Mockito.`when`(amount.asInt).thenReturn(1)
        Mockito.`when`(modifier.asInt).thenReturn(0)
        Mockito.`when`(CommandTest.webhookMessageCreateAction.addActionRow(reroll, rollD20, rollD10, rollD6, rollD4))
            .thenReturn(CommandTest.webhookMessageCreateAction)

        // Act
        rollCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        Mockito.verify(CommandTest.event, Mockito.times(1)).deferReply()
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(
            ArgumentMatchers.any(), anyVararg()

        )
        Mockito.verify(CommandTest.webhookMessageCreateAction, Mockito.times(1))?.addActionRow(
            ArgumentMatchers.eq(reroll),
            ArgumentMatchers.eq(rollD20),
            ArgumentMatchers.eq(rollD10),
            ArgumentMatchers.eq(rollD6),
            ArgumentMatchers.eq(rollD4)
        )
    }

    @Test
    fun testHandleDiceRoll() {
        // Call the handleDiceRoll method
        rollCommand.handleDiceRoll(CommandTest.event, 6, 1, 0)

        // Perform verifications as needed
        Mockito.verify(CommandTest.event, Mockito.times(1)).deferReply()
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(ArgumentMatchers.any(), anyVararg())
        Mockito.verify(CommandTest.webhookMessageCreateAction, Mockito.times(1))?.addActionRow(
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any()
        )
    }
}

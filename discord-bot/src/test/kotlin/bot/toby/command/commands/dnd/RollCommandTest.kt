package bot.toby.command.commands.dnd

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.UserDtoHelper
import com.fasterxml.jackson.databind.ObjectMapper
import common.events.CampaignEventOccurred
import common.events.CampaignEventType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class RollCommandTest : CommandTest {
    private lateinit var rollCommand: RollCommand

    lateinit var userDtoHelper: UserDtoHelper
    lateinit var dndHelper: DnDHelper
    lateinit var applicationEventPublisher: ApplicationEventPublisher
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        userDtoHelper = mockk()
        dndHelper = DnDHelper(userDtoHelper)
        applicationEventPublisher = mockk(relaxed = true)
        every { event.hook.sendMessageEmbeds(any(), *anyVararg()) } returns webhookMessageCreateAction
        rollCommand = RollCommand(dndHelper, applicationEventPublisher, objectMapper)
    }

    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testRollCommand() {
        // Arrange
        val ctx = DefaultCommandContext(event)
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
        every { webhookMessageCreateAction.addComponents(any<ActionRow>()) } returns webhookMessageCreateAction


        // Act
        rollCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 1) { event.hook.sendMessageEmbeds(any(), *anyVararg()) }
        verify(exactly = 1) {
            webhookMessageCreateAction.addComponents(any<ActionRow>())
        }
    }

    @Test
    fun testHandleDiceRoll() {
        every { webhookMessageCreateAction.addComponents(any<ActionRow>()) } returns webhookMessageCreateAction

        // Call the handleDiceRoll method
        rollCommand.handleDiceRoll(event, 6, 1, 0)

        // Perform verifications as needed
        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 1) { event.hook.sendMessageEmbeds(any(), *anyVararg()) }
        verify(exactly = 1) {
            webhookMessageCreateAction.addComponents(any<ActionRow>())
        }
    }

    @Test
    fun testRollPublishesCampaignEvent() {
        every { webhookMessageCreateAction.addComponents(any<ActionRow>()) } returns webhookMessageCreateAction

        rollCommand.handleDiceRoll(event, 20, 2, 3)

        verify(exactly = 1) {
            applicationEventPublisher.publishEvent(match<CampaignEventOccurred> {
                it.type == CampaignEventType.ROLL &&
                    it.guildId == 1L &&
                    it.actorDiscordId == 1L
            })
        }
    }

    @Test
    fun testRollSkipsPublishWhenNoGuild() {
        every { webhookMessageCreateAction.addComponents(any<ActionRow>()) } returns webhookMessageCreateAction
        every { event.guild } returns null

        rollCommand.handleDiceRoll(event, 20, 1, 0)

        verify(exactly = 0) { applicationEventPublisher.publishEvent(any<CampaignEventOccurred>()) }
    }

    @Test
    fun testRollPayloadShape() {
        every { webhookMessageCreateAction.addComponents(any<ActionRow>()) } returns webhookMessageCreateAction

        rollCommand.handleDiceRoll(event, 6, 3, 2)

        verify(exactly = 1) {
            applicationEventPublisher.publishEvent(match<CampaignEventOccurred> { published ->
                val payload = objectMapper.readValue(
                    published.payloadJson, Map::class.java
                ) as Map<String, Any?>
                assertEquals(6, payload["sides"])
                assertEquals(3, payload["count"])
                assertEquals(2, payload["modifier"])
                val raw = payload["rawTotal"] as Int
                val total = payload["total"] as Int
                assertTrue(raw in 3..18)
                assertEquals(raw + 2, total)
                true
            })
        }
    }
}

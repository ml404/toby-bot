package bot.toby.command.commands.dnd

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.UserDtoHelper
import web.service.InitiativeResolver
import common.events.CampaignEventType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.service.SessionLogPublisher

class RollCommandTest : CommandTest {
    private lateinit var rollCommand: RollCommand

    lateinit var userDtoHelper: UserDtoHelper
    lateinit var dndHelper: DnDHelper
    lateinit var sessionLog: SessionLogPublisher

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        userDtoHelper = mockk()
        dndHelper = DnDHelper(userDtoHelper, mockk<InitiativeResolver>(relaxed = true))
        sessionLog = mockk(relaxed = true)
        every { event.hook.sendMessageEmbeds(any(), *anyVararg()) } returns webhookMessageCreateAction
        rollCommand = RollCommand(dndHelper, sessionLog)
    }

    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testRollCommand() {
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

        rollCommand.handle(ctx, userDto, deleteDelay)

        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 1) { event.hook.sendMessageEmbeds(any(), *anyVararg()) }
        verify(exactly = 1) {
            webhookMessageCreateAction.addComponents(any<ActionRow>())
        }
    }

    @Test
    fun testHandleDiceRoll() {
        every { webhookMessageCreateAction.addComponents(any<ActionRow>()) } returns webhookMessageCreateAction

        rollCommand.handleDiceRoll(event, 6, 1, 0)

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
            sessionLog.publish(
                guildId = 1L,
                type = CampaignEventType.ROLL,
                actorDiscordId = 1L,
                actorName = any(),
                payload = any(),
                refEventId = null
            )
        }
    }

    @Test
    fun testRollSkipsPublishWhenNoGuild() {
        every { webhookMessageCreateAction.addComponents(any<ActionRow>()) } returns webhookMessageCreateAction
        every { event.guild } returns null

        rollCommand.handleDiceRoll(event, 20, 1, 0)

        verify(exactly = 0) { sessionLog.publish(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun testRollPayloadShape() {
        every { webhookMessageCreateAction.addComponents(any<ActionRow>()) } returns webhookMessageCreateAction
        val captured = slot<Map<String, Any?>>()
        every {
            sessionLog.publish(
                guildId = any(),
                type = CampaignEventType.ROLL,
                actorDiscordId = any(),
                actorName = any(),
                payload = capture(captured),
                refEventId = any()
            )
        } returns Unit

        rollCommand.handleDiceRoll(event, 6, 3, 2)

        val payload = captured.captured
        assertEquals(6, payload["sides"])
        assertEquals(3, payload["count"])
        assertEquals(2, payload["modifier"])
        val raw = payload["rawTotal"] as Int
        val total = payload["total"] as Int
        assertTrue(raw in 3..18)
        assertEquals(raw + 2, total)
    }
}

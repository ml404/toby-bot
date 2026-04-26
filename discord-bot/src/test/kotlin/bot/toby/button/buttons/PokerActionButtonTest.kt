package bot.toby.button.buttons

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.ButtonTest.Companion.mockGuild
import bot.toby.button.ButtonTest.Companion.mockHook
import bot.toby.button.DefaultButtonContext
import bot.toby.command.commands.economy.PokerEmbeds
import database.dto.UserDto
import database.poker.PokerEngine
import database.poker.PokerTable
import database.poker.PokerTableRegistry
import database.service.PokerService
import database.service.PokerService.ActionOutcome
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PokerActionButtonTest : ButtonTest {

    private lateinit var pokerService: PokerService
    private lateinit var tableRegistry: PokerTableRegistry
    private lateinit var button: PokerActionButton

    private val seatedId = 100L
    private val guildId = 1L
    private val tableId = 7L

    private lateinit var message: Message

    @BeforeEach
    override fun setup() {
        super.setup()
        every { mockGuild.idLong } returns guildId

        pokerService = mockk(relaxed = true)
        tableRegistry = mockk(relaxed = true)
        button = PokerActionButton(pokerService, tableRegistry)

        message = mockk(relaxed = true)
        every { event.message } returns message

        val edit = mockk<net.dv8tion.jda.api.requests.restaction.MessageEditAction>(relaxed = true)
        every { message.editMessageEmbeds(any<MessageEmbed>()) } returns edit
        every { edit.setComponents(any<Collection<MessageTopLevelComponent>>()) } returns edit
        every { edit.setComponents(*anyVararg<MessageTopLevelComponent>()) } returns edit
        every { edit.queue() } just Runs

        val send = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { mockHook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) } returns send
        every { send.setEphemeral(any()) } returns send
        every { send.queue() } just Runs

        val reply = mockk<net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction>(relaxed = true)
        every { event.reply(any<String>()) } returns reply
        every { reply.setEphemeral(any()) } returns reply
        every { reply.queue() } just Runs
        every { event.replyEmbeds(any<MessageEmbed>(), *anyVararg()) } returns reply

        val deferEdit = mockk<net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction>(relaxed = true)
        every { event.deferEdit() } returns deferEdit
        every { deferEdit.queue() } just Runs
    }

    @AfterEach
    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
    }

    private fun stubTable(seatChips: List<Pair<Long, Long>> = listOf(seatedId to 1000L)): PokerTable {
        return PokerTable(
            id = tableId,
            guildId = guildId,
            hostDiscordId = seatedId,
            minBuyIn = 100L,
            maxBuyIn = 5000L,
            smallBlind = 5L,
            bigBlind = 10L,
            smallBet = 10L,
            bigBet = 20L,
            maxRaisesPerStreet = 4,
            maxSeats = 6,
        ).also { t ->
            seatChips.forEach { (id, chips) ->
                t.seats.add(PokerTable.Seat(discordId = id, chips = chips))
            }
        }
    }

    @Test
    fun `unparseable component id sends ephemeral error and does nothing`() {
        every { event.componentId } returns "garbage"

        button.handle(DefaultButtonContext(event), UserDto(seatedId, guildId), 0)

        verify(exactly = 0) { pokerService.applyAction(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing table sends ephemeral error and does not call service`() {
        every { event.componentId } returns PokerEmbeds.buttonId(PokerEmbeds.Action.FOLD, tableId)
        every { tableRegistry.get(tableId) } returns null

        button.handle(DefaultButtonContext(event), UserDto(seatedId, guildId), 0)

        verify(exactly = 0) { pokerService.applyAction(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unseated user trying to fold gets ephemeral reject`() {
        every { event.componentId } returns PokerEmbeds.buttonId(PokerEmbeds.Action.FOLD, tableId)
        every { tableRegistry.get(tableId) } returns stubTable(seatChips = listOf(999L to 1000L))

        button.handle(DefaultButtonContext(event), UserDto(seatedId, guildId), 0)

        verify(exactly = 0) { pokerService.applyAction(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `peek replies ephemerally with the seat hole cards and does not touch service`() {
        every { event.componentId } returns PokerEmbeds.buttonId(PokerEmbeds.Action.PEEK, tableId)
        every { tableRegistry.get(tableId) } returns stubTable()

        button.handle(DefaultButtonContext(event), UserDto(seatedId, guildId), 0)

        verify(exactly = 0) { pokerService.applyAction(any(), any(), any(), any(), any()) }
        verify { event.replyEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }

    @Test
    fun `peek by unseated user sends ephemeral error`() {
        every { event.componentId } returns PokerEmbeds.buttonId(PokerEmbeds.Action.PEEK, tableId)
        every { tableRegistry.get(tableId) } returns stubTable(seatChips = listOf(999L to 1000L))

        button.handle(DefaultButtonContext(event), UserDto(seatedId, guildId), 0)

        verify(exactly = 0) { pokerService.applyAction(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `check_call resolves to Call when seat owes money`() {
        every { event.componentId } returns PokerEmbeds.buttonId(PokerEmbeds.Action.CHECK_CALL, tableId)
        val table = stubTable()
        // Seat owes 10 (currentBet 10, committed 0).
        table.currentBet = 10L
        every { tableRegistry.get(tableId) } returns table
        every {
            pokerService.applyAction(seatedId, guildId, tableId, PokerEngine.PokerAction.Call, any())
        } returns ActionOutcome.Continued

        button.handle(DefaultButtonContext(event), UserDto(seatedId, guildId), 0)

        verify(exactly = 1) {
            pokerService.applyAction(seatedId, guildId, tableId, PokerEngine.PokerAction.Call, any())
        }
    }

    @Test
    fun `check_call resolves to Check when seat is square`() {
        every { event.componentId } returns PokerEmbeds.buttonId(PokerEmbeds.Action.CHECK_CALL, tableId)
        val table = stubTable()
        table.currentBet = 10L
        table.seats[0].committedThisRound = 10L
        every { tableRegistry.get(tableId) } returns table
        every {
            pokerService.applyAction(seatedId, guildId, tableId, PokerEngine.PokerAction.Check, any())
        } returns ActionOutcome.Continued

        button.handle(DefaultButtonContext(event), UserDto(seatedId, guildId), 0)

        verify(exactly = 1) {
            pokerService.applyAction(seatedId, guildId, tableId, PokerEngine.PokerAction.Check, any())
        }
    }

    @Test
    fun `fold dispatches Fold and edits the message in place`() {
        every { event.componentId } returns PokerEmbeds.buttonId(PokerEmbeds.Action.FOLD, tableId)
        every { tableRegistry.get(tableId) } returns stubTable()
        every {
            pokerService.applyAction(seatedId, guildId, tableId, PokerEngine.PokerAction.Fold, any())
        } returns ActionOutcome.Continued

        button.handle(DefaultButtonContext(event), UserDto(seatedId, guildId), 0)

        verify(exactly = 1) {
            pokerService.applyAction(seatedId, guildId, tableId, PokerEngine.PokerAction.Fold, any())
        }
        verify { message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `service rejection surfaces ephemeral error without editing the message`() {
        every { event.componentId } returns PokerEmbeds.buttonId(PokerEmbeds.Action.FOLD, tableId)
        every { tableRegistry.get(tableId) } returns stubTable()
        every {
            pokerService.applyAction(seatedId, guildId, tableId, PokerEngine.PokerAction.Fold, any())
        } returns ActionOutcome.Rejected(PokerEngine.RejectReason.NOT_YOUR_TURN)

        button.handle(DefaultButtonContext(event), UserDto(seatedId, guildId), 0)

        // Hook send was used for ephemeral error (not message edit).
        verify(exactly = 0) { message.editMessageEmbeds(any<MessageEmbed>()) }
        verify { mockHook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }

    @Test
    fun `hand resolved edits the message with result and clears components`() {
        every { event.componentId } returns PokerEmbeds.buttonId(PokerEmbeds.Action.FOLD, tableId)
        val table = stubTable(seatChips = listOf(seatedId to 1000L, 200L to 1000L))
        every { tableRegistry.get(tableId) } returns table
        val result = PokerTable.HandResult(
            handNumber = 1L,
            winners = listOf(seatedId),
            payoutByDiscordId = mapOf(seatedId to 95L),
            pot = 100L,
            rake = 5L,
            board = emptyList(),
            revealedHoleCards = emptyMap(),
            resolvedAt = java.time.Instant.now()
        )
        every {
            pokerService.applyAction(seatedId, guildId, tableId, PokerEngine.PokerAction.Fold, any())
        } returns ActionOutcome.HandResolved(result)

        button.handle(DefaultButtonContext(event), UserDto(seatedId, guildId), 0)

        verify { message.editMessageEmbeds(any<MessageEmbed>()) }
    }
}

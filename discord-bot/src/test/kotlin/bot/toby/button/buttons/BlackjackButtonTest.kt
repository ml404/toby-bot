package bot.toby.button.buttons

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.ButtonTest.Companion.mockGuild
import bot.toby.button.DefaultButtonContext
import bot.toby.command.commands.economy.BlackjackEmbeds
import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.dto.UserDto
import database.service.BlackjackService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class BlackjackButtonTest : ButtonTest {

    private lateinit var service: BlackjackService
    private lateinit var registry: BlackjackTableRegistry
    private lateinit var button: BlackjackButton
    private lateinit var editAction: MessageEditCallbackAction
    private lateinit var replyAction: ReplyCallbackAction

    private val ownerId = 6L
    private val guildId = 1L

    @BeforeEach
    override fun setup() {
        super.setup()
        every { mockGuild.idLong } returns guildId

        service = mockk(relaxed = true)
        registry = mockk(relaxed = true)
        button = BlackjackButton(service, registry)

        editAction = mockk(relaxed = true)
        every { editAction.setComponents(any<Collection<MessageTopLevelComponent>>()) } returns editAction
        every { editAction.setComponents(*anyVararg<MessageTopLevelComponent>()) } returns editAction
        every { editAction.queue() } just Runs
        every { event.editMessageEmbeds(any<MessageEmbed>()) } returns editAction
        every { event.message.editMessageEmbeds(any<MessageEmbed>()) } returns editAction
        every { event.deferEdit().queue() } just Runs

        replyAction = mockk(relaxed = true)
        every { replyAction.setEphemeral(any()) } returns replyAction
        every { replyAction.queue() } just Runs
        every { event.reply(any<String>()) } returns replyAction
        every { event.replyEmbeds(any<MessageEmbed>()) } returns replyAction
    }

    @AfterEach
    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
    }

    private fun soloTable(seatOwner: Long = ownerId): BlackjackTable {
        val t = BlackjackTable(
            id = 7L,
            guildId = guildId,
            mode = BlackjackTable.Mode.SOLO,
            hostDiscordId = seatOwner,
            ante = 50L,
            maxSeats = 1
        )
        t.seats.add(BlackjackTable.Seat(discordId = seatOwner, ante = 50L, stake = 50L))
        return t
    }

    private fun multiTable(): BlackjackTable {
        val t = BlackjackTable(
            id = 8L,
            guildId = guildId,
            mode = BlackjackTable.Mode.MULTI,
            hostDiscordId = ownerId,
            ante = 100L,
            maxSeats = 5
        )
        t.seats.add(BlackjackTable.Seat(discordId = ownerId, ante = 100L, stake = 100L))
        t.seats.add(BlackjackTable.Seat(discordId = 99L, ante = 100L, stake = 100L))
        return t
    }

    @Test
    fun `solo HIT routes to applySoloAction and re-renders the embed`() {
        val table = soloTable()
        every { registry.get(table.id) } returns table
        every { event.componentId } returns BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.HIT, table.id)
        every {
            service.applySoloAction(ownerId, guildId, table.id, Blackjack.Action.HIT)
        } returns BlackjackService.SoloActionOutcome.Continued(table)

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 1) {
            service.applySoloAction(ownerId, guildId, table.id, Blackjack.Action.HIT)
        }
        verify { event.message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `solo Resolved strips components and closes the table`() {
        val table = soloTable()
        every { registry.get(table.id) } returns table
        every { event.componentId } returns BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.STAND, table.id)
        val result = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = emptyList(),
            dealerTotal = 19,
            seatResults = mapOf(ownerId to Blackjack.Result.PLAYER_WIN),
            payouts = mapOf(ownerId to 100L),
            pot = 50L,
            rake = 0L,
            resolvedAt = Instant.now()
        )
        every {
            service.applySoloAction(ownerId, guildId, table.id, Blackjack.Action.STAND)
        } returns BlackjackService.SoloActionOutcome.Resolved(
            tableId = table.id,
            result = result,
            newBalance = 1_050L,
            jackpotPayout = 0L,
            lossTribute = 0L
        )

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify { event.message.editMessageEmbeds(any<MessageEmbed>()) }
        verify { editAction.setComponents(any<Collection<MessageTopLevelComponent>>()) }
        verify { service.closeSoloTable(table.id) }
    }

    @Test
    fun `solo NotYourHand replies ephemerally without driving the service`() {
        val table = soloTable(seatOwner = ownerId)
        every { registry.get(table.id) } returns table
        every { event.componentId } returns BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.HIT, table.id)

        button.handle(DefaultButtonContext(event), UserDto(999L, guildId), 0)

        verify(exactly = 0) {
            service.applySoloAction(any(), any(), any(), any())
        }
        verify { event.reply(any<String>()) }
        verify { replyAction.setEphemeral(true) }
    }

    @Test
    fun `multi HIT routes to applyMultiAction`() {
        val table = multiTable()
        every { registry.get(table.id) } returns table
        every { event.componentId } returns BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.HIT, table.id)
        every {
            service.applyMultiAction(ownerId, guildId, table.id, Blackjack.Action.HIT)
        } returns BlackjackService.MultiActionOutcome.Continued(table)

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 1) {
            service.applyMultiAction(ownerId, guildId, table.id, Blackjack.Action.HIT)
        }
    }

    @Test
    fun `peek action sends ephemeral hand without driving the service`() {
        val table = multiTable()
        every { registry.get(table.id) } returns table
        every { event.componentId } returns BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.PEEK, table.id)

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 0) {
            service.applyMultiAction(any(), any(), any(), any())
        }
        verify { event.replyEmbeds(any<MessageEmbed>()) }
        verify { replyAction.setEphemeral(true) }
    }

    @Test
    fun `unknown table id is rejected ephemerally`() {
        every { registry.get(any()) } returns null
        every { event.componentId } returns BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.HIT, 9999L)

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify { event.reply(any<String>()) }
        verify { replyAction.setEphemeral(true) }
    }

    @Test
    fun `malformed component id is acked without resolving`() {
        every { event.componentId } returns "blackjack:bogus"

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 0) {
            service.applySoloAction(any(), any(), any(), any())
            service.applyMultiAction(any(), any(), any(), any())
        }
    }
}

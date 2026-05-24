package bot.toby.button.buttons.pvp.connect4

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.ButtonTest.Companion.mockGuild
import bot.toby.button.ButtonTest.Companion.mockHook
import bot.toby.button.DefaultButtonContext
import bot.toby.command.commands.game.pvp.connect4.Connect4Embeds
import common.connect4.Connect4Engine
import database.boardgame.TurnBasedBoardWagerService
import database.connect4.Connect4SessionRegistry
import database.dto.UserDto
import database.service.pvp.connect4.Connect4Service
import database.service.pvp.PvpWagerService
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
import java.time.Instant
import bot.toby.button.buttons.pvp.connect4.Connect4Button

class Connect4ButtonTest : ButtonTest {

    private lateinit var connect4Service: Connect4Service
    private lateinit var registry: Connect4SessionRegistry
    private lateinit var button: Connect4Button

    private val initiatorId = 1L
    private val opponentId = 2L
    private val guildId = 1L
    private val stake = 50L
    private val sessionId = 7L

    private lateinit var message: Message

    @BeforeEach
    override fun setup() {
        super.setup()
        every { mockGuild.idLong } returns guildId

        connect4Service = mockk(relaxed = true)
        registry = mockk(relaxed = true)
        button = Connect4Button(connect4Service, registry)

        message = mockk(relaxed = true)
        every { event.message } returns message

        val edit = mockk<net.dv8tion.jda.api.requests.restaction.MessageEditAction>(relaxed = true)
        every { message.editMessageEmbeds(any<MessageEmbed>()) } returns edit
        every { edit.setComponents(any<Collection<MessageTopLevelComponent>>()) } returns edit
        every { edit.setComponents(*anyVararg<MessageTopLevelComponent>()) } returns edit
        every { edit.queue() } just Runs

        val send = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { mockHook.sendMessage(any<String>()) } returns send
        every { send.setEphemeral(any()) } returns send
        every { send.queue() } just Runs
    }

    @AfterEach
    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
    }

    private fun pendingSession() = Connect4SessionRegistry.Session(
        id = sessionId, guildId = guildId,
        initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
        stake = stake, createdAt = Instant.now(),
    )

    private fun liveSession() = pendingSession().also {
        it.state = database.pvp.PvpSessionRegistry.Session.State.LIVE
    }

    // ---- DECLINE ----

    @Test
    fun `non-opponent decline gets ephemeral reject`() {
        every { event.componentId } returns Connect4Embeds.declineButtonId(sessionId, opponentId)
        button.handle(DefaultButtonContext(event), UserDto(999L, guildId), 0)
        verify(exactly = 0) { registry.decline(any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    @Test
    fun `opponent decline removes the session and edits the message`() {
        every { event.componentId } returns Connect4Embeds.declineButtonId(sessionId, opponentId)
        every { registry.decline(sessionId) } returns pendingSession()

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 1) { registry.decline(sessionId) }
        verify(exactly = 1) { message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    // ---- ACCEPT ----

    @Test
    fun `opponent accept transitions to LIVE and posts turn embed`() {
        every { event.componentId } returns Connect4Embeds.acceptButtonId(sessionId, opponentId)
        every { registry.accept(sessionId, any()) } returns liveSession()
        every {
            connect4Service.acceptMatch(initiatorId, opponentId, guildId, stake)
        } returns PvpWagerService.AcceptOutcome.Ok(initiatorNewBalance = 100L, opponentNewBalance = 100L)

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 1) { registry.accept(sessionId, any()) }
        verify(exactly = 1) { connect4Service.acceptMatch(initiatorId, opponentId, guildId, stake) }
        verify(exactly = 1) { message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `accept failure on insufficient balance tears down the live session`() {
        every { event.componentId } returns Connect4Embeds.acceptButtonId(sessionId, opponentId)
        every { registry.accept(sessionId, any()) } returns liveSession()
        every {
            connect4Service.acceptMatch(initiatorId, opponentId, guildId, stake)
        } returns PvpWagerService.AcceptOutcome.OpponentInsufficient(have = 10L, needed = 50L)

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 1) { registry.forfeit(sessionId) }
        verify(exactly = 1) { message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `accept on an already-resolved session sends ephemeral`() {
        every { event.componentId } returns Connect4Embeds.acceptButtonId(sessionId, opponentId)
        every { registry.accept(sessionId, any()) } returns null

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 0) { connect4Service.acceptMatch(any(), any(), any(), any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    // ---- DROP ----

    @Test
    fun `drop by a non-player is rejected ephemerally`() {
        every { event.componentId } returns Connect4Embeds.dropButtonId(sessionId, column = 0)
        every { registry.get(sessionId) } returns liveSession()

        button.handle(DefaultButtonContext(event), UserDto(999L, guildId), 0)

        verify(exactly = 0) { registry.applyMove(any(), any(), any(), any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    @Test
    fun `drop on a continued move re-renders the board`() {
        val live = liveSession()
        every { event.componentId } returns Connect4Embeds.dropButtonId(sessionId, column = 3)
        every { registry.get(sessionId) } returns live
        every { registry.applyMove(sessionId, initiatorId, 3, any()) } returns
            Connect4Engine.MoveResult.Continued(Connect4Engine.empty(), droppedRow = 5)

        button.handle(DefaultButtonContext(event), UserDto(initiatorId, guildId), 0)

        verify(exactly = 1) { registry.applyMove(sessionId, initiatorId, 3, any()) }
        verify(exactly = 0) { registry.consumeForResolution(any()) }
        verify(exactly = 0) { connect4Service.resolveMatch(any(), any(), any(), any(), any()) }
        verify(atLeast = 1) { message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `drop that lands a Win drains and resolves with the engine winner`() {
        val live = liveSession().also { it.winner = Connect4Engine.Mark.RED }
        every { event.componentId } returns Connect4Embeds.dropButtonId(sessionId, column = 3)
        every { registry.get(sessionId) } returns live
        every { registry.applyMove(sessionId, initiatorId, 3, any()) } returns
            Connect4Engine.MoveResult.Win(Connect4Engine.empty(), Connect4Engine.Mark.RED, listOf(35, 36, 37, 38), droppedRow = 5)
        every { registry.consumeForResolution(sessionId) } returns live
        every {
            connect4Service.resolveMatch(initiatorId, opponentId, guildId, stake, initiatorId)
        } returns TurnBasedBoardWagerService.ResolveOutcome.Win(
            winnerDiscordId = initiatorId, loserDiscordId = opponentId,
            stake = stake, pot = 2 * stake,
            winnerNewBalance = 150L, loserNewBalance = 50L,
            lossTribute = 0L, xpGranted = 10L,
        )

        button.handle(DefaultButtonContext(event), UserDto(initiatorId, guildId), 0)

        verify(exactly = 1) { registry.consumeForResolution(sessionId) }
        verify(exactly = 1) {
            connect4Service.resolveMatch(initiatorId, opponentId, guildId, stake, initiatorId)
        }
    }

    @Test
    fun `drop that lands a Draw drains and resolves with null winner`() {
        val live = liveSession() // winner stays null
        every { event.componentId } returns Connect4Embeds.dropButtonId(sessionId, column = 6)
        every { registry.get(sessionId) } returns live
        every { registry.applyMove(sessionId, initiatorId, 6, any()) } returns
            Connect4Engine.MoveResult.Draw(Connect4Engine.empty())
        every { registry.consumeForResolution(sessionId) } returns live
        every {
            connect4Service.resolveMatch(initiatorId, opponentId, guildId, stake, null)
        } returns TurnBasedBoardWagerService.ResolveOutcome.Draw(
            stake = stake, initiatorNewBalance = stake, opponentNewBalance = stake,
        )

        button.handle(DefaultButtonContext(event), UserDto(initiatorId, guildId), 0)

        verify(exactly = 1) {
            connect4Service.resolveMatch(initiatorId, opponentId, guildId, stake, null)
        }
    }

    @Test
    fun `drop on a full column sends ephemeral and does not edit board`() {
        val live = liveSession()
        every { event.componentId } returns Connect4Embeds.dropButtonId(sessionId, column = 0)
        every { registry.get(sessionId) } returns live
        every { registry.applyMove(sessionId, initiatorId, 0, any()) } returns Connect4Engine.MoveResult.ColumnFull

        button.handle(DefaultButtonContext(event), UserDto(initiatorId, guildId), 0)

        verify(exactly = 0) { message.editMessageEmbeds(any<MessageEmbed>()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    @Test
    fun `drop by the wrong player (not their turn) sends ephemeral`() {
        val live = liveSession()
        every { event.componentId } returns Connect4Embeds.dropButtonId(sessionId, column = 0)
        every { registry.get(sessionId) } returns live
        every { registry.applyMove(sessionId, opponentId, 0, any()) } returns null

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 0) { registry.consumeForResolution(any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    // ---- FORFEIT ----

    @Test
    fun `forfeit by a player removes the session and resolves the opponent as winner`() {
        every { event.componentId } returns Connect4Embeds.forfeitButtonId(sessionId)
        val live = liveSession()
        every { registry.get(sessionId) } returns live
        every { registry.forfeit(sessionId) } returns live
        every {
            connect4Service.resolveMatch(initiatorId, opponentId, guildId, stake, opponentId)
        } returns TurnBasedBoardWagerService.ResolveOutcome.Win(
            winnerDiscordId = opponentId, loserDiscordId = initiatorId,
            stake = stake, pot = 2 * stake,
            winnerNewBalance = 250L, loserNewBalance = 0L,
            lossTribute = 0L, xpGranted = 10L,
        )

        // Initiator forfeits → opponent wins.
        button.handle(DefaultButtonContext(event), UserDto(initiatorId, guildId), 0)

        verify(exactly = 1) { registry.forfeit(sessionId) }
        verify(exactly = 1) {
            connect4Service.resolveMatch(initiatorId, opponentId, guildId, stake, opponentId)
        }
    }

    @Test
    fun `forfeit by a non-player is rejected`() {
        every { event.componentId } returns Connect4Embeds.forfeitButtonId(sessionId)
        every { registry.get(sessionId) } returns liveSession()

        button.handle(DefaultButtonContext(event), UserDto(999L, guildId), 0)

        verify(exactly = 0) { registry.forfeit(any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    @Test
    fun `unparseable component id sends ephemeral`() {
        every { event.componentId } returns "garbage"
        button.handle(DefaultButtonContext(event), UserDto(initiatorId, guildId), 0)
        verify { mockHook.sendMessage(any<String>()) }
    }
}

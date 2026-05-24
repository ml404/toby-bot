package bot.toby.button.buttons

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.ButtonTest.Companion.mockGuild
import bot.toby.button.ButtonTest.Companion.mockHook
import bot.toby.button.DefaultButtonContext
import bot.toby.command.commands.game.pvp.rps.RpsEmbeds
import database.dto.UserDto
import common.rps.RpsEngine
import database.rps.RpsSessionRegistry
import database.service.pvp.PvpWagerService
import database.service.pvp.rps.RpsService
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

class RpsButtonTest : ButtonTest {

    private lateinit var rpsService: RpsService
    private lateinit var registry: RpsSessionRegistry
    private lateinit var button: RpsButton

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

        rpsService = mockk(relaxed = true)
        registry = mockk(relaxed = true)
        button = RpsButton(rpsService, registry)

        message = mockk(relaxed = true)
        every { event.message } returns message

        // Wire the edit-message chain.
        val edit = mockk<net.dv8tion.jda.api.requests.restaction.MessageEditAction>(relaxed = true)
        every { message.editMessageEmbeds(any<MessageEmbed>()) } returns edit
        every { edit.setComponents(any<Collection<MessageTopLevelComponent>>()) } returns edit
        every { edit.setComponents(*anyVararg<MessageTopLevelComponent>()) } returns edit
        every { edit.queue() } just Runs

        // Hook send for ephemeral replies.
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

    private fun pendingSession() = RpsSessionRegistry.Session(
        id = sessionId, guildId = guildId,
        initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
        stake = stake, createdAt = Instant.now(),
    )

    private fun liveSession() = pendingSession().also { it.state = database.pvp.PvpSessionRegistry.Session.State.LIVE }

    // ---- DECLINE ----

    @Test
    fun `non-opponent click on decline gets ephemeral reject`() {
        every { event.componentId } returns RpsEmbeds.declineButtonId(sessionId, opponentId)

        button.handle(DefaultButtonContext(event), UserDto(999L, guildId), 0)

        verify(exactly = 0) { registry.decline(any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    @Test
    fun `opponent decline removes the session and edits the message`() {
        every { event.componentId } returns RpsEmbeds.declineButtonId(sessionId, opponentId)
        every { registry.decline(sessionId) } returns pendingSession()

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 1) { registry.decline(sessionId) }
        verify(exactly = 1) { message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    // ---- ACCEPT ----

    @Test
    fun `opponent accept transitions to LIVE and posts pick embed`() {
        every { event.componentId } returns RpsEmbeds.acceptButtonId(sessionId, opponentId)
        every { registry.accept(sessionId, any()) } returns liveSession()
        every {
            rpsService.acceptMatch(initiatorId, opponentId, guildId, stake)
        } returns PvpWagerService.AcceptOutcome.Ok(initiatorNewBalance = 100L, opponentNewBalance = 100L)

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 1) { registry.accept(sessionId, any()) }
        verify(exactly = 1) { rpsService.acceptMatch(initiatorId, opponentId, guildId, stake) }
        verify(exactly = 1) { message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `accept failure on insufficient balance tears down the live session`() {
        every { event.componentId } returns RpsEmbeds.acceptButtonId(sessionId, opponentId)
        every { registry.accept(sessionId, any()) } returns liveSession()
        every {
            rpsService.acceptMatch(initiatorId, opponentId, guildId, stake)
        } returns PvpWagerService.AcceptOutcome.OpponentInsufficient(have = 10L, needed = 50L)

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 1) { registry.forfeit(sessionId) }
        verify(exactly = 1) { message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `accept on an already-resolved session sends ephemeral`() {
        every { event.componentId } returns RpsEmbeds.acceptButtonId(sessionId, opponentId)
        every { registry.accept(sessionId, any()) } returns null

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 0) { rpsService.acceptMatch(any(), any(), any(), any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    // ---- PICK ----

    @Test
    fun `first pick re-renders pick embed with waiting status`() {
        every { event.componentId } returns RpsEmbeds.pickButtonId(sessionId, RpsEngine.Choice.ROCK)
        val live = liveSession()
        every { registry.get(sessionId) } returns live
        every { registry.recordPick(sessionId, initiatorId, RpsEngine.Choice.ROCK) } answers {
            live.picks[initiatorId] = RpsEngine.Choice.ROCK
            live
        }

        button.handle(DefaultButtonContext(event), UserDto(initiatorId, guildId), 0)

        verify(exactly = 1) { registry.recordPick(sessionId, initiatorId, RpsEngine.Choice.ROCK) }
        // bothPicked is false → no resolution call.
        verify(exactly = 0) { rpsService.resolveMatch(any(), any(), any(), any(), any(), any()) }
        // Pick embed re-rendered to update the waiting status.
        verify(atLeast = 1) { message.editMessageEmbeds(any<MessageEmbed>()) }
        // Ephemeral confirmation to the picker.
        verify { mockHook.sendMessage(match<String> { it.contains("You picked") }) }
    }

    @Test
    fun `second pick consumes the session and resolves`() {
        every { event.componentId } returns RpsEmbeds.pickButtonId(sessionId, RpsEngine.Choice.SCISSORS)
        val live = liveSession().also { it.picks[initiatorId] = RpsEngine.Choice.ROCK }
        every { registry.get(sessionId) } returns live
        every { registry.recordPick(sessionId, opponentId, RpsEngine.Choice.SCISSORS) } answers {
            live.picks[opponentId] = RpsEngine.Choice.SCISSORS
            live
        }
        every { registry.consumeForResolution(sessionId) } returns live
        every {
            rpsService.resolveMatch(initiatorId, opponentId, guildId, stake, RpsEngine.Choice.ROCK, RpsEngine.Choice.SCISSORS)
        } returns RpsService.ResolveOutcome.Win(
            winnerDiscordId = initiatorId, loserDiscordId = opponentId,
            winnerChoice = RpsEngine.Choice.ROCK, loserChoice = RpsEngine.Choice.SCISSORS,
            stake = stake, pot = 2 * stake,
            winnerNewBalance = 150L, loserNewBalance = 50L,
            lossTribute = 0L, xpGranted = 10L,
        )

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 1) { registry.consumeForResolution(sessionId) }
        verify(exactly = 1) {
            rpsService.resolveMatch(initiatorId, opponentId, guildId, stake, RpsEngine.Choice.ROCK, RpsEngine.Choice.SCISSORS)
        }
    }

    @Test
    fun `pick by a non-player is rejected ephemerally`() {
        every { event.componentId } returns RpsEmbeds.pickButtonId(sessionId, RpsEngine.Choice.ROCK)
        every { registry.get(sessionId) } returns liveSession()

        button.handle(DefaultButtonContext(event), UserDto(999L, guildId), 0)

        verify(exactly = 0) { registry.recordPick(any(), any(), any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    // ---- FORFEIT ----

    @Test
    fun `forfeit removes the session and resolves against the forfeiter`() {
        every { event.componentId } returns RpsEmbeds.forfeitButtonId(sessionId)
        val live = liveSession().also { it.picks[opponentId] = RpsEngine.Choice.ROCK }
        every { registry.get(sessionId) } returns live
        every { registry.forfeit(sessionId) } returns live
        every {
            rpsService.resolveMatch(initiatorId, opponentId, guildId, stake, null, RpsEngine.Choice.ROCK)
        } returns RpsService.ResolveOutcome.Win(
            winnerDiscordId = opponentId, loserDiscordId = initiatorId,
            winnerChoice = RpsEngine.Choice.ROCK, loserChoice = RpsEngine.Choice.SCISSORS,
            stake = stake, pot = 2 * stake,
            winnerNewBalance = 250L, loserNewBalance = 0L,
            lossTribute = 0L, xpGranted = 10L,
        )

        // Initiator clicks Forfeit; their pick (none) is dropped and the
        // opponent's existing pick wins by walkover.
        button.handle(DefaultButtonContext(event), UserDto(initiatorId, guildId), 0)

        verify(exactly = 1) { registry.forfeit(sessionId) }
        verify(exactly = 1) {
            rpsService.resolveMatch(initiatorId, opponentId, guildId, stake, null, RpsEngine.Choice.ROCK)
        }
    }

    @Test
    fun `forfeit by a non-player is rejected`() {
        every { event.componentId } returns RpsEmbeds.forfeitButtonId(sessionId)
        every { registry.get(sessionId) } returns liveSession()

        button.handle(DefaultButtonContext(event), UserDto(999L, guildId), 0)

        verify(exactly = 0) { registry.forfeit(any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    @Test
    fun `unparseable component id sends ephemeral`() {
        every { event.componentId } returns "garbage"

        button.handle(DefaultButtonContext(event), UserDto(initiatorId, guildId), 0)

        verify(exactly = 0) { registry.get(any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }
}

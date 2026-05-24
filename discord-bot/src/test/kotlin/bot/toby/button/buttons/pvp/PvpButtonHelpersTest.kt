package bot.toby.button.buttons.pvp

import database.dto.user.UserDto
import database.pvp.PvpSessionRegistry
import database.service.pvp.PvpWagerService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import bot.toby.button.buttons.pvp.PvpButtonHelpers

/**
 * Direct unit tests for [PvpButtonHelpers]. These cover the per-game
 * button handlers' shared preambles + `handleDecline` body once,
 * instead of relying on the per-game button tests
 * (`RpsButtonTest`, `TicTacToeButtonTest`, `Connect4ButtonTest`) to
 * catch helper regressions three times over.
 */
class PvpButtonHelpersTest {

    private val initiatorId = 100L
    private val opponentId = 200L
    private val clickerId = 300L
    private val sessionId = 7L
    private val guildId = 99L

    private lateinit var event: ButtonInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var message: Message
    private lateinit var editAction: MessageEditAction
    private lateinit var sendAction: WebhookMessageCreateAction<Message>

    @BeforeEach
    fun setUp() {
        event = mockk(relaxed = true)
        hook = mockk(relaxed = true)
        message = mockk(relaxed = true)
        editAction = mockk(relaxed = true)
        sendAction = mockk(relaxed = true)

        every { event.hook } returns hook
        every { event.message } returns message
        every { message.editMessageEmbeds(any<MessageEmbed>()) } returns editAction
        every { editAction.setComponents(any<Collection<MessageTopLevelComponent>>()) } returns editAction
        every { editAction.queue() } just Runs

        every { hook.sendMessage(any<String>()) } returns sendAction
        every { sendAction.setEphemeral(any()) } returns sendAction
        every { sendAction.queue() } just Runs
    }

    // ── ephemeralAlreadyResolved ───────────────────────────────────────

    @Test
    fun `ephemeralAlreadyResolved sends a fixed-text ephemeral via the interaction hook`() {
        PvpButtonHelpers.ephemeralAlreadyResolved(event)

        verify(exactly = 1) { hook.sendMessage("This match already resolved or expired.") }
        verify(exactly = 1) { sendAction.setEphemeral(true) }
        verify(exactly = 1) { sendAction.queue() }
    }

    // ── describeAccept ─────────────────────────────────────────────────

    @Test
    fun `describeAccept maps InitiatorInsufficient to a challenger-facing message`() {
        val text = PvpButtonHelpers.describeAccept(
            PvpWagerService.AcceptOutcome.InitiatorInsufficient(have = 0L, needed = 50L)
        )
        assertTrue(text.contains("challenger", ignoreCase = true))
    }

    @Test
    fun `describeAccept maps OpponentInsufficient to a self-facing message with the numbers`() {
        val text = PvpButtonHelpers.describeAccept(
            PvpWagerService.AcceptOutcome.OpponentInsufficient(have = 10L, needed = 50L)
        )
        assertTrue(text.contains("10"))
        assertTrue(text.contains("50"))
    }

    @Test
    fun `describeAccept maps UnknownInitiator to a challenger-profile-missing message`() {
        val text = PvpButtonHelpers.describeAccept(PvpWagerService.AcceptOutcome.UnknownInitiator)
        assertTrue(text.contains("challenger", ignoreCase = true))
    }

    @Test
    fun `describeAccept maps UnknownOpponent to a self-profile-missing message`() {
        val text = PvpButtonHelpers.describeAccept(PvpWagerService.AcceptOutcome.UnknownOpponent)
        assertTrue(text.contains("your", ignoreCase = true))
    }

    // ── requireScopedActor ─────────────────────────────────────────────

    @Test
    fun `requireScopedActor returns true and stays silent when clicker matches the scoped actor`() {
        val result = PvpButtonHelpers.requireScopedActor(
            event,
            requestingUserDto = userDto(opponentId),
            scopedDiscordId = opponentId,
            friendlyVerb = "accept",
        )

        assertTrue(result)
        verify(exactly = 0) { hook.sendMessage(any<String>()) }
    }

    @Test
    fun `requireScopedActor returns false and sends an ephemeral when clicker is not the scoped actor`() {
        val message = slot<String>()
        every { hook.sendMessage(capture(message)) } returns sendAction

        val result = PvpButtonHelpers.requireScopedActor(
            event,
            requestingUserDto = userDto(clickerId),
            scopedDiscordId = opponentId,
            friendlyVerb = "decline",
        )

        assertFalse(result)
        verify(exactly = 1) { sendAction.setEphemeral(true) }
        assertTrue(message.captured.contains("decline"))
        assertTrue(message.captured.contains("<@$opponentId>"))
    }

    // ── requireMatchParticipant ────────────────────────────────────────

    @Test
    fun `requireMatchParticipant returns true when the clicker is the initiator`() {
        val result = PvpButtonHelpers.requireMatchParticipant(
            event,
            requestingUserDto = userDto(initiatorId),
            initiatorDiscordId = initiatorId,
            opponentDiscordId = opponentId,
            notParticipantMessage = "irrelevant",
        )

        assertTrue(result)
        verify(exactly = 0) { hook.sendMessage(any<String>()) }
    }

    @Test
    fun `requireMatchParticipant returns true when the clicker is the opponent`() {
        val result = PvpButtonHelpers.requireMatchParticipant(
            event,
            requestingUserDto = userDto(opponentId),
            initiatorDiscordId = initiatorId,
            opponentDiscordId = opponentId,
            notParticipantMessage = "irrelevant",
        )

        assertTrue(result)
        verify(exactly = 0) { hook.sendMessage(any<String>()) }
    }

    @Test
    fun `requireMatchParticipant returns false and sends the caller-supplied ephemeral when clicker is a non-participant`() {
        val result = PvpButtonHelpers.requireMatchParticipant(
            event,
            requestingUserDto = userDto(clickerId),
            initiatorDiscordId = initiatorId,
            opponentDiscordId = opponentId,
            notParticipantMessage = "This isn't your match to forfeit.",
        )

        assertFalse(result)
        verify(exactly = 1) { hook.sendMessage("This isn't your match to forfeit.") }
        verify(exactly = 1) { sendAction.setEphemeral(true) }
    }

    // ── handleDecline ──────────────────────────────────────────────────

    @Test
    fun `handleDecline ignores the click and sends an ephemeral when the clicker is not the scoped actor`() {
        val registry = StubDeclineRegistry()
        val embedBuilder = StubEmbedBuilder()

        PvpButtonHelpers.handleDecline(
            event = event,
            requestingUserDto = userDto(clickerId),
            scopedDiscordId = opponentId,
            sessionId = sessionId,
            decline = registry::decline,
            pendingDeclineEmbed = embedBuilder::pendingDeclineEmbed,
        )

        assertEquals(0, registry.declineCalls)
        assertEquals(0, embedBuilder.calls)
        verify(exactly = 1) { sendAction.setEphemeral(true) }
        verify(exactly = 0) { message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `handleDecline calls decline + edits the message embed when the scoped actor declines a live offer`() {
        val pendingEmbed = mockk<MessageEmbed>(relaxed = true)
        val session = TestSession(sessionId, guildId, initiatorId, opponentId, stake = 50L)
        val registry = StubDeclineRegistry(returnValue = session)
        val embedBuilder = StubEmbedBuilder(embed = pendingEmbed)

        PvpButtonHelpers.handleDecline(
            event = event,
            requestingUserDto = userDto(opponentId),
            scopedDiscordId = opponentId,
            sessionId = sessionId,
            decline = registry::decline,
            pendingDeclineEmbed = embedBuilder::pendingDeclineEmbed,
        )

        assertEquals(1, registry.declineCalls)
        assertEquals(sessionId, registry.lastSessionId)
        assertEquals(1, embedBuilder.calls)
        assertEquals(initiatorId, embedBuilder.lastInitiatorId)
        assertEquals(opponentId, embedBuilder.lastOpponentId)
        verify(exactly = 1) { message.editMessageEmbeds(pendingEmbed) }
        verify(exactly = 1) { editAction.setComponents(emptyList<MessageTopLevelComponent>()) }
        verify(exactly = 1) { editAction.queue() }
    }

    @Test
    fun `handleDecline surfaces the already-resolved ephemeral when the registry returns null`() {
        val registry = StubDeclineRegistry(returnValue = null)
        val embedBuilder = StubEmbedBuilder()

        PvpButtonHelpers.handleDecline(
            event = event,
            requestingUserDto = userDto(opponentId),
            scopedDiscordId = opponentId,
            sessionId = sessionId,
            decline = registry::decline,
            pendingDeclineEmbed = embedBuilder::pendingDeclineEmbed,
        )

        assertEquals(1, registry.declineCalls)
        assertEquals(0, embedBuilder.calls)
        verify(exactly = 1) { hook.sendMessage("This match already resolved or expired.") }
        verify(exactly = 1) { sendAction.setEphemeral(true) }
        verify(exactly = 0) { message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun userDto(discordId: Long) = UserDto(discordId = discordId, guildId = guildId)

    /** Minimal session subclass used only as the [PvpSessionRegistry.Session] return type. */
    private class TestSession(
        id: Long, guildId: Long, initiatorDiscordId: Long, opponentDiscordId: Long, stake: Long,
    ) : PvpSessionRegistry.Session(
        id, guildId, initiatorDiscordId, opponentDiscordId, stake, Instant.parse("2026-05-24T00:00:00Z"),
    )

    /** Counting stub — records what `decline(sessionId)` was called with. */
    private class StubDeclineRegistry(
        private val returnValue: TestSession? = null,
    ) {
        var declineCalls: Int = 0; private set
        var lastSessionId: Long? = null; private set

        fun decline(id: Long): TestSession? {
            declineCalls += 1
            lastSessionId = id
            return returnValue
        }
    }

    /** Counting stub — records which (initiator, opponent) pair was rendered. */
    private class StubEmbedBuilder(private val embed: MessageEmbed? = null) {
        var calls: Int = 0; private set
        var lastInitiatorId: Long? = null; private set
        var lastOpponentId: Long? = null; private set

        fun pendingDeclineEmbed(initiatorId: Long, opponentId: Long): MessageEmbed {
            calls += 1
            lastInitiatorId = initiatorId
            lastOpponentId = opponentId
            return embed ?: mockk(relaxed = true)
        }
    }
}

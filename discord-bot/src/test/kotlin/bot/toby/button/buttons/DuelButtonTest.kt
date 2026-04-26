package bot.toby.button.buttons

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.ButtonTest.Companion.mockGuild
import bot.toby.button.ButtonTest.Companion.mockHook
import bot.toby.button.DefaultButtonContext
import bot.toby.command.commands.economy.DuelEmbeds
import database.duel.PendingDuelRegistry
import database.dto.UserDto
import database.service.DuelService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class DuelButtonTest : ButtonTest {

    private lateinit var duelService: DuelService
    private lateinit var pendingDuelRegistry: PendingDuelRegistry
    private lateinit var button: DuelButton

    private val initiatorId = 1L
    private val opponentId = 2L
    private val guildId = 1L
    private val stake = 50L
    private val duelId = 100L

    private lateinit var message: Message
    private lateinit var messageEditAction: RestAction<Void>
    @Suppress("UNCHECKED_CAST")
    private fun anyEditAction(): RestAction<Void> = mockk(relaxed = true) {
        every { queue() } just Runs
    }

    @BeforeEach
    override fun setup() {
        super.setup()
        every { mockGuild.idLong } returns guildId

        duelService = mockk(relaxed = true)
        pendingDuelRegistry = mockk(relaxed = true)
        button = DuelButton(duelService, pendingDuelRegistry)

        message = mockk(relaxed = true)
        every { event.message } returns message

        // Wire the edit-message chain. JDA returns a MessageEditAction; we
        // care about the queue() at the end.
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

    private fun pendingDuel() = PendingDuelRegistry.PendingDuel(
        id = duelId, guildId = guildId,
        initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
        stake = stake, createdAt = Instant.now()
    )

    @Test
    fun `non-opponent gets ephemeral reject without resolving`() {
        every { event.componentId } returns DuelEmbeds.acceptButtonId(duelId, opponentId)

        button.handle(DefaultButtonContext(event), UserDto(999L, guildId), 0)

        verify(exactly = 0) { duelService.acceptDuel(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { pendingDuelRegistry.consumeForAccept(any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    @Test
    fun `accept happy path resolves via DuelService and edits the offer message`() {
        every { event.componentId } returns DuelEmbeds.acceptButtonId(duelId, opponentId)
        every { pendingDuelRegistry.consumeForAccept(duelId) } returns pendingDuel()
        every {
            duelService.acceptDuel(initiatorId, opponentId, guildId, stake)
        } returns DuelService.AcceptOutcome.Win(
            winnerDiscordId = initiatorId, loserDiscordId = opponentId,
            stake = stake, pot = 100L,
            winnerNewBalance = 245L, loserNewBalance = 50L,
            lossTribute = 5L
        )

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 1) { pendingDuelRegistry.consumeForAccept(duelId) }
        verify(exactly = 1) { duelService.acceptDuel(initiatorId, opponentId, guildId, stake) }
        verify { event.message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `accept after offer expired returns ephemeral expired message`() {
        every { event.componentId } returns DuelEmbeds.acceptButtonId(duelId, opponentId)
        every { pendingDuelRegistry.consumeForAccept(duelId) } returns null

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 0) { duelService.acceptDuel(any(), any(), any(), any(), any()) }
        verify { mockHook.sendMessage(any<String>()) }
    }

    @Test
    fun `decline cancels offer in registry without calling service`() {
        every { event.componentId } returns DuelEmbeds.declineButtonId(duelId, opponentId)
        every { pendingDuelRegistry.cancel(duelId) } returns pendingDuel()

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify(exactly = 1) { pendingDuelRegistry.cancel(duelId) }
        verify(exactly = 0) { duelService.acceptDuel(any(), any(), any(), any(), any()) }
        verify { event.message.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `accept where service reports insufficient credits renders error embed`() {
        every { event.componentId } returns DuelEmbeds.acceptButtonId(duelId, opponentId)
        every { pendingDuelRegistry.consumeForAccept(duelId) } returns pendingDuel()
        every {
            duelService.acceptDuel(initiatorId, opponentId, guildId, stake)
        } returns DuelService.AcceptOutcome.InitiatorInsufficient(have = 5L, needed = 50L)

        button.handle(DefaultButtonContext(event), UserDto(opponentId, guildId), 0)

        verify { event.message.editMessageEmbeds(any<MessageEmbed>()) }
    }
}

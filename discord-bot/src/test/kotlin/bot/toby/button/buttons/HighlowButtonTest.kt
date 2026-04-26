package bot.toby.button.buttons

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.ButtonTest.Companion.mockGuild
import bot.toby.button.DefaultButtonContext
import database.dto.UserDto
import database.economy.Highlow
import database.service.HighlowService
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

class HighlowButtonTest : ButtonTest {

    private lateinit var highlowService: HighlowService
    private lateinit var button: HighlowButton
    private lateinit var editAction: MessageEditCallbackAction
    private lateinit var replyAction: ReplyCallbackAction

    private val ownerId = 6L
    private val guildId = 1L
    private val anchor = 9
    private val stake = 50L

    @BeforeEach
    override fun setup() {
        super.setup()
        every { mockGuild.idLong } returns guildId

        highlowService = mockk(relaxed = true)
        button = HighlowButton(highlowService)

        // F-bounded generics on JDA's MessageEditCallbackAction don't survive
        // mockk's relaxed deep-stubs cleanly, so we wire the chain manually.
        editAction = mockk(relaxed = true)
        every { editAction.setComponents(any<Collection<MessageTopLevelComponent>>()) } returns editAction
        every { editAction.setComponents(*anyVararg<MessageTopLevelComponent>()) } returns editAction
        every { editAction.queue() } just Runs
        every { event.editMessageEmbeds(any<MessageEmbed>()) } returns editAction

        replyAction = mockk(relaxed = true)
        every { replyAction.setEphemeral(any()) } returns replyAction
        every { replyAction.queue() } just Runs
        every { event.reply(any<String>()) } returns replyAction
    }

    @AfterEach
    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
    }

    @Test
    fun `routes click to HighlowService with parsed direction anchor and stake`() {
        every { event.componentId } returns "highlow:HIGHER:$anchor:$stake:$ownerId"
        every {
            highlowService.play(ownerId, guildId, stake, Highlow.Direction.HIGHER, anchor)
        } returns HighlowService.PlayOutcome.Win(
            stake = stake,
            payout = 100L,
            net = stake,
            anchor = anchor,
            next = 12,
            direction = Highlow.Direction.HIGHER,
            multiplier = 2.0,
            newBalance = 1_050L
        )

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 1) {
            highlowService.play(ownerId, guildId, stake, Highlow.Direction.HIGHER, anchor)
        }
        verify { event.editMessageEmbeds(any<MessageEmbed>()) }
        verify { editAction.queue() }
    }

    @Test
    fun `LOWER direction parses correctly`() {
        every { event.componentId } returns "highlow:LOWER:5:25:$ownerId"

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 1) {
            highlowService.play(ownerId, guildId, 25L, Highlow.Direction.LOWER, 5)
        }
    }

    @Test
    fun `edits the original message and clears components`() {
        every { event.componentId } returns "highlow:HIGHER:$anchor:$stake:$ownerId"
        every {
            highlowService.play(ownerId, guildId, stake, Highlow.Direction.HIGHER, anchor)
        } returns HighlowService.PlayOutcome.Lose(
            stake = stake,
            anchor = anchor,
            next = anchor,
            direction = Highlow.Direction.HIGHER,
            newBalance = 950L
        )

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify { event.editMessageEmbeds(any<MessageEmbed>()) }
        verify { editAction.setComponents(any<Collection<MessageTopLevelComponent>>()) }
    }

    @Test
    fun `someone else clicking another player's round is rejected ephemerally without resolving`() {
        every { event.componentId } returns "highlow:HIGHER:$anchor:$stake:$ownerId"

        button.handle(DefaultButtonContext(event), UserDto(999L, guildId), 0)

        verify(exactly = 0) { highlowService.play(any(), any(), any(), any(), any()) }
        verify { event.reply(any<String>()) }
        verify { replyAction.setEphemeral(true) }
        verify { replyAction.queue() }
    }

    @Test
    fun `malformed component id is acked without resolving`() {
        every { event.componentId } returns "highlow:bogus"

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 0) { highlowService.play(any(), any(), any(), any(), any()) }
    }
}

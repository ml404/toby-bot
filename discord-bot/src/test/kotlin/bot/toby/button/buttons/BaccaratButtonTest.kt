package bot.toby.button.buttons

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.ButtonTest.Companion.mockGuild
import bot.toby.button.DefaultButtonContext
import database.card.Card
import database.card.Rank
import database.card.Suit
import database.dto.UserDto
import database.economy.Baccarat
import database.service.BaccaratService
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

class BaccaratButtonTest : ButtonTest {

    private lateinit var baccaratService: BaccaratService
    private lateinit var button: BaccaratButton
    private lateinit var editAction: MessageEditCallbackAction
    private lateinit var replyAction: ReplyCallbackAction

    private val ownerId = 6L
    private val guildId = 1L
    private val stake = 50L

    @BeforeEach
    override fun setup() {
        super.setup()
        every { mockGuild.idLong } returns guildId

        baccaratService = mockk(relaxed = true)
        button = BaccaratButton(baccaratService)

        // F-bounded generics on JDA's MessageEditCallbackAction don't survive
        // mockk's relaxed deep-stubs cleanly, so wire the chain manually.
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

    private fun loseOutcome(side: Baccarat.Side) = BaccaratService.PlayOutcome.Lose(
        stake = stake,
        side = side,
        winner = if (side == Baccarat.Side.BANKER) Baccarat.Side.PLAYER else Baccarat.Side.BANKER,
        playerCards = listOf(Card(Rank.FIVE, Suit.SPADES), Card(Rank.THREE, Suit.HEARTS)),
        bankerCards = listOf(Card(Rank.NINE, Suit.CLUBS), Card(Rank.SEVEN, Suit.DIAMONDS)),
        playerTotal = 8,
        bankerTotal = 6,
        isPlayerNatural = true,
        isBankerNatural = false,
        newBalance = 950L
    )

    @Test
    fun `routes click to BaccaratService with parsed side and stake`() {
        every { event.componentId } returns "baccarat:PLAYER:$stake:$ownerId"
        every {
            baccaratService.play(ownerId, guildId, stake, Baccarat.Side.PLAYER)
        } returns loseOutcome(Baccarat.Side.PLAYER)

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 1) {
            baccaratService.play(ownerId, guildId, stake, Baccarat.Side.PLAYER)
        }
        verify { event.editMessageEmbeds(any<MessageEmbed>()) }
        verify { editAction.queue() }
    }

    @Test
    fun `BANKER and TIE sides parse correctly`() {
        every { event.componentId } returns "baccarat:BANKER:25:$ownerId"
        every {
            baccaratService.play(ownerId, guildId, 25L, Baccarat.Side.BANKER)
        } returns loseOutcome(Baccarat.Side.BANKER)

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)
        verify(exactly = 1) {
            baccaratService.play(ownerId, guildId, 25L, Baccarat.Side.BANKER)
        }

        every { event.componentId } returns "baccarat:TIE:75:$ownerId"
        every {
            baccaratService.play(ownerId, guildId, 75L, Baccarat.Side.TIE)
        } returns loseOutcome(Baccarat.Side.TIE)

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)
        verify(exactly = 1) {
            baccaratService.play(ownerId, guildId, 75L, Baccarat.Side.TIE)
        }
    }

    @Test
    fun `edits the original message and clears components`() {
        every { event.componentId } returns "baccarat:PLAYER:$stake:$ownerId"
        every {
            baccaratService.play(ownerId, guildId, stake, Baccarat.Side.PLAYER)
        } returns loseOutcome(Baccarat.Side.PLAYER)

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify { event.editMessageEmbeds(any<MessageEmbed>()) }
        verify { editAction.setComponents(any<Collection<MessageTopLevelComponent>>()) }
    }

    @Test
    fun `another player clicking someone else's round is rejected ephemerally without resolving`() {
        every { event.componentId } returns "baccarat:PLAYER:$stake:$ownerId"

        button.handle(DefaultButtonContext(event), UserDto(999L, guildId), 0)

        verify(exactly = 0) { baccaratService.play(any(), any(), any(), any(), any()) }
        verify { event.reply(any<String>()) }
        verify { replyAction.setEphemeral(true) }
        verify { replyAction.queue() }
    }

    @Test
    fun `malformed component id is acked without resolving`() {
        every { event.componentId } returns "baccarat:bogus"

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 0) { baccaratService.play(any(), any(), any(), any(), any()) }
    }
}

package bot.toby.button.buttons

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.ButtonTest.Companion.mockGuild
import bot.toby.button.DefaultButtonContext
import database.dto.UserDto
import database.economy.Highlow
import database.service.HighlowService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HighlowButtonTest : ButtonTest {

    private lateinit var highlowService: HighlowService
    private lateinit var button: HighlowButton

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
    }

    @AfterEach
    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
    }

    @Test
    fun `routes click to HighlowService with parsed direction anchor and stake`() {
        every { event.componentId } returns "highlow:HIGHER:$anchor:$stake:$ownerId"
        every { event.user.idLong } returns ownerId

        every {
            highlowService.play(ownerId, guildId, stake, Highlow.Direction.HIGHER, anchor)
        } returns HighlowService.PlayOutcome.Win(
            stake = stake,
            payout = 100L,
            net = stake,
            anchor = anchor,
            next = 12,
            direction = Highlow.Direction.HIGHER,
            newBalance = 1_050L
        )

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 1) {
            highlowService.play(ownerId, guildId, stake, Highlow.Direction.HIGHER, anchor)
        }
        verify { event.editMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `LOWER direction parses correctly`() {
        every { event.componentId } returns "highlow:LOWER:5:25:$ownerId"
        every { event.user.idLong } returns ownerId

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 1) {
            highlowService.play(ownerId, guildId, 25L, Highlow.Direction.LOWER, 5)
        }
    }

    @Test
    fun `edits the original message and clears components`() {
        every { event.componentId } returns "highlow:HIGHER:$anchor:$stake:$ownerId"
        every { event.user.idLong } returns ownerId

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

        verify {
            event.editMessageEmbeds(any<MessageEmbed>())
                .setComponents()
        }
    }

    @Test
    fun `someone else clicking another player's round is rejected ephemerally without resolving`() {
        every { event.componentId } returns "highlow:HIGHER:$anchor:$stake:$ownerId"
        every { event.user.idLong } returns 999L

        button.handle(DefaultButtonContext(event), UserDto(999L, guildId), 0)

        verify(exactly = 0) { highlowService.play(any(), any(), any(), any(), any()) }
        verify {
            event.reply(any<String>()).setEphemeral(true)
        }
    }

    @Test
    fun `malformed component id is acked without resolving`() {
        every { event.componentId } returns "highlow:bogus"
        every { event.user.idLong } returns ownerId

        button.handle(DefaultButtonContext(event), UserDto(ownerId, guildId), 0)

        verify(exactly = 0) { highlowService.play(any(), any(), any(), any(), any()) }
    }
}

package bot.toby.modal.modals

import bot.toby.helpers.UserDtoHelper
import core.modal.ModalContext
import database.service.social.TipService
import database.service.social.TipService.TipOutcome
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TipMessageModalTest {

    private lateinit var tipService: TipService
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var modal: TipMessageModal
    private lateinit var ctx: ModalContext
    private lateinit var event: ModalInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var channel: MessageChannelUnion
    private val hookEmbeds = mutableListOf<MessageEmbed>()
    private val hookText = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        tipService = mockk()
        userDtoHelper = mockk(relaxed = true)
        modal = TipMessageModal(tipService, userDtoHelper)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true)
        channel = mockk(relaxed = true)

        val guild = mockk<Guild> { every { idLong } returns 100L }
        val user = mockk<User> { every { idLong } returns 42L }
        every { event.user } returns user
        every { event.hook } returns hook
        every { event.channel } returns channel

        ctx = mockk {
            every { this@mockk.event } returns this@TipMessageModalTest.event
            every { this@mockk.guild } returns guild
        }

        @Suppress("UNCHECKED_CAST")
        val hookEmbedSend = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessageEmbeds(capture(hookEmbeds)) } returns hookEmbedSend
        every { hookEmbedSend.setEphemeral(true) } returns hookEmbedSend
        every { hookEmbedSend.queue() } just Runs

        @Suppress("UNCHECKED_CAST")
        val hookTextSend = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessage(capture(hookText)) } returns hookTextSend
        every { hookTextSend.setEphemeral(true) } returns hookTextSend
        every { hookTextSend.queue() } just Runs

        val channelSend = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns channelSend
        every { channelSend.addContent(any<String>()) } returns channelSend
        every { channelSend.queue() } just Runs
    }

    private fun submit(modalId: String, note: String? = null) {
        every { event.modalId } returns modalId
        val mapping = note?.let { mockk<ModalMapping> { every { asString } returns it } }
        every { event.getValue(TipMessageModal.FIELD_NOTE) } returns mapping
    }

    @Test
    fun `name is tip_message`() {
        assertEquals("tip_message", modal.name)
    }

    @Test
    fun `customId encodes recipient and amount`() {
        // Round-trip the encoder used by the slash command path; if this
        // format changes, the modal would silently lose recipient context.
        assertEquals("tip_message:9:250", TipMessageModal.customId(9L, 250L))
    }

    @Test
    fun `malformed customId (missing recipient or amount) errors out and never hits the service`() {
        submit("tip_message:abc")

        modal.handle(ctx, 0)

        assertEquals(1, hookEmbeds.size)
        verify(exactly = 0) { tipService.tip(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { userDtoHelper.calculateUserDto(any(), any()) }
    }

    @Test
    fun `blank note is normalised to null before reaching the service`() {
        // tip()'s `at` and `dailyCap` parameters have Kotlin defaults that
        // materialise at the call site — match them with any() so we're
        // asserting only the inputs the modal actually controls.
        submit("tip_message:9:250", note = "   ")
        every {
            tipService.tip(
                senderDiscordId = 42L, recipientDiscordId = 9L, guildId = 100L,
                amount = 250L, note = null, at = any(), dailyCap = any(),
            )
        } returns TipOutcome.Ok(
            sender = 42L, recipient = 9L, amount = 250L, note = null,
            senderNewBalance = 100L, recipientNewBalance = 350L, sentTodayAfter = 250L, dailyCap = 1000L,
        )

        modal.handle(ctx, 0)

        verify(exactly = 1) {
            tipService.tip(
                senderDiscordId = 42L, recipientDiscordId = 9L, guildId = 100L,
                amount = 250L, note = null, at = any(), dailyCap = any(),
            )
        }
    }

    @Test
    fun `Ok outcome posts the public embed with recipient ping and confirms ephemerally`() {
        submit("tip_message:9:250", note = "nice game")
        every {
            tipService.tip(
                senderDiscordId = 42L, recipientDiscordId = 9L, guildId = 100L,
                amount = 250L, note = "nice game", at = any(), dailyCap = any(),
            )
        } returns TipOutcome.Ok(
            sender = 42L, recipient = 9L, amount = 250L, note = "nice game",
            senderNewBalance = 100L, recipientNewBalance = 350L, sentTodayAfter = 250L, dailyCap = 1000L,
        )
        val contentSlot = slot<String>()
        val channelSend = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns channelSend
        every { channelSend.addContent(capture(contentSlot)) } returns channelSend
        every { channelSend.queue() } just Runs

        modal.handle(ctx, 0)

        // Recipient row lazy-created (same as the slash path).
        verify(exactly = 1) { userDtoHelper.calculateUserDto(9L, 100L) }
        assertTrue(contentSlot.captured.contains("<@9>"))
        assertEquals(listOf("Tip sent."), hookText)
    }

    @Test
    fun `failure outcome replies with a friendly ephemeral error and never posts to channel`() {
        submit("tip_message:9:250")
        every {
            tipService.tip(any(), any(), any(), any(), any(), any(), any())
        } returns TipOutcome.InsufficientCredits(have = 50L, needed = 250L)

        modal.handle(ctx, 0)

        assertEquals(1, hookEmbeds.size)
        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }
}

package bot.toby.modal.modals

import core.modal.ModalContext
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
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

class PollModalTest {

    private lateinit var modal: PollModal
    private lateinit var ctx: ModalContext
    private lateinit var event: ModalInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var channel: MessageChannelUnion
    private val hookMessageSlot = slot<String>()
    private val embedSlot = slot<MessageEmbed>()

    @BeforeEach
    fun setup() {
        modal = PollModal()
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true)
        channel = mockk(relaxed = true)

        val guild = mockk<Guild> { every { idLong } returns 100L }
        val user = mockk<User>(relaxed = true) {
            every { idLong } returns 42L
            every { effectiveName } returns "matt"
        }
        val member = mockk<Member>(relaxed = true) { every { effectiveName } returns "Matt" }

        every { event.user } returns user
        every { event.member } returns member
        every { event.hook } returns hook
        every { event.channel } returns channel

        ctx = mockk {
            every { this@mockk.event } returns this@PollModalTest.event
            every { this@mockk.guild } returns guild
        }

        @Suppress("UNCHECKED_CAST")
        val hookSend = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessage(capture(hookMessageSlot)) } returns hookSend
        every { hookSend.setEphemeral(true) } returns hookSend
        every { hookSend.queue() } just Runs

        val channelSend = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(capture(embedSlot)) } returns channelSend
        // Skip the queue callback — addReaction would need a Message mock; verify
        // the embed shape instead, which is the actual contract.
        every { channelSend.queue(any()) } just Runs
    }

    private fun submitWith(question: String?, vararg options: String?) {
        val qMapping = question?.let { mockk<ModalMapping> { every { asString } returns it } }
        every { event.getValue(PollModal.FIELD_QUESTION) } returns qMapping
        // Stub every option field, even past the size of `options`, so
        // PollModal sees `null` (not a leftover stub) for unused indices.
        (1..PollModal.MAX_OPTIONS).forEach { idx ->
            val raw = options.getOrNull(idx - 1)
            val mapping = raw?.let { mockk<ModalMapping> { every { asString } returns it } }
            every { event.getValue("${PollModal.FIELD_OPTION_PREFIX}$idx") } returns mapping
        }
    }

    @Test
    fun `name is poll`() {
        assertEquals("poll", modal.name)
    }

    @Test
    fun `rejects when no options provided and never posts an embed`() {
        submitWith(question = "What's for dinner?")

        modal.handle(ctx, 0)

        assertTrue(hookMessageSlot.captured.contains("at least one option"))
        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `whitespace-only options are filtered - still rejects when nothing meaningful remains`() {
        submitWith(question = "Q", "   ", "\t", "")

        modal.handle(ctx, 0)

        assertTrue(hookMessageSlot.captured.contains("at least one option"))
    }

    @Test
    fun `blank question falls back to literal Poll as the embed title`() {
        submitWith(question = "   ", "pizza")

        modal.handle(ctx, 0)

        assertEquals("Poll", embedSlot.captured.title)
    }

    @Test
    fun `posts an embed with one numbered line per non-blank option`() {
        submitWith(question = "What's for dinner?", "pizza", "tacos", " sushi ")

        modal.handle(ctx, 0)

        val description = embedSlot.captured.description ?: ""
        assertTrue(description.contains("pizza"))
        assertTrue(description.contains("tacos"))
        assertTrue(description.contains("sushi"))
        assertTrue(description.contains("1️⃣"))
        assertTrue(description.contains("2️⃣"))
        assertTrue(description.contains("3️⃣"))
        // Only 3 options used — the 4th emoji should not be in the embed.
        assertEquals(false, description.contains("4️⃣"))
        // Confirmation is ephemeral on the hook.
        assertEquals("Poll posted.", hookMessageSlot.captured)
    }

    @Test
    fun `embed author falls back to user effectiveName when member is null (DM modal)`() {
        every { event.member } returns null
        submitWith(question = "Q?", "yes")

        modal.handle(ctx, 0)

        assertEquals("matt", embedSlot.captured.author?.name)
    }
}

package bot.toby.handler

import bot.toby.command.commands.mtg.ScryfallCubeFetcher
import common.mtg.CubeCard
import common.mtg.MtgColor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CardMentionListenerTest {

    private lateinit var fetcher: ScryfallCubeFetcher
    private lateinit var listener: CardMentionListener
    private lateinit var event: MessageReceivedEvent

    @BeforeEach
    fun setUp() {
        fetcher = mockk()
        listener = CardMentionListener(fetcher, Dispatchers.Unconfined)
        event = mockk(relaxed = true)
        every { event.author.isBot } returns false
        every { event.isWebhookMessage } returns false
    }

    private fun message(content: String) = every { event.message.contentRaw } returns content

    // --- cardMentions extraction (pure) --------------------------------

    @Test
    fun `cardMentions pulls distinct bracketed names in order`() {
        assertEquals(
            listOf("Lightning Bolt", "Sol Ring"),
            CardMentionListener.cardMentions("I run [[Lightning Bolt]] and [[Sol Ring]] — did I mention [[Lightning Bolt]]?"),
        )
    }

    @Test
    fun `cardMentions ignores text without brackets and caps the count`() {
        assertEquals(emptyList<String>(), CardMentionListener.cardMentions("no cards here"))
        val many = (1..8).joinToString(" ") { "[[Card $it]]" }
        assertEquals(CardMentionListener.MAX_CARDS, CardMentionListener.cardMentions(many).size)
    }

    @Test
    fun `cardMentions trims and drops blank mentions`() {
        assertEquals(listOf("Bolt"), CardMentionListener.cardMentions("[[  Bolt  ]] [[   ]]"))
    }

    // --- listener behaviour --------------------------------------------

    @Test
    fun `a message with a card mention replies with the resolved card embed`() {
        message("check out [[Lightning Bolt]]")
        coEvery { fetcher.fetchNamed("Lightning Bolt") } returns
            CubeCard("Lightning Bolt", setOf(MtgColor.RED), typeLine = "Instant", manaValue = 1.0, imageUrl = "https://img/bolt.jpg", rarity = "common")

        listener.onMessageReceived(event)

        coVerify(exactly = 1) { fetcher.fetchNamed("Lightning Bolt") }
        verify(exactly = 1) { event.channel.sendMessageEmbeds(any<Collection<MessageEmbed>>()) }
    }

    @Test
    fun `a message with no brackets is ignored entirely`() {
        message("just chatting")
        listener.onMessageReceived(event)
        coVerify(exactly = 0) { fetcher.fetchNamed(any()) }
        verify(exactly = 0) { event.channel.sendMessageEmbeds(any<Collection<MessageEmbed>>()) }
    }

    @Test
    fun `bot and webhook messages are ignored`() {
        message("[[Lightning Bolt]]")
        every { event.author.isBot } returns true
        listener.onMessageReceived(event)
        coVerify(exactly = 0) { fetcher.fetchNamed(any()) }
    }

    @Test
    fun `nothing is sent when no mention resolves`() {
        message("[[Notacard]]")
        coEvery { fetcher.fetchNamed("Notacard") } returns null
        listener.onMessageReceived(event)
        verify(exactly = 0) { event.channel.sendMessageEmbeds(any<Collection<MessageEmbed>>()) }
    }
}

package bot.toby.handler

import bot.toby.command.commands.mtg.ScryfallCubeFetcher
import common.mtg.CubeCard
import common.mtg.MtgColor
import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CardMentionListenerTest {

    private lateinit var fetcher: ScryfallCubeFetcher
    private lateinit var configService: ConfigService
    private lateinit var listener: CardMentionListener
    private lateinit var event: MessageReceivedEvent

    @BeforeEach
    fun setUp() {
        fetcher = mockk()
        configService = mockk(relaxed = true) // null config → feature enabled (opt-out)
        listener = CardMentionListener(fetcher, configService, Dispatchers.Unconfined)
        event = mockk(relaxed = true)
        every { event.author.isBot } returns false
        every { event.isWebhookMessage } returns false
        every { event.isFromGuild } returns false // most tests don't need a guild config
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

    @Test
    fun `a card-mention embed includes the oracle rules text`() {
        message("[[Lightning Bolt]]")
        coEvery { fetcher.fetchNamed("Lightning Bolt") } returns CubeCard(
            "Lightning Bolt", setOf(MtgColor.RED), typeLine = "Instant", manaValue = 1.0,
            imageUrl = "https://img/bolt.jpg", rarity = "common", oracleText = "Lightning Bolt deals 3 damage to any target.",
        )

        listener.onMessageReceived(event)

        val captured = mutableListOf<Collection<MessageEmbed>>()
        verify { event.channel.sendMessageEmbeds(capture(captured)) }
        val embeds = captured.first().toList()
        assertEquals(1, embeds.size)
        assertTrue(embeds[0].description!!.contains("deals 3 damage"))
    }

    @Test
    fun `a double-faced card mention adds a second embed for the back face`() {
        message("[[Delver of Secrets]]")
        coEvery { fetcher.fetchNamed("Delver of Secrets") } returns CubeCard(
            "Delver of Secrets // Insectile Aberration", setOf(MtgColor.BLUE), typeLine = "Creature",
            manaValue = 1.0, imageUrl = "https://img/front.jpg", imageUrlBack = "https://img/back.jpg",
        )

        listener.onMessageReceived(event)

        val captured = mutableListOf<Collection<MessageEmbed>>()
        verify { event.channel.sendMessageEmbeds(capture(captured)) }
        val embeds = captured.first().toList()
        assertEquals(2, embeds.size)
        assertEquals("https://img/front.jpg", embeds[0].image?.url)
        assertTrue(embeds[1].title!!.contains("(back)"))
        assertEquals("https://img/back.jpg", embeds[1].image?.url)
    }

    @Test
    fun `a guild that turned card mentions off gets no lookup`() {
        every { event.isFromGuild } returns true
        every { event.guild.idLong } returns 123L
        every {
            configService.getConfigByName(ConfigDto.Configurations.CARD_MENTIONS.configValue, "123")
        } returns mockk { every { value } returns "false" }
        message("[[Lightning Bolt]]")

        listener.onMessageReceived(event)

        coVerify(exactly = 0) { fetcher.fetchNamed(any()) }
    }
}

package bot.toby.command.commands.mtg

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import common.mtg.CubeCard
import database.dto.user.CardPriceWatchDto
import database.service.user.CardPriceWatchService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PriceWatchCommandTest : CommandTest {

    private lateinit var fetcher: ScryfallCubeFetcher
    private lateinit var priceWatchService: CardPriceWatchService
    private lateinit var command: PriceWatchCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        fetcher = mockk()
        priceWatchService = mockk(relaxed = true)
        command = PriceWatchCommand(fetcher, priceWatchService, Dispatchers.Unconfined)
        every { requestingUserDto.discordId } returns 100L
    }

    private fun strOpt(value: String): OptionMapping = mockk { every { asString } returns value }
    private fun numOpt(value: Double): OptionMapping = mockk { every { asDouble } returns value }
    private fun longOpt(value: Long): OptionMapping = mockk { every { asLong } returns value }
    private fun run() = command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay = 0)

    @Test
    fun `add resolves the card, captures the price and confirms`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns PriceWatchCommand.SUB_ADD
        every { event.getOption(PriceWatchCommand.OPT_NAME) } returns strOpt("Ragavan")
        every { event.getOption(PriceWatchCommand.OPT_DIRECTION) } returns strOpt("BELOW")
        every { event.getOption(PriceWatchCommand.OPT_PRICE) } returns numOpt(30.0)
        every { event.getOption(PriceWatchCommand.OPT_CURRENCY) } returns strOpt("usd")
        coEvery { fetcher.fetchNamed("Ragavan") } returns CubeCard("Ragavan, Nimble Pilferer", priceUsd = "45.00")
        every {
            priceWatchService.create(100L, any(), "Ragavan, Nimble Pilferer", "usd", CardPriceWatchDto.Direction.BELOW, 30.0, 45.0)
        } returns CardPriceWatchDto(id = 5, cardName = "Ragavan, Nimble Pilferer", currency = "usd", direction = "BELOW", threshold = 30.0)

        run()

        assertTrue(slot.captured.title!!.contains("Watching"))
        verify(exactly = 1) { priceWatchService.create(100L, any(), "Ragavan, Nimble Pilferer", "usd", CardPriceWatchDto.Direction.BELOW, 30.0, 45.0) }
    }

    @Test
    fun `add reports when the card can't be found`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns PriceWatchCommand.SUB_ADD
        every { event.getOption(PriceWatchCommand.OPT_NAME) } returns strOpt("Notacard")
        every { event.getOption(PriceWatchCommand.OPT_DIRECTION) } returns strOpt("BELOW")
        every { event.getOption(PriceWatchCommand.OPT_PRICE) } returns numOpt(30.0)
        every { event.getOption(PriceWatchCommand.OPT_CURRENCY) } returns null
        coEvery { fetcher.fetchNamed(any()) } returns null

        run()

        assertTrue(slot.captured.description!!.contains("Notacard"))
        verify(exactly = 0) { priceWatchService.create(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `list shows the user's watches`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns PriceWatchCommand.SUB_LIST
        every { priceWatchService.listForUser(100L) } returns listOf(
            CardPriceWatchDto(id = 1, cardName = "Ragavan", currency = "usd", direction = "BELOW", threshold = 30.0)
        )

        run()

        assertEquals("Your card price watches", slot.captured.title)
        assertTrue(slot.captured.description!!.contains("Ragavan"))
    }

    @Test
    fun `remove deletes by id`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns PriceWatchCommand.SUB_REMOVE
        every { event.getOption(PriceWatchCommand.OPT_WATCH_ID) } returns longOpt(5L)
        every { priceWatchService.remove(5L, 100L) } returns true

        run()

        assertTrue(slot.captured.title!!.contains("Watch removed"))
        verify(exactly = 1) { priceWatchService.remove(5L, 100L) }
    }

    @Test
    fun `exposes add, list and remove subcommands`() {
        assertEquals("pricewatch", command.name)
        val subs = command.subCommands.associateBy { it.name }
        assertEquals(setOf("add", "list", "remove"), subs.keys)
        assertTrue(subs.getValue("add").options.first { it.name == "name" }.isRequired)
        assertTrue(subs.getValue("remove").options.first { it.name == "id" }.isRequired)
    }
}

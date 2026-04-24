package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import bot.toby.economy.TobyCoinChartRenderer
import database.dto.TobyCoinMarketDto
import database.dto.UserDto
import database.economy.TobyCoinEngine
import database.service.EconomyTradeService
import database.service.EconomyTradeService.TradeOutcome
import database.service.TobyCoinMarketService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

internal class TobyCoinCommandTest : CommandTest {
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var chartRenderer: TobyCoinChartRenderer
    private lateinit var command: TobyCoinCommand

    private val discordId = 1L
    private val guildId = 1L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        chartRenderer = mockk(relaxed = true)
        command = TobyCoinCommand(marketService, tradeService, chartRenderer)
        every { guild.name } returns "Test Guild"
        every { tradeService.loadOrCreateMarket(guildId) } returns market(100.0)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun intOpt(value: Long): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asLong } returns value
        return o
    }

    private fun market(price: Double = 100.0) = TobyCoinMarketDto(
        guildId = guildId,
        price = price,
        lastTickAt = Instant.now()
    )

    @Test
    fun `buy delegates to EconomyTradeService with the amount option`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "buy"
        every { event.getOption("amount") } returns intOpt(5L)
        every { tradeService.buy(discordId, guildId, 5L) } returns TradeOutcome.Ok(
            amount = 5L,
            transactedCredits = 500L,
            newCoins = 5L,
            newCredits = 500L,
            newPrice = 100.2
        )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { tradeService.buy(discordId, guildId, 5L) }
    }

    @Test
    fun `sell delegates to EconomyTradeService with the amount option`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "sell"
        every { event.getOption("amount") } returns intOpt(4L)
        every { tradeService.sell(discordId, guildId, 4L) } returns TradeOutcome.Ok(
            amount = 4L,
            transactedCredits = 400L,
            newCoins = 0L,
            newCredits = 400L,
            newPrice = 99.84
        )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { tradeService.sell(discordId, guildId, 4L) }
    }

    @Test
    fun `balance reads coin and credit fields without hitting trade service`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 250L
            tobyCoins = 7L
        }
        every { event.subcommandName } returns "balance"

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { tradeService.buy(any(), any(), any()) }
        verify(exactly = 0) { tradeService.sell(any(), any(), any()) }
    }

    @Test
    fun `price subcommand looks up 24h history`() {
        every { event.subcommandName } returns "price"
        every { marketService.listHistory(guildId, any()) } returns emptyList()
        every { tradeService.loadOrCreateMarket(guildId) } returns market(TobyCoinEngine.INITIAL_PRICE)

        command.handle(DefaultCommandContext(event), UserDto(discordId, guildId), 5)

        verify(exactly = 1) { marketService.listHistory(guildId, any()) }
    }
}

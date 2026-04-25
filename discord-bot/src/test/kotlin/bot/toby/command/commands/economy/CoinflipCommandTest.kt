package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import database.economy.Coinflip
import database.service.CoinflipService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class CoinflipCommandTest : CommandTest {
    private lateinit var coinflipService: CoinflipService
    private lateinit var command: CoinflipCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        coinflipService = mockk(relaxed = true)
        command = CoinflipCommand(coinflipService)
        every { guild.idLong } returns guildId
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun strOpt(value: String): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asString } returns value
        return o
    }

    private fun intOpt(value: Long): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asLong } returns value
        return o
    }

    @Test
    fun `delegates to CoinflipService with the parsed side and stake`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("side") } returns strOpt("HEADS")
        every { event.getOption("stake") } returns intOpt(50L)
        every { coinflipService.flip(discordId, guildId, 50L, Coinflip.Side.HEADS) } returns
            CoinflipService.FlipOutcome.Lose(
                stake = 50L,
                landed = Coinflip.Side.TAILS,
                predicted = Coinflip.Side.HEADS,
                newBalance = 950L
            )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) {
            coinflipService.flip(discordId, guildId, 50L, Coinflip.Side.HEADS)
        }
    }

    @Test
    fun `replies with embed on each outcome variant`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("side") } returns strOpt("TAILS")
        every { event.getOption("stake") } returns intOpt(100L)

        val outcomes = listOf(
            CoinflipService.FlipOutcome.Win(
                stake = 100L,
                payout = 200L,
                net = 100L,
                landed = Coinflip.Side.TAILS,
                predicted = Coinflip.Side.TAILS,
                newBalance = 1_100L
            ),
            CoinflipService.FlipOutcome.Lose(
                stake = 100L,
                landed = Coinflip.Side.HEADS,
                predicted = Coinflip.Side.TAILS,
                newBalance = 900L
            ),
            CoinflipService.FlipOutcome.InsufficientCredits(stake = 100L, have = 50L),
            CoinflipService.FlipOutcome.InvalidStake(min = 10L, max = 1_000L),
            CoinflipService.FlipOutcome.UnknownUser
        )
        outcomes.forEach { outcome ->
            every { coinflipService.flip(any(), any(), any(), any()) } returns outcome
            command.handle(DefaultCommandContext(event), user, 5)
        }

        verify(atLeast = outcomes.size) {
            event.hook.sendMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>())
        }
    }

    @Test
    fun `missing side option short-circuits without calling the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("side") } returns null
        every { event.getOption("stake") } returns intOpt(100L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { coinflipService.flip(any(), any(), any(), any()) }
    }

    @Test
    fun `unknown side string short-circuits without calling the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("side") } returns strOpt("EDGE")
        every { event.getOption("stake") } returns intOpt(100L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { coinflipService.flip(any(), any(), any(), any()) }
    }
}

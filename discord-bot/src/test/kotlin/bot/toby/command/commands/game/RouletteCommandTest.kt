package bot.toby.command.commands.game

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import common.economy.Roulette
import database.service.RouletteService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class RouletteCommandTest : CommandTest {
    private lateinit var rouletteService: RouletteService
    private lateinit var command: RouletteCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        rouletteService = mockk(relaxed = true)
        command = RouletteCommand(rouletteService)
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
    fun `delegates to RouletteService with parsed bet and stake (outside bet)`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("RED")
        every { event.getOption("stake") } returns intOpt(50L)
        every { event.getOption("number") } returns null
        every { rouletteService.spin(discordId, guildId, 50L, Roulette.Bet.RED, null) } returns
            RouletteService.SpinOutcome.Lose(
                stake = 50L,
                bet = Roulette.Bet.RED,
                landed = 0,
                color = Roulette.Color.GREEN,
                straightNumber = null,
                newBalance = 950L,
            )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) {
            rouletteService.spin(discordId, guildId, 50L, Roulette.Bet.RED, null)
        }
    }

    @Test
    fun `delegates to RouletteService with the picked number on a STRAIGHT bet`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("STRAIGHT")
        every { event.getOption("stake") } returns intOpt(20L)
        every { event.getOption("number") } returns intOpt(17L)
        every { rouletteService.spin(discordId, guildId, 20L, Roulette.Bet.STRAIGHT, 17) } returns
            RouletteService.SpinOutcome.Win(
                stake = 20L,
                bet = Roulette.Bet.STRAIGHT,
                landed = 17,
                color = Roulette.Color.BLACK,
                straightNumber = 17,
                multiplier = 36L,
                payout = 720L,
                net = 700L,
                newBalance = 1_700L,
            )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) {
            rouletteService.spin(discordId, guildId, 20L, Roulette.Bet.STRAIGHT, 17)
        }
    }

    @Test
    fun `STRAIGHT without a number short-circuits without calling the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("STRAIGHT")
        every { event.getOption("stake") } returns intOpt(20L)
        every { event.getOption("number") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { rouletteService.spin(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `STRAIGHT with an out-of-range number short-circuits`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("STRAIGHT")
        every { event.getOption("stake") } returns intOpt(20L)
        every { event.getOption("number") } returns intOpt(99L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { rouletteService.spin(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `replies with embed on each outcome variant`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("RED")
        every { event.getOption("stake") } returns intOpt(100L)
        every { event.getOption("number") } returns null

        val outcomes = listOf(
            RouletteService.SpinOutcome.Win(
                stake = 100L,
                bet = Roulette.Bet.RED,
                landed = 7,
                color = Roulette.Color.RED,
                straightNumber = null,
                multiplier = 2L,
                payout = 200L,
                net = 100L,
                newBalance = 1_100L,
            ),
            RouletteService.SpinOutcome.Lose(
                stake = 100L,
                bet = Roulette.Bet.RED,
                landed = 0,
                color = Roulette.Color.GREEN,
                straightNumber = null,
                newBalance = 900L,
            ),
            RouletteService.SpinOutcome.InsufficientCredits(stake = 100L, have = 50L),
            RouletteService.SpinOutcome.InvalidStake(min = 10L, max = 500L),
            RouletteService.SpinOutcome.InvalidNumber(min = 0, max = 36),
            RouletteService.SpinOutcome.UnknownUser,
        )
        outcomes.forEach { outcome ->
            every { rouletteService.spin(any(), any(), any(), any(), any()) } returns outcome
            command.handle(DefaultCommandContext(event), user, 5)
        }

        verify(atLeast = outcomes.size) {
            event.hook.sendMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>())
        }
    }

    @Test
    fun `missing bet option short-circuits without calling the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns null
        every { event.getOption("stake") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { rouletteService.spin(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unknown bet string short-circuits without calling the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("PURPLE")
        every { event.getOption("stake") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { rouletteService.spin(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing stake option short-circuits without calling the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("RED")
        every { event.getOption("stake") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { rouletteService.spin(any(), any(), any(), any(), any()) }
    }
}

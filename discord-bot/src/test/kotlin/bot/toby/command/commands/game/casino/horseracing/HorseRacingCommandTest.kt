package bot.toby.command.commands.game.casino.horseracing

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import database.dto.user.UserDto
import common.casino.horseracing.HorseRacing
import database.service.casino.horseracing.HorseRacingService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import bot.toby.command.commands.game.casino.horseracing.HorseRacingCommand

internal class HorseRacingCommandTest : CommandTest {
    private lateinit var service: HorseRacingService
    private lateinit var command: HorseRacingCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        service = mockk(relaxed = true)
        command = HorseRacingCommand(service)
        every { guild.idLong } returns guildId
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun strOpt(value: String): OptionMapping = mockk<OptionMapping>(relaxed = true).also {
        every { it.asString } returns value
    }

    private fun intOpt(value: Long): OptionMapping = mockk<OptionMapping>(relaxed = true).also {
        every { it.asLong } returns value
    }

    @Test
    fun `delegates to HorseRacingService with parsed horse, bet, and stake`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("PLACE")
        every { event.getOption("horse") } returns intOpt(3L)
        every { event.getOption("stake") } returns intOpt(50L)
        every { service.race(discordId, guildId, 50L, 3, HorseRacing.Bet.PLACE) } returns
            HorseRacingService.RaceOutcome.Lose(
                stake = 50L,
                bet = HorseRacing.Bet.PLACE,
                pickedHorse = 3,
                finishingOrder = listOf(1, 2, 3, 4, 5, 6),
                newBalance = 950L,
            )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) {
            service.race(discordId, guildId, 50L, 3, HorseRacing.Bet.PLACE)
        }
    }

    @Test
    fun `missing bet option short-circuits without calling the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns null
        every { event.getOption("horse") } returns intOpt(1L)
        every { event.getOption("stake") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { service.race(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unknown bet string short-circuits without calling the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("EXACTA")
        every { event.getOption("horse") } returns intOpt(1L)
        every { event.getOption("stake") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { service.race(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing horse option short-circuits without calling the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("WIN")
        every { event.getOption("horse") } returns null
        every { event.getOption("stake") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { service.race(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing stake option short-circuits without calling the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("WIN")
        every { event.getOption("horse") } returns intOpt(1L)
        every { event.getOption("stake") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { service.race(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `replies with embed on each outcome variant`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("bet") } returns strOpt("WIN")
        every { event.getOption("horse") } returns intOpt(1L)
        every { event.getOption("stake") } returns intOpt(100L)

        val outcomes = listOf(
            HorseRacingService.RaceOutcome.Win(
                stake = 100L,
                bet = HorseRacing.Bet.WIN,
                pickedHorse = 1,
                finishingOrder = listOf(1, 2, 3, 4, 5, 6),
                multiplier = 3.2,
                payout = 320L,
                net = 220L,
                newBalance = 1_220L,
            ),
            HorseRacingService.RaceOutcome.Lose(
                stake = 100L,
                bet = HorseRacing.Bet.WIN,
                pickedHorse = 1,
                finishingOrder = listOf(2, 3, 4, 5, 6, 1),
                newBalance = 900L,
            ),
            HorseRacingService.RaceOutcome.InvalidHorse(min = 1, max = HorseRacing.FIELD_SIZE),
            HorseRacingService.RaceOutcome.InsufficientCredits(stake = 100L, have = 50L),
            HorseRacingService.RaceOutcome.InvalidStake(min = 10L, max = 500L),
            HorseRacingService.RaceOutcome.UnknownUser,
        )
        outcomes.forEach { outcome ->
            every { service.race(any(), any(), any(), any(), any()) } returns outcome
            command.handle(DefaultCommandContext(event), user, 5)
        }

        verify(atLeast = outcomes.size) {
            event.hook.sendMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>())
        }
    }
}

package bot.toby.command.commands.game

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import common.economy.WheelOfFortune
import database.service.casino.wheeloffortune.WheelOfFortuneService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class WheelOfFortuneCommandTest : CommandTest {
    private lateinit var wheelService: WheelOfFortuneService
    private lateinit var command: WheelOfFortuneCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        wheelService = mockk(relaxed = true)
        command = WheelOfFortuneCommand(wheelService)
        every { guild.idLong } returns guildId
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun longOpt(value: Long): OptionMapping = mockk<OptionMapping>(relaxed = true).also {
        every { it.asLong } returns value
    }

    @Test
    fun `delegates to WheelOfFortuneService with pick and stake`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("pick") } returns longOpt(5L)
        every { event.getOption("stake") } returns longOpt(50L)
        every { wheelService.spin(discordId, guildId, 50L, 5L) } returns
            WheelOfFortuneService.SpinOutcome.Lose(
                stake = 50L, pickedMultiplier = 5L, landedMultiplier = 2L,
                newBalance = 950L, lossTribute = 5L,
            )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { wheelService.spin(discordId, guildId, 50L, 5L) }
    }

    @Test
    fun `replies with embed on each outcome variant`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("pick") } returns longOpt(2L)
        every { event.getOption("stake") } returns longOpt(100L)

        val outcomes = listOf<WheelOfFortuneService.SpinOutcome>(
            WheelOfFortuneService.SpinOutcome.Win(
                stake = 100L, pickedMultiplier = 2L, landedMultiplier = 2L,
                payout = 200L, net = 100L, newBalance = 1_100L,
            ),
            WheelOfFortuneService.SpinOutcome.Lose(
                stake = 100L, pickedMultiplier = 2L, landedMultiplier = 5L,
                newBalance = 900L,
            ),
            WheelOfFortuneService.SpinOutcome.InsufficientCredits(stake = 100L, have = 50L),
            WheelOfFortuneService.SpinOutcome.InvalidStake(min = 10L, max = 500L),
            WheelOfFortuneService.SpinOutcome.InvalidPick(picks = WheelOfFortune.PICKS),
            WheelOfFortuneService.SpinOutcome.UnknownUser,
        )
        outcomes.forEach { outcome ->
            every { wheelService.spin(any(), any(), any(), any()) } returns outcome
            command.handle(DefaultCommandContext(event), user, 5)
        }

        verify(atLeast = outcomes.size) {
            event.hook.sendMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>())
        }
    }

    @Test
    fun `missing pick option short-circuits`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("pick") } returns null
        every { event.getOption("stake") } returns longOpt(100L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { wheelService.spin(any(), any(), any(), any()) }
    }

    @Test
    fun `missing stake option short-circuits`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("pick") } returns longOpt(2L)
        every { event.getOption("stake") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { wheelService.spin(any(), any(), any(), any()) }
    }
}

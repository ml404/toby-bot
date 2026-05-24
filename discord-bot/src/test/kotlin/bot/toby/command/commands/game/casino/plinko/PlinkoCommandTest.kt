package bot.toby.command.commands.game.casino.plinko

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import common.economy.Plinko
import database.service.casino.plinko.PlinkoService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import bot.toby.command.commands.game.casino.plinko.PlinkoCommand

internal class PlinkoCommandTest : CommandTest {
    private lateinit var plinkoService: PlinkoService
    private lateinit var command: PlinkoCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        plinkoService = mockk(relaxed = true)
        command = PlinkoCommand(plinkoService)
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

    private fun stringOpt(value: String): OptionMapping = mockk<OptionMapping>(relaxed = true).also {
        every { it.asString } returns value
    }

    @Test
    fun `delegates to PlinkoService with risk and stake`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("risk") } returns stringOpt("MEDIUM")
        every { event.getOption("stake") } returns longOpt(50L)
        every { plinkoService.drop(discordId, guildId, 50L, Plinko.Risk.MEDIUM) } returns
            PlinkoService.DropOutcome.Lose(
                stake = 50L, risk = Plinko.Risk.MEDIUM, bucket = 4, multiplier = 0.0,
                payout = 0L, net = -50L, newBalance = 950L,
            )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { plinkoService.drop(discordId, guildId, 50L, Plinko.Risk.MEDIUM) }
    }

    @Test
    fun `risk is case-insensitive`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("risk") } returns stringOpt("high")
        every { event.getOption("stake") } returns longOpt(100L)
        every { plinkoService.drop(discordId, guildId, 100L, Plinko.Risk.HIGH) } returns
            PlinkoService.DropOutcome.Push(
                stake = 100L, risk = Plinko.Risk.HIGH, bucket = 3, newBalance = 1_000L,
            )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { plinkoService.drop(discordId, guildId, 100L, Plinko.Risk.HIGH) }
    }

    @Test
    fun `unknown risk does not call the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("risk") } returns stringOpt("XTREME")
        every { event.getOption("stake") } returns longOpt(100L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { plinkoService.drop(any(), any(), any(), any()) }
    }

    @Test
    fun `replies with embed on each outcome variant`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("risk") } returns stringOpt("LOW")
        every { event.getOption("stake") } returns longOpt(100L)

        val outcomes = listOf<PlinkoService.DropOutcome>(
            PlinkoService.DropOutcome.Win(
                stake = 100L, risk = Plinko.Risk.LOW, bucket = 0, multiplier = 1.9,
                payout = 190L, net = 90L, newBalance = 1_090L,
            ),
            PlinkoService.DropOutcome.Lose(
                stake = 100L, risk = Plinko.Risk.LOW, bucket = 4, multiplier = 0.4,
                payout = 40L, net = -60L, newBalance = 940L,
            ),
            PlinkoService.DropOutcome.Push(
                stake = 100L, risk = Plinko.Risk.LOW, bucket = 3, newBalance = 1_000L,
            ),
            PlinkoService.DropOutcome.InsufficientCredits(stake = 100L, have = 50L),
            PlinkoService.DropOutcome.InvalidStake(min = 10L, max = 500L),
            PlinkoService.DropOutcome.UnknownUser,
        )
        outcomes.forEach { outcome ->
            every { plinkoService.drop(any(), any(), any(), any()) } returns outcome
            command.handle(DefaultCommandContext(event), user, 5)
        }

        verify(atLeast = outcomes.size) {
            event.hook.sendMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>())
        }
    }

    @Test
    fun `missing risk option short-circuits`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("risk") } returns null
        every { event.getOption("stake") } returns longOpt(100L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { plinkoService.drop(any(), any(), any(), any()) }
    }

    @Test
    fun `missing stake option short-circuits`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("risk") } returns stringOpt("LOW")
        every { event.getOption("stake") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { plinkoService.drop(any(), any(), any(), any()) }
    }
}

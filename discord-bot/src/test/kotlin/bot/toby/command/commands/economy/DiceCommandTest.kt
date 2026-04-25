package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import database.service.DiceService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class DiceCommandTest : CommandTest {
    private lateinit var diceService: DiceService
    private lateinit var command: DiceCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        diceService = mockk(relaxed = true)
        command = DiceCommand(diceService)
        every { guild.idLong } returns guildId
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

    @Test
    fun `delegates to DiceService with prediction and stake`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("prediction") } returns intOpt(3L)
        every { event.getOption("stake") } returns intOpt(50L)
        every { diceService.roll(discordId, guildId, 50L, 3) } returns DiceService.RollOutcome.Lose(
            stake = 50L, landed = 5, predicted = 3, newBalance = 950L
        )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { diceService.roll(discordId, guildId, 50L, 3) }
    }

    @Test
    fun `replies with embed on each outcome variant`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("prediction") } returns intOpt(4L)
        every { event.getOption("stake") } returns intOpt(100L)

        val outcomes = listOf(
            DiceService.RollOutcome.Win(stake = 100L, payout = 500L, net = 400L, landed = 4, predicted = 4, newBalance = 1_400L),
            DiceService.RollOutcome.Lose(stake = 100L, landed = 1, predicted = 4, newBalance = 900L),
            DiceService.RollOutcome.InsufficientCredits(stake = 100L, have = 50L),
            DiceService.RollOutcome.InvalidStake(min = 10L, max = 500L),
            DiceService.RollOutcome.InvalidPrediction(min = 1, max = 6),
            DiceService.RollOutcome.UnknownUser
        )
        outcomes.forEach { outcome ->
            every { diceService.roll(any(), any(), any(), any()) } returns outcome
            command.handle(DefaultCommandContext(event), user, 5)
        }

        verify(atLeast = outcomes.size) {
            event.hook.sendMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>())
        }
    }

    @Test
    fun `missing prediction option short-circuits`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("prediction") } returns null
        every { event.getOption("stake") } returns intOpt(100L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { diceService.roll(any(), any(), any(), any()) }
    }
}

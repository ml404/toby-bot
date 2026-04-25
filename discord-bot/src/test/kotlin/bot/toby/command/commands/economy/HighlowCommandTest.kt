package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import database.economy.Highlow
import database.service.HighlowService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class HighlowCommandTest : CommandTest {
    private lateinit var highlowService: HighlowService
    private lateinit var command: HighlowCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        highlowService = mockk(relaxed = true)
        command = HighlowCommand(highlowService)
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
    fun `delegates to HighlowService with parsed direction and stake`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("direction") } returns strOpt("HIGHER")
        every { event.getOption("stake") } returns intOpt(50L)
        every { highlowService.play(discordId, guildId, 50L, Highlow.Direction.HIGHER) } returns
            HighlowService.PlayOutcome.Lose(stake = 50L, anchor = 7, next = 7, direction = Highlow.Direction.HIGHER, newBalance = 950L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) {
            highlowService.play(discordId, guildId, 50L, Highlow.Direction.HIGHER)
        }
    }

    @Test
    fun `replies with embed on each outcome variant`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("direction") } returns strOpt("LOWER")
        every { event.getOption("stake") } returns intOpt(100L)

        val outcomes = listOf(
            HighlowService.PlayOutcome.Win(stake = 100L, payout = 200L, net = 100L,
                anchor = 10, next = 3, direction = Highlow.Direction.LOWER, newBalance = 1_100L),
            HighlowService.PlayOutcome.Lose(stake = 100L, anchor = 5, next = 10,
                direction = Highlow.Direction.LOWER, newBalance = 900L),
            HighlowService.PlayOutcome.InsufficientCredits(stake = 100L, have = 50L),
            HighlowService.PlayOutcome.InvalidStake(min = 10L, max = 500L),
            HighlowService.PlayOutcome.UnknownUser
        )
        outcomes.forEach { outcome ->
            every { highlowService.play(any(), any(), any(), any()) } returns outcome
            command.handle(DefaultCommandContext(event), user, 5)
        }

        verify(atLeast = outcomes.size) {
            event.hook.sendMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>())
        }
    }

    @Test
    fun `unknown direction short-circuits`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("direction") } returns strOpt("SIDEWAYS")
        every { event.getOption("stake") } returns intOpt(100L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { highlowService.play(any(), any(), any(), any()) }
    }
}

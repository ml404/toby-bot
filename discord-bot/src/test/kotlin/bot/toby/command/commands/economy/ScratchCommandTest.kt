package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import database.economy.SlotMachine
import database.service.ScratchService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class ScratchCommandTest : CommandTest {
    private lateinit var scratchService: ScratchService
    private lateinit var command: ScratchCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        scratchService = mockk(relaxed = true)
        command = ScratchCommand(scratchService)
        every { guild.idLong } returns guildId
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun intOpt(value: Long): OptionMapping = mockk<OptionMapping>(relaxed = true).also {
        every { it.asLong } returns value
    }

    @Test
    fun `delegates to ScratchService with stake`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(50L)
        every { scratchService.scratch(discordId, guildId, 50L) } returns
            ScratchService.ScratchOutcome.Lose(
                stake = 50L,
                cells = List(5) { SlotMachine.Symbol.CHERRY },
                newBalance = 950L
            )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { scratchService.scratch(discordId, guildId, 50L) }
    }

    @Test
    fun `replies with embed on each outcome variant`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(100L)

        val outcomes = listOf(
            ScratchService.ScratchOutcome.Win(
                stake = 100L, payout = 3_000L, net = 2_900L,
                cells = List(5) { SlotMachine.Symbol.STAR },
                winningSymbol = SlotMachine.Symbol.STAR,
                matchCount = 5,
                newBalance = 3_900L
            ),
            ScratchService.ScratchOutcome.Lose(
                stake = 100L,
                cells = listOf(
                    SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.LEMON,
                    SlotMachine.Symbol.BELL, SlotMachine.Symbol.STAR, SlotMachine.Symbol.CHERRY
                ),
                newBalance = 900L
            ),
            ScratchService.ScratchOutcome.InsufficientCredits(stake = 100L, have = 50L),
            ScratchService.ScratchOutcome.InvalidStake(min = 10L, max = 500L),
            ScratchService.ScratchOutcome.UnknownUser
        )
        outcomes.forEach { outcome ->
            every { scratchService.scratch(any(), any(), any()) } returns outcome
            command.handle(DefaultCommandContext(event), user, 5)
        }

        verify(atLeast = outcomes.size) {
            event.hook.sendMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>())
        }
    }

    @Test
    fun `missing stake option short-circuits`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { scratchService.scratch(any(), any(), any()) }
    }
}

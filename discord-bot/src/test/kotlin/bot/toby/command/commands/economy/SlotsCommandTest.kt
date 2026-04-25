package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import database.economy.SlotMachine
import database.service.SlotsService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SlotsCommandTest : CommandTest {
    private lateinit var slotsService: SlotsService
    private lateinit var command: SlotsCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        slotsService = mockk(relaxed = true)
        command = SlotsCommand(slotsService)
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
    fun `delegates to SlotsService with the stake option`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(50L)
        every { slotsService.spin(discordId, guildId, 50L) } returns SlotsService.SpinOutcome.Lose(
            stake = 50L,
            symbols = listOf(SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.LEMON, SlotMachine.Symbol.BELL),
            newBalance = 950L
        )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { slotsService.spin(discordId, guildId, 50L) }
    }

    @Test
    fun `replies with embed on each outcome variant`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(100L)

        // Each outcome should produce an embed reply through event.hook.
        // Using relaxed mocks: just verify replyEmbeds is invoked.
        val outcomes = listOf(
            SlotsService.SpinOutcome.Win(
                stake = 100L,
                multiplier = 5L,
                payout = 500L,
                net = 400L,
                symbols = listOf(
                    SlotMachine.Symbol.CHERRY,
                    SlotMachine.Symbol.CHERRY,
                    SlotMachine.Symbol.CHERRY
                ),
                newBalance = 1_400L
            ),
            SlotsService.SpinOutcome.Lose(
                stake = 100L,
                symbols = listOf(
                    SlotMachine.Symbol.CHERRY,
                    SlotMachine.Symbol.LEMON,
                    SlotMachine.Symbol.STAR
                ),
                newBalance = 900L
            ),
            SlotsService.SpinOutcome.InsufficientCredits(stake = 100L, have = 50L),
            SlotsService.SpinOutcome.InvalidStake(min = 10L, max = 500L),
            SlotsService.SpinOutcome.UnknownUser
        )
        outcomes.forEach { outcome ->
            every { slotsService.spin(any(), any(), any()) } returns outcome
            command.handle(DefaultCommandContext(event), user, 5)
        }

        verify(atLeast = outcomes.size) { event.hook.sendMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>()) }
    }

    @Test
    fun `missing stake option short-circuits without calling the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { slotsService.spin(any(), any(), any()) }
    }
}

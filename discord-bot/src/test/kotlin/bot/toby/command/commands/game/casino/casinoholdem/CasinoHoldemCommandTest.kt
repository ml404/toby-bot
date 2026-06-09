package bot.toby.command.commands.game.casino.casinoholdem

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import database.poker.CasinoHoldemTableRegistry
import database.service.casino.casinoholdem.CasinoHoldemService
import database.service.casino.casinoholdem.CasinoHoldemService.DealOutcome
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class CasinoHoldemCommandTest : CommandTest {

    private lateinit var service: CasinoHoldemService
    private lateinit var tableRegistry: CasinoHoldemTableRegistry
    private lateinit var command: CasinoHoldemCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        service = mockk(relaxed = true)
        tableRegistry = mockk(relaxed = true)
        command = CasinoHoldemCommand(service, tableRegistry)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun intOpt(v: Long): OptionMapping = mockk(relaxed = true) { every { asLong } returns v }

    @Test
    fun `outside a guild it does not deal`() {
        every { event.guild } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify(exactly = 0) { service.dealSolo(any(), any(), any(), any()) }
    }

    @Test
    fun `missing stake does not deal`() {
        every { event.getOption("stake") } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify(exactly = 0) { service.dealSolo(any(), any(), any(), any()) }
    }

    @Test
    fun `delegates to the service with the parsed stake and auto-topup defaulting to false`() {
        every { event.getOption("stake") } returns intOpt(100L)
        every { event.getOption("auto_topup") } returns null
        every { service.dealSolo(1L, 1L, 100L, false) } returns DealOutcome.InvalidStake(10L, 500L)

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify(exactly = 1) { service.dealSolo(1L, 1L, 100L, false) }
    }

    @Test
    fun `failure outcomes reply with an embed`() {
        every { event.getOption("stake") } returns intOpt(100L)
        val failures = listOf(
            DealOutcome.InvalidStake(10L, 500L),
            DealOutcome.InsufficientCredits(stake = 100L, have = 5L),
            DealOutcome.InsufficientCoinsForTopUp(needed = 50L, have = 5L),
            DealOutcome.UnknownUser,
        )

        failures.forEach { outcome ->
            every { service.dealSolo(any(), any(), any(), any()) } returns outcome
            command.handle(DefaultCommandContext(event), requestingUserDto, 5)
        }

        verify(atLeast = failures.size) {
            event.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg<MessageEmbed>())
        }
    }

    @Test
    fun `a dealt hand whose table has vanished replies with an error`() {
        every { event.getOption("stake") } returns intOpt(100L)
        every { service.dealSolo(any(), any(), any(), any()) } returns
            DealOutcome.Dealt(tableId = 7L, snapshot = mockk(relaxed = true), newBalance = 0L)
        every { tableRegistry.get(7L) } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg<MessageEmbed>()) }
    }
}

package bot.toby.command.commands.moderation

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.interactionHook
import bot.toby.command.CommandTest.Companion.replyCallbackAction
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import database.service.CasinoAdminService
import database.service.JackpotLotteryService
import database.service.JackpotService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Focused coverage for the `/jackpotadmin lottery_draw` reply
 * formatting after the incentives work. The handler must surface a
 * "bonus impact" line when [JackpotLotteryService.DrawOutcome.Ok]
 * carries non-zero [bonusTicketsAwarded] / [highestMilestoneFired]
 * and stay clean when neither moved. Other admin subcommands are
 * intentionally out of scope here — they're untouched by this PR.
 */
internal class JackpotAdminCommandTest : CommandTest {

    private val guildId = 42L
    private lateinit var casinoAdminService: CasinoAdminService
    private lateinit var jackpotService: JackpotService
    private lateinit var jackpotLotteryService: JackpotLotteryService
    private lateinit var command: JackpotAdminCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        casinoAdminService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        jackpotLotteryService = mockk(relaxed = true)
        command = JackpotAdminCommand(casinoAdminService, jackpotService, jackpotLotteryService)
        every { guild.idLong } returns guildId
        every { event.subcommandName } returns "lottery_draw"
        // The lottery_draw handler defers ephemerally; the base sets up
        // deferReply() but not the boolean overload.
        every { event.deferReply(true) } returns replyCallbackAction
        // requestingUserDto.superUser is already stubbed true in the base.
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    private fun captureReply(): io.mockk.CapturingSlot<String> {
        val slot = slot<String>()
        every { interactionHook.sendMessage(capture(slot)) } returns webhookMessageCreateAction
        return slot
    }

    @Test
    fun `lottery_draw reply omits the bonus-impact line when neither bonuses nor milestones fired`() {
        every { jackpotLotteryService.drawLottery(guildId) } returns
            JackpotLotteryService.DrawOutcome.Ok(
                payouts = listOf(
                    JackpotLotteryService.WinnerPayout(discordId = 1L, ticketCount = 5, amount = 800L),
                ),
                totalPaid = 800L,
                drained = 1_000L,
            )
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        val reply = captured.captured
        assertTrue(reply.contains("Lottery drawn"), reply)
        assertTrue(reply.contains("Total paid: **800**"), reply)
        assertFalse(reply.contains("🎁"), "no bonus emoji when nothing granted: $reply")
        assertFalse(reply.contains("🚀"), "no milestone emoji when nothing fired: $reply")
    }

    @Test
    fun `lottery_draw reply surfaces bonus tickets awarded when DrawOutcome reports them`() {
        every { jackpotLotteryService.drawLottery(guildId) } returns
            JackpotLotteryService.DrawOutcome.Ok(
                payouts = listOf(
                    JackpotLotteryService.WinnerPayout(discordId = 1L, ticketCount = 30, amount = 800L),
                ),
                totalPaid = 800L,
                drained = 1_000L,
                bonusTicketsAwarded = 11L,
            )
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        val reply = captured.captured
        assertTrue(reply.contains("🎁 11 bulk bonus tickets awarded"), reply)
        assertFalse(reply.contains("🚀"), "milestone emoji only when one fired: $reply")
    }

    @Test
    fun `lottery_draw reply surfaces milestone fired up to highest threshold`() {
        every { jackpotLotteryService.drawLottery(guildId) } returns
            JackpotLotteryService.DrawOutcome.Ok(
                payouts = listOf(
                    JackpotLotteryService.WinnerPayout(discordId = 1L, ticketCount = 50, amount = 1_200L),
                ),
                totalPaid = 1_200L,
                drained = 1_500L,
                highestMilestoneFired = 100L,
            )
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        val reply = captured.captured
        assertTrue(reply.contains("🚀 milestone up to 100 tickets fired"), reply)
    }

    @Test
    fun `lottery_draw reply combines both impact bits on one separator line`() {
        every { jackpotLotteryService.drawLottery(guildId) } returns
            JackpotLotteryService.DrawOutcome.Ok(
                payouts = emptyList(),
                totalPaid = 0L,
                drained = 0L,
                bonusTicketsAwarded = 7L,
                highestMilestoneFired = 50L,
            )
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        val reply = captured.captured
        // Both bits live on the same impact line separated by ' · ',
        // so admins read one summary instead of two trailing lines.
        assertTrue(reply.contains("🎁 7 bulk bonus tickets awarded · 🚀 milestone up to 50 tickets fired"), reply)
    }

    @Test
    fun `lottery_draw reply on BelowMinBuyers surfaces the have-need counts and gate hint`() {
        every { jackpotLotteryService.drawLottery(guildId) } returns
            JackpotLotteryService.DrawOutcome.BelowMinBuyers(have = 1, need = 2)
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        val reply = captured.captured
        assertTrue(reply.contains("**1** distinct buyer"), reply)
        assertTrue(reply.contains("need **2**"), reply)
        assertTrue(reply.contains("LOTTERY_DAILY_MIN_BUYERS"), reply)
    }
}

package bot.toby.command.commands.moderation

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.replyCallbackAction
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import bot.toby.scheduling.LotteryAnnouncer
import database.dto.lottery.JackpotLotteryDto
import database.dto.user.UserDto
import database.service.casino.CasinoAdminService
import database.service.lottery.JackpotLotteryService
import database.service.economy.JackpotService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
    private lateinit var lotteryAnnouncer: LotteryAnnouncer
    private lateinit var command: JackpotAdminCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        casinoAdminService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        jackpotLotteryService = mockk(relaxed = true)
        lotteryAnnouncer = mockk(relaxed = true)
        command = JackpotAdminCommand(
            casinoAdminService, jackpotService, jackpotLotteryService, lotteryAnnouncer,
        )
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
        // Mirror LevelCommandTest's working pattern — stub the chained
        // `event.hook.sendMessage(...)` rather than the companion-level
        // `interactionHook` mock. Capture works for both
        // replyAndDelete (success) and replyEphemeralAndDelete (errors)
        // because both route through `sendMessage(message)` on the hook.
        val slot = slot<String>()
        every { event.hook.sendMessage(capture(slot)) } returns webhookMessageCreateAction
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

    // ---- lottery_refresh_embed ----

    private fun openLotteryRow(
        id: Long = 1L,
        messageId: Long? = 999L,
        channelId: Long? = 777L,
        mode: String = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
    ): JackpotLotteryDto = JackpotLotteryDto(
        id = id,
        guildId = guildId,
        ticketPrice = 50L,
        poolAmount = 1_000L,
        mode = mode,
    ).also {
        it.announcementMessageId = messageId
        it.announcementChannelId = channelId
    }

    @Test
    fun `lottery_refresh_embed replies 'No open lottery' when nothing is open`() {
        every { event.subcommandName } returns "lottery_refresh_embed"
        every { jackpotLotteryService.getOpenLotteriesForRefresh(guildId) } returns emptyList()
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        assertTrue(captured.captured.contains("No open lottery to refresh"), captured.captured)
        verify(exactly = 0) { lotteryAnnouncer.refreshAnnouncement(any(), any(), any()) }
    }

    @Test
    fun `lottery_refresh_embed calls refreshAnnouncement with force=true for each open lottery`() {
        // Both a TICKET_WEIGHTED and a NUMBER_MATCH open lottery —
        // the manual trigger refreshes every open row for the guild,
        // not just weighted. (NUMBER_MATCH embeds don't carry the
        // incentives field, but they still benefit from a forced
        // pool refresh.)
        every { event.subcommandName } returns "lottery_refresh_embed"
        val weighted = openLotteryRow(id = 1L, mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        val match = openLotteryRow(id = 2L, mode = JackpotLotteryDto.MODE_NUMBER_MATCH)
        every { jackpotLotteryService.getOpenLotteriesForRefresh(guildId) } returns listOf(weighted, match)
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify(exactly = 1) {
            lotteryAnnouncer.refreshAnnouncement(guild, weighted, force = true)
        }
        verify(exactly = 1) {
            lotteryAnnouncer.refreshAnnouncement(guild, match, force = true)
        }
        assertTrue(captured.captured.contains("Queued **2**"), captured.captured)
    }

    @Test
    fun `lottery_refresh_embed counts lotteries with no announcement reference as skipped`() {
        // One lottery has a message id; the other never announced
        // (admin opened it but the channel resolution failed at the
        // time, or the announce ref was cleared by UNKNOWN_MESSAGE).
        // The reply should clearly distinguish queued vs skipped.
        every { event.subcommandName } returns "lottery_refresh_embed"
        val withRef = openLotteryRow(id = 1L)
        val noRef = openLotteryRow(id = 2L, messageId = null, channelId = null)
        every { jackpotLotteryService.getOpenLotteriesForRefresh(guildId) } returns listOf(withRef, noRef)
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify(exactly = 1) {
            lotteryAnnouncer.refreshAnnouncement(guild, withRef, force = true)
        }
        verify(exactly = 0) {
            lotteryAnnouncer.refreshAnnouncement(guild, noRef, any())
        }
        val reply = captured.captured
        assertTrue(reply.contains("Queued **1**"), reply)
        assertTrue(reply.contains("Skipped **1**"), reply)
        assertTrue(reply.contains("no announce posted yet"), reply)
    }

    @Test
    fun `lottery_refresh_embed reply uses correct pluralisation for a single refresh`() {
        // Cosmetic but visible: "Queued 1 embed refresh." not
        // "Queued 1 embed refreshes." Pin the singular form so a
        // future copy edit doesn't regress it.
        every { event.subcommandName } returns "lottery_refresh_embed"
        every { jackpotLotteryService.getOpenLotteriesForRefresh(guildId) } returns
            listOf(openLotteryRow(id = 1L))
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        val reply = captured.captured
        assertTrue(reply.contains("Queued **1** embed refresh."), reply)
        assertFalse(reply.contains("refreshes"), "single refresh should use the singular form: $reply")
    }

    @Test
    fun `lottery_refresh_embed is owner-or-superuser-only`() {
        // Mirror the existing auth-gate behaviour from the other
        // lottery subcommands: a non-owner / non-superuser gets the
        // ephemeral error and no service calls fire.
        every { event.subcommandName } returns "lottery_refresh_embed"
        val plainUser = mockk<UserDto>(relaxed = true) {
            every { superUser } returns false
        }
        every { event.member?.isOwner } returns false
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), plainUser, 5)

        assertEquals("Server owner or superuser only.", captured.captured)
        verify(exactly = 0) { jackpotLotteryService.getOpenLotteriesForRefresh(any()) }
        verify(exactly = 0) { lotteryAnnouncer.refreshAnnouncement(any(), any(), any()) }
    }
}

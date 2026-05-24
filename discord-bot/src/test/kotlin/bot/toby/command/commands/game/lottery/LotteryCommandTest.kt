package bot.toby.command.commands.game.lottery

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import database.dto.guild.ConfigDto
import database.dto.lottery.JackpotLotteryDto
import database.dto.lottery.JackpotLotteryTicketDto
import database.dto.user.UserDto
import database.service.guild.ConfigService
import database.service.lottery.JackpotLotteryService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import bot.toby.command.commands.game.lottery.LotteryCommand

/**
 * Covers the player-facing surface for the participation-incentive
 * work: `/lottery buy` must reflect any bulk-bonus and milestone
 * payouts in the reply text, and `/lottery status` must surface the
 * active tiers + the user's next thresholds so they can see the rules
 * without an admin posting them. The underlying `LotteryHelper`
 * accessors are unit-tested in `LotteryHelperTest`; here we pin the
 * formatting glue that turns those tuples into chat-readable text.
 */
internal class LotteryCommandTest : CommandTest {

    private val guildId = 42L
    private val discordId = 1L
    private lateinit var jackpotLotteryService: JackpotLotteryService
    private lateinit var configService: ConfigService
    private lateinit var command: LotteryCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        jackpotLotteryService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        command = LotteryCommand(jackpotLotteryService, configService)
        every { guild.idLong } returns guildId
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    private fun intOpt(value: Long): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asLong } returns value
        return o
    }

    private fun stubBuySubcommand(count: Long) {
        every { event.subcommandName } returns "buy"
        every { event.getOption("count") } returns intOpt(count)
    }

    private fun stubStatusSubcommand() {
        every { event.subcommandName } returns "status"
    }

    private fun stubConfig(key: ConfigDto.Configurations, value: String?) {
        every {
            configService.getConfigByName(key.configValue, guildId.toString())
        } returns value?.let {
            ConfigDto(name = key.configValue, value = it, guildId = guildId.toString())
        }
    }

    private fun captureReply(): io.mockk.CapturingSlot<String> {
        // Mirror LevelCommandTest's working pattern: stub the chained
        // `event.hook.sendMessage(...)` rather than going through the
        // companion-level `interactionHook` mock. Both `replyAndDelete`
        // and `replyEphemeralAndDelete` route through `sendMessage(...)`
        // on the InteractionHook receiver, so a single capture covers
        // both success and error paths.
        val slot = slot<String>()
        every { event.hook.sendMessage(capture(slot)) } returns webhookMessageCreateAction
        return slot
    }

    @Test
    fun `buy reply renders cleanly when no incentives are configured`() {
        stubBuySubcommand(count = 5)
        every {
            jackpotLotteryService.buyTickets(guildId, discordId, 5)
        } returns JackpotLotteryService.BuyOutcome.Ok(
            ticketCount = 5,
            totalSpent = 500L,
            newBalance = 9_500L,
            newPool = 1_500L,
        )
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), UserDto(discordId = discordId, guildId = guildId), 5)

        val reply = captured.captured
        assertTrue(reply.contains("Bought **5** tickets"), reply)
        assertTrue(reply.contains("**5** tickets") || reply.contains("5** tickets"), reply)
        assertTrue(reply.contains("1500"), "pool surfaced: $reply")
        assertTrue(reply.contains("9500"), "balance surfaced: $reply")
        assertFalse(reply.contains("Bulk-buy bonus"), "no bonus copy when nothing granted: $reply")
        assertFalse(reply.contains("Milestone reached"), "no milestone copy when nothing fired: $reply")
    }

    @Test
    fun `buy reply surfaces bulk bonus tickets when granted`() {
        stubBuySubcommand(count = 10)
        every {
            jackpotLotteryService.buyTickets(guildId, discordId, 10)
        } returns JackpotLotteryService.BuyOutcome.Ok(
            ticketCount = 10,
            totalSpent = 1_000L,
            newBalance = 9_000L,
            newPool = 2_000L,
            bonusTicketsGranted = 3L,
            totalBonusTickets = 3L,
        )
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), UserDto(discordId = discordId, guildId = guildId), 5)

        val reply = captured.captured
        assertTrue(reply.contains("Bulk-buy bonus"), "bonus callout present: $reply")
        assertTrue(reply.contains("+3"), "bonus count surfaced: $reply")
        // The total owned line should also include the bonus breakdown.
        assertTrue(reply.contains("paid + **3** bonus"), reply)
    }

    @Test
    fun `buy reply surfaces milestone top-up when a milestone fires`() {
        stubBuySubcommand(count = 50)
        every {
            jackpotLotteryService.buyTickets(guildId, discordId, 50)
        } returns JackpotLotteryService.BuyOutcome.Ok(
            ticketCount = 50,
            totalSpent = 5_000L,
            newBalance = 5_000L,
            newPool = 5_100L,
            milestoneBonuses = listOf(
                JackpotLotteryService.MilestoneBonus(threshold = 50L, creditsAdded = 100L),
            ),
        )
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), UserDto(discordId = discordId, guildId = guildId), 5)

        val reply = captured.captured
        assertTrue(reply.contains("Milestone reached"), reply)
        assertTrue(reply.contains("**50** tickets sold"), reply)
        assertTrue(reply.contains("**+100** credits"), reply)
    }

    @Test
    fun `buy reply forwards each failure outcome to an ephemeral error`() {
        // Each failure variant should result in an ephemeral reply
        // through `replyEphemeralAndDelete` → `sendMessage`; we capture
        // every send and assert the variant-specific copy made it
        // through. A regression that swallows one (e.g. `UnknownUser`)
        // would leave that bucket silent in prod.
        val outcomes = mapOf<JackpotLotteryService.BuyOutcome, String>(
            JackpotLotteryService.BuyOutcome.NoOpenLottery to "No lottery is open",
            JackpotLotteryService.BuyOutcome.InvalidCount(0) to "must be positive",
            JackpotLotteryService.BuyOutcome.Insufficient(have = 10L, need = 100L) to
                "only have **10** credits",
            JackpotLotteryService.BuyOutcome.UnknownUser to "No user record",
        )
        for ((outcome, expected) in outcomes) {
            stubBuySubcommand(count = 1)
            every { jackpotLotteryService.buyTickets(guildId, discordId, 1) } returns outcome
            val captured = captureReply()

            command.handle(DefaultCommandContext(event), UserDto(discordId = discordId, guildId = guildId), 5)

            assertTrue(
                captured.captured.contains(expected, ignoreCase = true),
                "$outcome should produce a reply containing '$expected', got: ${captured.captured}",
            )
        }
    }

    @Test
    fun `status reply renders nothing-active block when no incentives configured`() {
        stubStatusSubcommand()
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
            openedAt = Instant.now(), closesAt = Instant.now().plusSeconds(3_600),
        )
        every { jackpotLotteryService.ticketsForOpenWeighted(guildId) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = discordId, ticketCount = 5, spent = 500L),
        )
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), UserDto(discordId = discordId, guildId = guildId), 5)

        val reply = captured.captured
        assertTrue(reply.contains("Lottery status"), reply)
        assertTrue(reply.contains("Your tickets"), reply)
        assertFalse(
            reply.contains("Active incentives"),
            "no Active incentives block when nothing is configured: $reply",
        )
    }

    @Test
    fun `status reply surfaces active tiers and the user's next bulk threshold`() {
        stubStatusSubcommand()
        stubConfig(ConfigDto.Configurations.LOTTERY_BULK_TIER1_BUY, "10")
        stubConfig(ConfigDto.Configurations.LOTTERY_BULK_TIER1_BONUS, "3")
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
            openedAt = Instant.now(), closesAt = Instant.now().plusSeconds(3_600),
        )
        // User holds 4 tickets — 6 short of the bulk threshold of 10.
        every { jackpotLotteryService.ticketsForOpenWeighted(guildId) } returns listOf(
            JackpotLotteryTicketDto(lotteryId = 1L, discordId = discordId, ticketCount = 4, spent = 400L),
        )
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), UserDto(discordId = discordId, guildId = guildId), 5)

        val reply = captured.captured
        assertTrue(reply.contains("Active incentives"), reply)
        assertTrue(reply.contains("Bulk bonus"), reply)
        assertTrue(reply.contains("buy ≥10 → +3 free"), reply)
        // Next-threshold hint: 6 more to hit tier 1.
        assertTrue(
            reply.contains("buy 6 more in one purchase for +3"),
            "next-threshold hint surfaced: $reply",
        )
    }

    @Test
    fun `status reply lists multiplier and milestone tiers when configured`() {
        stubStatusSubcommand()
        stubConfig(ConfigDto.Configurations.LOTTERY_MULT_TIER1_TOTAL, "15")
        stubConfig(ConfigDto.Configurations.LOTTERY_MULT_TIER1_BP, "15000")
        stubConfig(ConfigDto.Configurations.LOTTERY_MILESTONE1_TICKETS, "50")
        stubConfig(ConfigDto.Configurations.LOTTERY_MILESTONE1_PCT, "10")
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
            openedAt = Instant.now(), closesAt = Instant.now().plusSeconds(3_600),
        )
        every { jackpotLotteryService.ticketsForOpenWeighted(guildId) } returns emptyList()
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), UserDto(discordId = discordId, guildId = guildId), 5)

        val reply = captured.captured
        assertTrue(reply.contains("Volume multiplier"), reply)
        assertTrue(reply.contains("hold ≥15 → 1.50×"), "multiplier rendered with two decimals: $reply")
        assertTrue(reply.contains("Pool milestones"), reply)
        assertTrue(reply.contains("@50 tickets → +10% of jackpot"), reply)
    }

    @Test
    fun `status reply surfaces user's bonus tickets in the owned-tickets line`() {
        stubStatusSubcommand()
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 1L, guildId = guildId, ticketPrice = 100L, poolAmount = 1_000L,
            winnerCount = 3, status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
            openedAt = Instant.now(), closesAt = Instant.now().plusSeconds(3_600),
        )
        every { jackpotLotteryService.ticketsForOpenWeighted(guildId) } returns listOf(
            JackpotLotteryTicketDto(
                lotteryId = 1L, discordId = discordId, ticketCount = 12, spent = 1_200L,
                bonusTickets = 4L,
            ),
        )
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), UserDto(discordId = discordId, guildId = guildId), 5)

        val reply = captured.captured
        assertTrue(reply.contains("**12** paid + **4** bonus"), reply)
    }

    @Test
    fun `status reply with no open lottery returns the no-lottery copy`() {
        stubStatusSubcommand()
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns null
        val captured = captureReply()

        command.handle(DefaultCommandContext(event), UserDto(discordId = discordId, guildId = guildId), 5)

        assertTrue(captured.captured.contains("No lottery is open"))
        verify(exactly = 0) { jackpotLotteryService.ticketsForOpenWeighted(any()) }
    }
}

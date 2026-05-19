package bot.toby.scheduling

import database.dto.ConfigDto
import database.dto.JackpotLotteryDto
import database.service.ConfigService
import database.service.JackpotLotteryService
import database.service.LotteryDailyService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

class LotteryDailyJobTest {

    private lateinit var jda: JDA
    private lateinit var configService: ConfigService
    private lateinit var lotteryDailyService: LotteryDailyService
    private lateinit var jackpotLotteryService: JackpotLotteryService
    private lateinit var lotteryAnnouncer: LotteryAnnouncer
    private lateinit var guild: Guild
    private lateinit var job: LotteryDailyJob

    private val guildId = 100L
    private val today: LocalDate = LocalDate.of(2026, 5, 1)
    private val clock: Clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        lotteryDailyService = mockk(relaxed = true)
        jackpotLotteryService = mockk(relaxed = true)
        lotteryAnnouncer = mockk(relaxed = true)
        guild = mockk(relaxed = true)

        val cache: SnowflakeCacheView<Guild> = mockk(relaxed = true)
        every { jda.guildCache } returns cache
        every { cache.iterator() } returns mutableListOf(guild).iterator()
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()

        job = LotteryDailyJob(
            jda, configService, lotteryDailyService, jackpotLotteryService,
            lotteryAnnouncer, clock,
        )
    }

    private fun stubEnabled(enabled: Boolean) {
        val dto = ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_DAILY_ENABLED.configValue,
            value = enabled.toString(),
            guildId = guildId.toString(),
        )
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_DAILY_ENABLED.configValue,
                guildId.toString(),
            )
        } returns dto
    }

    private fun stubMode(mode: String) {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.LOTTERY_DAILY_MODE.configValue,
                guildId.toString(),
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.LOTTERY_DAILY_MODE.configValue,
            value = mode,
            guildId = guildId.toString(),
        )
    }

    @Test
    fun `runDaily skips guilds with daily lottery disabled`() {
        stubEnabled(false)

        job.runDaily()

        verify(exactly = 0) { jackpotLotteryService.openMatchLottery(any(), any(), any(), any()) }
        verify(exactly = 0) { jackpotLotteryService.drawMatchLottery(any()) }
        verify(exactly = 0) { lotteryDailyService.markRan(any(), any()) }
    }

    @Test
    fun `runDaily skips guilds whose ledger already records a run today`() {
        stubEnabled(true)
        every { lotteryDailyService.alreadyRan(guildId, today) } returns true

        job.runDaily()

        verify(exactly = 0) { jackpotLotteryService.openMatchLottery(any(), any(), any(), any()) }
        verify(exactly = 0) { jackpotLotteryService.drawMatchLottery(any()) }
    }

    @Test
    fun `runDaily closes prior open draw and opens a fresh one`() {
        stubEnabled(true)
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        // Open match exists.
        every { jackpotLotteryService.getOpenMatch(guildId) } returns JackpotLotteryDto(
            id = 5L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_NUMBER_MATCH,
        )
        every { jackpotLotteryService.drawMatchLottery(guildId) } returns
            JackpotLotteryService.DrawMatchOutcome.Ok(
                drawnNumbers = listOf(1, 2, 3, 4, 5),
                tierPayouts = emptyList(),
                totalPaid = 0L,
                drained = 0L,
                rolledBackToJackpot = 0L,
            )
        every { jackpotLotteryService.openMatchLottery(guildId, any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.Ok(
                lottery = JackpotLotteryDto(id = 6L, guildId = guildId),
                seeded = 100L,
            )

        job.runDaily()

        verify(exactly = 1) { jackpotLotteryService.drawMatchLottery(guildId) }
        verify(exactly = 1) { jackpotLotteryService.openMatchLottery(guildId, any(), any(), 24L) }
        verify(exactly = 1) { lotteryDailyService.markRan(guildId, today) }
    }

    @Test
    fun `runDaily cancels the prior open draw when no tickets were bought`() {
        stubEnabled(true)
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        every { jackpotLotteryService.getOpenMatch(guildId) } returns JackpotLotteryDto(
            id = 5L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_NUMBER_MATCH,
        )
        every { jackpotLotteryService.drawMatchLottery(guildId) } returns
            JackpotLotteryService.DrawMatchOutcome.NoTickets
        every { jackpotLotteryService.openMatchLottery(guildId, any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.Ok(
                lottery = JackpotLotteryDto(id = 6L, guildId = guildId),
                seeded = 100L,
            )

        job.runDaily()

        verify(exactly = 1) { jackpotLotteryService.cancelMatchLottery(guildId) }
        verify(exactly = 1) { jackpotLotteryService.openMatchLottery(guildId, any(), any(), any()) }
        verify(exactly = 1) { lotteryDailyService.markRan(guildId, today) }
    }

    @Test
    fun `runDaily skips draw when no prior lottery exists, just opens a new one`() {
        stubEnabled(true)
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        every { jackpotLotteryService.getOpenMatch(guildId) } returns null
        every { jackpotLotteryService.openMatchLottery(guildId, any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.Ok(
                lottery = JackpotLotteryDto(id = 6L, guildId = guildId),
                seeded = 50L,
            )

        job.runDaily()

        verify(exactly = 0) { jackpotLotteryService.drawMatchLottery(any()) }
        verify(exactly = 1) { jackpotLotteryService.openMatchLottery(guildId, any(), any(), any()) }
        verify(exactly = 1) { lotteryDailyService.markRan(guildId, today) }
    }

    @Test
    fun `runDaily does not mark ran when the open call rejects with InvalidParams`() {
        stubEnabled(true)
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        every { jackpotLotteryService.getOpenMatch(guildId) } returns null
        every { jackpotLotteryService.openMatchLottery(guildId, any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.InvalidParams("ticket price must be > 0")

        job.runDaily()

        // markRan is skipped so the next daily tick can retry once the
        // admin fixes the offending config.
        verify(exactly = 0) { lotteryDailyService.markRan(any(), any()) }
    }

    // ---- WEIGHTED-mode dispatch ----

    @Test
    fun `runDaily in WEIGHTED mode closes prior weighted draw and opens a fresh one`() {
        stubEnabled(true)
        stubMode("WEIGHTED")
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 5L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every { jackpotLotteryService.drawLottery(guildId) } returns
            JackpotLotteryService.DrawOutcome.Ok(
                payouts = emptyList(), totalPaid = 1_000L, drained = 1_000L,
            )
        every { jackpotLotteryService.openLottery(guildId, any(), any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.Ok(
                lottery = JackpotLotteryDto(id = 6L, guildId = guildId),
                seeded = 5_000L,
            )

        job.runDaily()

        // Weighted-path methods called, NOT match-path.
        verify(exactly = 1) { jackpotLotteryService.drawLottery(guildId) }
        verify(exactly = 1) { jackpotLotteryService.openLottery(guildId, any(), 24L, 3, any()) }
        verify(exactly = 0) { jackpotLotteryService.drawMatchLottery(any()) }
        verify(exactly = 0) { jackpotLotteryService.openMatchLottery(any(), any(), any(), any()) }
        verify(exactly = 1) { lotteryDailyService.markRan(guildId, today) }
    }

    @Test
    fun `runDaily in WEIGHTED mode treats EmptyPool as a soft skip and marks ran`() {
        stubEnabled(true)
        stubMode("WEIGHTED")
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns null
        every { jackpotLotteryService.openLottery(guildId, any(), any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.EmptyPool

        job.runDaily()

        // No spin-loop on every cron tick; admin must seed the pool.
        verify(exactly = 1) { lotteryDailyService.markRan(guildId, today) }
    }

    @Test
    fun `runDaily in WEIGHTED mode cancels prior draw if no tickets`() {
        stubEnabled(true)
        stubMode("WEIGHTED")
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 5L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every { jackpotLotteryService.drawLottery(guildId) } returns
            JackpotLotteryService.DrawOutcome.NoTickets
        every { jackpotLotteryService.openLottery(guildId, any(), any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.Ok(
                lottery = JackpotLotteryDto(id = 6L, guildId = guildId),
                seeded = 1_000L,
            )

        job.runDaily()

        verify(exactly = 1) { jackpotLotteryService.cancelLottery(guildId) }
        verify(exactly = 1) { jackpotLotteryService.openLottery(guildId, any(), any(), any(), any()) }
    }

    @Test
    fun `runDaily defaults to NUMBER_MATCH when mode config is missing or unknown`() {
        stubEnabled(true)
        // No mode stub — defaults to NUMBER_MATCH per LotteryHelper.dailyMode.
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        every { jackpotLotteryService.getOpenMatch(guildId) } returns null
        every { jackpotLotteryService.openMatchLottery(guildId, any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.Ok(
                lottery = JackpotLotteryDto(id = 1L, guildId = guildId), seeded = 0L,
            )

        job.runDaily()

        verify(exactly = 1) { jackpotLotteryService.openMatchLottery(guildId, any(), any(), any()) }
        verify(exactly = 0) { jackpotLotteryService.openLottery(any(), any(), any(), any(), any()) }
    }

    // ===================================================================
    // Announcer wiring + BelowMinBuyers path
    // ===================================================================

    @Test
    fun `runDaily calls announcer once after a successful WEIGHTED cycle`() {
        stubEnabled(true)
        stubMode("WEIGHTED")
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 5L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every { jackpotLotteryService.drawLottery(guildId) } returns
            JackpotLotteryService.DrawOutcome.Ok(
                payouts = listOf(
                    JackpotLotteryService.WinnerPayout(discordId = 1L, ticketCount = 2, amount = 500L),
                ),
                totalPaid = 500L, drained = 1_000L,
            )
        every { jackpotLotteryService.openLottery(guildId, any(), any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.Ok(
                lottery = JackpotLotteryDto(id = 6L, guildId = guildId, poolAmount = 50L),
                seeded = 50L,
            )

        job.runDaily()

        verify(exactly = 1) {
            lotteryAnnouncer.announceCycle(
                guild,
                "WEIGHTED",
                any<LotteryAnnouncer.PriorOutcome.WeightedDrawn>(),
                any<LotteryAnnouncer.OpenSummary.Ok>(),
            )
        }
    }

    @Test
    fun `runDaily threads bonusTicketsAwarded and highestMilestoneFired through to WeightedDrawn`() {
        // Regression guard for the incentives PR: the daily job is a
        // pure pass-through for the new DrawOutcome.Ok fields, but
        // "pure pass-through" is exactly the kind of code that
        // silently regresses when the announcer payload shape evolves.
        stubEnabled(true)
        stubMode("WEIGHTED")
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 5L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every { jackpotLotteryService.drawLottery(guildId) } returns
            JackpotLotteryService.DrawOutcome.Ok(
                payouts = listOf(
                    JackpotLotteryService.WinnerPayout(discordId = 1L, ticketCount = 30, amount = 800L),
                ),
                totalPaid = 800L,
                drained = 1_000L,
                bonusTicketsAwarded = 11L,
                highestMilestoneFired = 50L,
            )
        every { jackpotLotteryService.openLottery(guildId, any(), any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.Ok(
                lottery = JackpotLotteryDto(id = 6L, guildId = guildId, poolAmount = 50L),
                seeded = 50L,
            )
        val priorSlot = slot<LotteryAnnouncer.PriorOutcome>()
        every {
            lotteryAnnouncer.announceCycle(any(), any(), capture(priorSlot), any())
        } just runs

        job.runDaily()

        val prior = priorSlot.captured
        assertTrue(prior is LotteryAnnouncer.PriorOutcome.WeightedDrawn, "WEIGHTED cycle → WeightedDrawn")
        prior as LotteryAnnouncer.PriorOutcome.WeightedDrawn
        assertEquals(11L, prior.bonusTicketsAwarded)
        assertEquals(50L, prior.highestMilestoneFired)
    }

    @Test
    fun `runDaily handles WEIGHTED BelowMinBuyers — cancels, refunds, announces, marks ran`() {
        stubEnabled(true)
        stubMode("WEIGHTED")
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 5L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every { jackpotLotteryService.drawLottery(guildId) } returns
            JackpotLotteryService.DrawOutcome.BelowMinBuyers(have = 1, need = 2)
        every { jackpotLotteryService.openLottery(guildId, any(), any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.Ok(
                lottery = JackpotLotteryDto(id = 6L, guildId = guildId, poolAmount = 50L),
                seeded = 50L,
            )

        job.runDaily()

        // Refund path called.
        verify(exactly = 1) { jackpotLotteryService.cancelLottery(guildId) }
        // Announcer called with BelowMinBuyers prior.
        verify(exactly = 1) {
            lotteryAnnouncer.announceCycle(
                guild,
                "WEIGHTED",
                any<LotteryAnnouncer.PriorOutcome.BelowMinBuyers>(),
                any<LotteryAnnouncer.OpenSummary.Ok>(),
            )
        }
        // Day still marked ran — the gate firing isn't a config error,
        // just low engagement.
        verify(exactly = 1) { lotteryDailyService.markRan(guildId, today) }
    }

    @Test
    fun `runDaily handles NUMBER_MATCH BelowMinBuyers same way`() {
        stubEnabled(true)
        // Default mode is NUMBER_MATCH.
        every { lotteryDailyService.alreadyRan(guildId, today) } returns false
        every { jackpotLotteryService.getOpenMatch(guildId) } returns JackpotLotteryDto(
            id = 5L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_NUMBER_MATCH,
        )
        every { jackpotLotteryService.drawMatchLottery(guildId) } returns
            JackpotLotteryService.DrawMatchOutcome.BelowMinBuyers(have = 1, need = 3)
        every { jackpotLotteryService.openMatchLottery(guildId, any(), any(), any()) } returns
            JackpotLotteryService.OpenOutcome.Ok(
                lottery = JackpotLotteryDto(id = 6L, guildId = guildId, poolAmount = 0L), seeded = 0L,
            )

        job.runDaily()

        verify(exactly = 1) { jackpotLotteryService.cancelMatchLottery(guildId) }
        verify(exactly = 1) {
            lotteryAnnouncer.announceCycle(
                guild,
                "NUMBER_MATCH",
                any<LotteryAnnouncer.PriorOutcome.BelowMinBuyers>(),
                any<LotteryAnnouncer.OpenSummary.Ok>(),
            )
        }
    }

    @Test
    fun `runDaily skips announcer entirely when daily lottery is disabled`() {
        stubEnabled(false)

        job.runDaily()

        verify(exactly = 0) { lotteryAnnouncer.announceCycle(any(), any(), any(), any()) }
    }
}

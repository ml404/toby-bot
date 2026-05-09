package bot.toby.scheduling

import database.dto.ConfigDto
import database.dto.JackpotLotteryDto
import database.service.ConfigService
import database.service.JackpotLotteryService
import database.service.LotteryDailyService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
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
        guild = mockk(relaxed = true)

        val cache: SnowflakeCacheView<Guild> = mockk(relaxed = true)
        every { jda.guildCache } returns cache
        every { cache.iterator() } returns mutableListOf(guild).iterator()
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()

        job = LotteryDailyJob(jda, configService, lotteryDailyService, jackpotLotteryService, clock)
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
}

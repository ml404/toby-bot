package bot.toby.scheduling

import database.dto.JackpotLotteryDto
import database.service.lottery.JackpotLotteryService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LotteryRefreshJobTest {

    private lateinit var jda: JDA
    private lateinit var jackpotLotteryService: JackpotLotteryService
    private lateinit var lotteryAnnouncer: LotteryAnnouncer
    private lateinit var job: LotteryRefreshJob

    private val guildA: Guild = mockk(relaxed = true)
    private val guildB: Guild = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        jackpotLotteryService = mockk(relaxed = true)
        lotteryAnnouncer = mockk(relaxed = true)

        every { guildA.idLong } returns 100L
        every { guildB.idLong } returns 200L

        val cache: SnowflakeCacheView<Guild> = mockk(relaxed = true)
        every { jda.guildCache } returns cache
        every { cache.iterator() } returns mutableListOf(guildA, guildB).iterator()

        job = LotteryRefreshJob(jda, jackpotLotteryService, lotteryAnnouncer)
    }

    @Test
    fun `refreshAll fans out to each guild and each open lottery`() {
        val lotteryA = JackpotLotteryDto(id = 1L, guildId = 100L)
        val lotteryB1 = JackpotLotteryDto(id = 2L, guildId = 200L)
        val lotteryB2 = JackpotLotteryDto(id = 3L, guildId = 200L)
        every { jackpotLotteryService.getOpenLotteriesForRefresh(100L) } returns listOf(lotteryA)
        every { jackpotLotteryService.getOpenLotteriesForRefresh(200L) } returns listOf(lotteryB1, lotteryB2)

        job.refreshAll()

        verify(exactly = 1) { lotteryAnnouncer.refreshAnnouncement(guildA, lotteryA) }
        verify(exactly = 1) { lotteryAnnouncer.refreshAnnouncement(guildB, lotteryB1) }
        verify(exactly = 1) { lotteryAnnouncer.refreshAnnouncement(guildB, lotteryB2) }
    }

    @Test
    fun `refreshAll isolates per-guild errors`() {
        every { jackpotLotteryService.getOpenLotteriesForRefresh(100L) } throws RuntimeException("boom")
        val lotteryB = JackpotLotteryDto(id = 2L, guildId = 200L)
        every { jackpotLotteryService.getOpenLotteriesForRefresh(200L) } returns listOf(lotteryB)

        job.refreshAll()

        verify(exactly = 1) { lotteryAnnouncer.refreshAnnouncement(guildB, lotteryB) }
    }

    @Test
    fun `refreshAll does nothing when no guilds have open lotteries`() {
        every { jackpotLotteryService.getOpenLotteriesForRefresh(any()) } returns emptyList()

        job.refreshAll()

        verify(exactly = 0) { lotteryAnnouncer.refreshAnnouncement(any(), any()) }
    }
}

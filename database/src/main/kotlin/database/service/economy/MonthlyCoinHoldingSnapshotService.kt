package database.service.economy

import database.dto.economy.MonthlyCoinHoldingSnapshotDto
import java.time.LocalDate

/**
 * Read/freeze access to per-coin holding baselines captured at each monthly
 * boundary. The wallet leaderboard diffs current holdings against these to show
 * a per-coin "+/- this month"; the monthly job (and a lazy web fallback) writes
 * them.
 */
interface MonthlyCoinHoldingSnapshotService {
    fun listForGuildDate(guildId: Long, snapshotDate: LocalDate): List<MonthlyCoinHoldingSnapshotDto>
    fun upsertIfMissing(dto: MonthlyCoinHoldingSnapshotDto): MonthlyCoinHoldingSnapshotDto
}

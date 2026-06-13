package database.persistence.economy

import database.dto.economy.MonthlyCoinHoldingSnapshotDto
import java.time.LocalDate

interface MonthlyCoinHoldingSnapshotPersistence {
    /** Every frozen coin balance for a guild at one month boundary. */
    fun listForGuildDate(guildId: Long, snapshotDate: LocalDate): List<MonthlyCoinHoldingSnapshotDto>

    /**
     * Inserts the snapshot only when none exists for that
     * (discordId, guildId, snapshotDate, coin). Returns the existing row
     * unmodified if one is present, so re-freezing never clobbers the
     * authoritative boundary value.
     */
    fun upsertIfMissing(dto: MonthlyCoinHoldingSnapshotDto): MonthlyCoinHoldingSnapshotDto
}

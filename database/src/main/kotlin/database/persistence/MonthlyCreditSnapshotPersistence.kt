package database.persistence

import database.dto.MonthlyCreditSnapshotDto
import java.time.LocalDate

interface MonthlyCreditSnapshotPersistence {
    fun get(discordId: Long, guildId: Long, snapshotDate: LocalDate): MonthlyCreditSnapshotDto?
    fun listForGuildDate(guildId: Long, snapshotDate: LocalDate): List<MonthlyCreditSnapshotDto>
    fun upsert(dto: MonthlyCreditSnapshotDto): MonthlyCreditSnapshotDto

    /**
     * Inserts the snapshot only when none exists for that (discordId, guildId,
     * snapshotDate). Returns the existing row unmodified if one is present —
     * unlike [upsert], this does not clobber existing counter values.
     *
     * Used to lazy-fill baselines when the monthly scheduler hasn't run (fresh
     * deploy, new guild, bot was down on the 1st) so "this month" deltas
     * become meaningful from the next earn onwards.
     */
    fun upsertIfMissing(dto: MonthlyCreditSnapshotDto): MonthlyCreditSnapshotDto
}

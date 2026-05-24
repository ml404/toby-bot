package database.service.economy

import database.dto.economy.MonthlyCreditSnapshotDto
import java.time.LocalDate

interface MonthlyCreditSnapshotService {
    fun get(discordId: Long, guildId: Long, snapshotDate: LocalDate): MonthlyCreditSnapshotDto?
    fun listForGuildDate(guildId: Long, snapshotDate: LocalDate): List<MonthlyCreditSnapshotDto>
    fun upsert(dto: MonthlyCreditSnapshotDto): MonthlyCreditSnapshotDto
    fun upsertIfMissing(dto: MonthlyCreditSnapshotDto): MonthlyCreditSnapshotDto
}

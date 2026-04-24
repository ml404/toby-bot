package database.service

import database.dto.MonthlyCreditSnapshotDto
import java.time.LocalDate

interface MonthlyCreditSnapshotService {
    fun get(discordId: Long, guildId: Long, snapshotDate: LocalDate): MonthlyCreditSnapshotDto?
    fun listForGuildDate(guildId: Long, snapshotDate: LocalDate): List<MonthlyCreditSnapshotDto>
    fun upsert(dto: MonthlyCreditSnapshotDto): MonthlyCreditSnapshotDto
}

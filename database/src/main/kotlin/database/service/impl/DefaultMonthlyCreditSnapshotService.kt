package database.service.impl

import database.dto.MonthlyCreditSnapshotDto
import database.persistence.MonthlyCreditSnapshotPersistence
import database.service.MonthlyCreditSnapshotService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class DefaultMonthlyCreditSnapshotService @Autowired constructor(
    private val persistence: MonthlyCreditSnapshotPersistence
) : MonthlyCreditSnapshotService {
    override fun get(discordId: Long, guildId: Long, snapshotDate: LocalDate): MonthlyCreditSnapshotDto? =
        persistence.get(discordId, guildId, snapshotDate)

    override fun listForGuildDate(guildId: Long, snapshotDate: LocalDate): List<MonthlyCreditSnapshotDto> =
        persistence.listForGuildDate(guildId, snapshotDate)

    override fun upsert(dto: MonthlyCreditSnapshotDto): MonthlyCreditSnapshotDto = persistence.upsert(dto)

    override fun upsertIfMissing(dto: MonthlyCreditSnapshotDto): MonthlyCreditSnapshotDto =
        persistence.upsertIfMissing(dto)
}

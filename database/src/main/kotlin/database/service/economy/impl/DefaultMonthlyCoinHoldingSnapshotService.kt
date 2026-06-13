package database.service.economy.impl

import database.dto.economy.MonthlyCoinHoldingSnapshotDto
import database.persistence.economy.MonthlyCoinHoldingSnapshotPersistence
import database.service.economy.MonthlyCoinHoldingSnapshotService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class DefaultMonthlyCoinHoldingSnapshotService @Autowired constructor(
    private val persistence: MonthlyCoinHoldingSnapshotPersistence
) : MonthlyCoinHoldingSnapshotService {
    override fun listForGuildDate(guildId: Long, snapshotDate: LocalDate): List<MonthlyCoinHoldingSnapshotDto> =
        persistence.listForGuildDate(guildId, snapshotDate)

    override fun upsertIfMissing(dto: MonthlyCoinHoldingSnapshotDto): MonthlyCoinHoldingSnapshotDto =
        persistence.upsertIfMissing(dto)
}

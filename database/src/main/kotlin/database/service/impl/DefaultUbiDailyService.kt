package database.service.impl

import database.dto.UbiDailyDto
import database.persistence.UbiDailyPersistence
import database.service.UbiDailyService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class DefaultUbiDailyService @Autowired constructor(
    private val persistence: UbiDailyPersistence
) : UbiDailyService {
    override fun get(discordId: Long, guildId: Long, date: LocalDate): UbiDailyDto? =
        persistence.get(discordId, guildId, date)

    override fun upsert(row: UbiDailyDto): UbiDailyDto = persistence.upsert(row)

    override fun sumGrantedInRangeByUser(guildId: Long, from: LocalDate, until: LocalDate): Map<Long, Long> =
        persistence.sumGrantedInRangeByUser(guildId, from, until)
}

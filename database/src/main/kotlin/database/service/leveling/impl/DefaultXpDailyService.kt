package database.service.leveling.impl

import database.dto.XpDailyDto
import database.persistence.XpDailyPersistence
import database.service.leveling.XpDailyService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class DefaultXpDailyService @Autowired constructor(
    private val persistence: XpDailyPersistence
) : XpDailyService {
    override fun get(discordId: Long, guildId: Long, date: LocalDate): XpDailyDto? =
        persistence.get(discordId, guildId, date)

    override fun upsert(row: XpDailyDto): XpDailyDto = persistence.upsert(row)
}

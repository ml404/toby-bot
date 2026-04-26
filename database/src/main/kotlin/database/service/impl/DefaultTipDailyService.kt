package database.service.impl

import database.dto.TipDailyDto
import database.persistence.TipDailyPersistence
import database.service.TipDailyService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class DefaultTipDailyService @Autowired constructor(
    private val persistence: TipDailyPersistence
) : TipDailyService {
    override fun get(senderDiscordId: Long, guildId: Long, date: LocalDate): TipDailyDto? =
        persistence.get(senderDiscordId, guildId, date)

    override fun upsert(row: TipDailyDto): TipDailyDto = persistence.upsert(row)
}

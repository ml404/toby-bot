package database.service.social.impl

import database.dto.social.TipDailyDto
import database.persistence.social.TipDailyPersistence
import database.service.social.TipDailyService
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

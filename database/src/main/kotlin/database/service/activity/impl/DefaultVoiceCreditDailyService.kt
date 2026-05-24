package database.service.activity.impl

import database.dto.activity.VoiceCreditDailyDto
import database.persistence.activity.VoiceCreditDailyPersistence
import database.service.activity.VoiceCreditDailyService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class DefaultVoiceCreditDailyService @Autowired constructor(
    private val persistence: VoiceCreditDailyPersistence
) : VoiceCreditDailyService {
    override fun get(discordId: Long, guildId: Long, date: LocalDate): VoiceCreditDailyDto? =
        persistence.get(discordId, guildId, date)

    override fun upsert(row: VoiceCreditDailyDto): VoiceCreditDailyDto = persistence.upsert(row)
}

package database.service.impl

import database.dto.ActivityMonthlyRollupDto
import database.persistence.ActivityMonthlyRollupPersistence
import database.service.ActivityMonthlyRollupService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class DefaultActivityMonthlyRollupService @Autowired constructor(
    private val persistence: ActivityMonthlyRollupPersistence
) : ActivityMonthlyRollupService {

    override fun addSeconds(
        discordId: Long,
        guildId: Long,
        monthStart: LocalDate,
        activityName: String,
        delta: Long
    ): ActivityMonthlyRollupDto =
        persistence.addSeconds(discordId, guildId, monthStart, activityName, delta)

    override fun forGuildMonth(guildId: Long, monthStart: LocalDate): List<ActivityMonthlyRollupDto> =
        persistence.forGuildMonth(guildId, monthStart)

    override fun forUser(guildId: Long, discordId: Long): List<ActivityMonthlyRollupDto> =
        persistence.forUser(guildId, discordId)

    override fun forUserMonth(
        guildId: Long,
        discordId: Long,
        monthStart: LocalDate
    ): List<ActivityMonthlyRollupDto> = persistence.forUserMonth(guildId, discordId, monthStart)

    override fun deleteBefore(cutoff: LocalDate): Int = persistence.deleteBefore(cutoff)
}

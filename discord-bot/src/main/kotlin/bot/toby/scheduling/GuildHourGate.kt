package bot.toby.scheduling

import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * Shared per-guild scheduling gate for the daily cron jobs.
 *
 * Each wall-clock job runs hourly and asks this gate which UTC hour (0-23)
 * a guild has configured for that job, falling back to the job's historical
 * fixed hour when the config is unset or invalid. The job then acts on the
 * guild only when the current UTC hour equals [configuredHour].
 *
 * Kept deliberately tiny and clock-free: the caller owns its injected
 * [java.time.Clock] (already tested) and passes the current hour in, so this
 * is a pure config reader. Config reads are cache-backed in [ConfigService].
 */
@Component
class GuildHourGate(private val configService: ConfigService) {

    /**
     * The per-guild UTC run-hour (0-23) for [key], or [defaultHour] when the
     * value is missing, non-numeric, or out of range.
     */
    fun configuredHour(
        guildId: Long,
        key: ConfigDto.Configurations,
        defaultHour: Int,
    ): Int = configService.getConfigByName(key.configValue, guildId.toString())
        ?.value?.trim()?.toIntOrNull()?.takeIf { it in 0..23 }
        ?: defaultHour
}

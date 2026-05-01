package bot.toby.scheduling

import common.logging.DiscordLogger
import database.dto.ConfigDto
import database.dto.UbiDailyDto
import database.service.ConfigService
import database.service.SocialCreditAwardService
import database.service.UbiDailyService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Daily Universal Basic Income grant. At 00:00 UTC, every guild with a
 * positive `UBI_DAILY_AMOUNT` config grants that many credits to every
 * known user, bypassing the daily cap. The `ubi_daily` ledger makes
 * retries idempotent — if the bot restarts mid-run or the job fires
 * twice in a day, only one grant per (user, guild, date) lands.
 */
@Component
@Profile("prod")
class UniversalBasicIncomeJob @Autowired constructor(
    private val jda: JDA,
    private val userService: UserService,
    private val configService: ConfigService,
    private val ubiDailyService: UbiDailyService,
    private val awardService: SocialCreditAwardService,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    fun runDaily() {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        logger.info { "Running UBI job for $today" }

        jda.guildCache.forEach { guild ->
            runCatching { grantForGuild(guild, today) }
                .onFailure { logger.error("UBI grant failed for guild ${guild.idLong}: ${it.message}") }
        }
    }

    private fun grantForGuild(guild: Guild, today: LocalDate) {
        val amount = readUbiAmount(guild.id)
        if (amount <= 0L) {
            logger.info { "UBI disabled or unset for guild ${guild.idLong}; skipping." }
            return
        }

        val users = userService.listGuildUsers(guild.idLong).filterNotNull()
        if (users.isEmpty()) {
            logger.info { "No tracked users for guild ${guild.idLong}; skipping UBI." }
            return
        }

        var granted = 0
        var skipped = 0
        users.forEach { dto ->
            val discordId = dto.discordId
            val guildId = dto.guildId
            if (ubiDailyService.get(discordId, guildId, today) != null) {
                skipped++
                return@forEach
            }
            val awarded = awardService.award(
                discordId = discordId,
                guildId = guildId,
                amount = amount,
                reason = "ubi",
                countsAgainstDailyCap = false,
                at = today.atStartOfDay().toInstant(ZoneOffset.UTC)
            )
            if (awarded > 0L) {
                ubiDailyService.upsert(
                    UbiDailyDto(
                        discordId = discordId,
                        guildId = guildId,
                        grantDate = today,
                        creditsGranted = awarded
                    )
                )
                granted++
            }
        }
        logger.info {
            "UBI for guild ${guild.idLong}: granted=$granted alreadyGranted=$skipped amount=$amount"
        }
    }

    private fun readUbiAmount(guildId: String): Long {
        val raw = configService.getConfigByName(
            ConfigDto.Configurations.UBI_DAILY_AMOUNT.configValue,
            guildId
        )?.value
        return raw?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }
}

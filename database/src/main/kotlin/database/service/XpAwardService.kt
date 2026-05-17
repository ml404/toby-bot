package database.service

import common.events.LevelUpEvent
import common.leveling.LevelCurve
import database.dto.ConfigDto
import database.dto.XpDailyDto
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Single entry point for awarding XP. Mirrors [SocialCreditAwardService]:
 * positive [amount] only, daily-cap accounting (per-guild override via
 * [ConfigDto.Configurations.DAILY_XP_CAP], falling back to
 * [DEFAULT_DAILY_XP_CAP]), and a single update path so cache invalidation
 * stays consistent.
 *
 * The award path computes the user's level before and after the increment;
 * if at least one threshold is crossed, a [LevelUpEvent] is published.
 * Listeners in the discord-bot module pick that up to announce the
 * level-up, assign role rewards, and unlock gated titles.
 */
@Service
@Transactional
class XpAwardService(
    private val userService: UserService,
    private val xpDailyService: XpDailyService,
    private val configService: ConfigService,
    private val eventPublisher: ApplicationEventPublisher
) {
    /**
     * Add [amount] XP to the user. Daily cap is enforced when
     * [countsAgainstDailyCap] is true. Returns the XP that was actually
     * granted (clamped to remaining daily headroom). No-ops and returns 0
     * for non-positive [amount] or unknown users. [channelId] is forwarded
     * to any emitted [LevelUpEvent] so listeners can post in the right
     * channel.
     */
    fun award(
        discordId: Long,
        guildId: Long,
        amount: Long,
        reason: String,
        channelId: Long? = null,
        countsAgainstDailyCap: Boolean = true,
        at: Instant = Instant.now(),
    ): Long {
        if (amount <= 0L) return 0L

        val user = userService.getUserById(discordId, guildId) ?: return 0L

        val granted = if (countsAgainstDailyCap) {
            clampToDailyCap(discordId, guildId, amount, at)
        } else {
            amount
        }
        if (granted <= 0L) return 0L

        val oldXp = user.xp
        val newXp = oldXp + granted
        val oldLevel = LevelCurve.levelForXp(oldXp)
        val newLevel = LevelCurve.levelForXp(newXp)
        user.xp = newXp
        userService.updateUser(user)

        if (newLevel > oldLevel) {
            eventPublisher.publishEvent(
                LevelUpEvent(
                    discordId = discordId,
                    guildId = guildId,
                    oldLevel = oldLevel,
                    newLevel = newLevel,
                    channelId = channelId
                )
            )
        }
        return granted
    }

    private fun clampToDailyCap(
        discordId: Long,
        guildId: Long,
        requested: Long,
        at: Instant
    ): Long {
        val today = LocalDate.ofInstant(at, ZoneOffset.UTC)
        val existing = xpDailyService.get(discordId, guildId, today)
        val usedToday = existing?.xpEarned ?: 0L
        val dailyCap = resolveDailyCap(guildId)
        val headroom = (dailyCap - usedToday).coerceAtLeast(0L)
        val granted = requested.coerceAtMost(headroom)
        if (granted > 0L) {
            xpDailyService.upsert(
                XpDailyDto(
                    discordId = discordId,
                    guildId = guildId,
                    earnDate = today,
                    xpEarned = usedToday + granted
                )
            )
        }
        return granted
    }

    private fun resolveDailyCap(guildId: Long): Long {
        val raw = configService.getConfigByName(
            ConfigDto.Configurations.DAILY_XP_CAP.configValue,
            guildId.toString()
        )?.value
        val parsed = raw?.toLongOrNull()
        return if (parsed != null && parsed >= 0L) parsed else DEFAULT_DAILY_XP_CAP
    }

    companion object {
        // Fallback when DAILY_XP_CAP config is unset or unparseable for the guild.
        const val DEFAULT_DAILY_XP_CAP: Long = 1000L
    }
}

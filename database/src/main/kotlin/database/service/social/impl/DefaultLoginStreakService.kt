package database.service.social.impl

import common.events.StreakClaimedEvent
import database.dto.ConfigDto
import database.dto.LoginStreakDto
import database.persistence.social.LoginStreakPersistence
import database.service.guild.ConfigService
import database.service.social.LoginStreakService
import database.service.social.LoginStreakService.ClaimResult
import database.service.social.SocialCreditAwardService
import database.service.leveling.XpAwardService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Single entry point for the daily login streak. Mirrors [XpAwardService]:
 *   - One write path so cache invalidation and reward accounting stay
 *     consistent across slash command (`/daily`) and web POST.
 *   - Streak rewards bypass the daily XP cap (countsAgainstDailyCap = false) —
 *     the whole point of the streak is to grant predictable on-claim XP
 *     regardless of how much the user has already earned today.
 *
 * Streak logic:
 *   - last_claim_date == today           → AlreadyClaimed (no event, no reward).
 *   - last_claim_date == today - 1 day   → currentStreak + 1.
 *   - otherwise (including never-claimed) → currentStreak = 1.
 *
 * Day boundaries use UTC, matching the existing daily-cap ledgers.
 */
@Service
@Transactional
class DefaultLoginStreakService(
    private val persistence: LoginStreakPersistence,
    private val xpAwardService: XpAwardService,
    private val socialCreditAwardService: SocialCreditAwardService,
    private val configService: ConfigService,
    private val eventPublisher: ApplicationEventPublisher
) : LoginStreakService {

    override fun get(discordId: Long, guildId: Long): LoginStreakDto? =
        persistence.get(discordId, guildId)

    override fun findActiveStreaksDueForReminder(
        guildId: Long,
        today: LocalDate
    ): List<LoginStreakDto> = persistence.findActiveStreaksDueForReminder(guildId, today)

    override fun claim(
        discordId: Long,
        guildId: Long,
        at: Instant,
        channelId: Long?
    ): ClaimResult {
        val today = LocalDate.ofInstant(at, ZoneOffset.UTC)
        val existing = persistence.get(discordId, guildId)

        if (existing?.lastClaimDate == today) {
            return ClaimResult.AlreadyClaimed(
                currentStreak = existing.currentStreak,
                longestStreak = existing.longestStreak
            )
        }

        val previousStreak = existing?.currentStreak ?: 0
        val previousLongest = existing?.longestStreak ?: 0
        val previousTotal = existing?.totalClaims ?: 0L

        val continued = existing?.lastClaimDate == today.minusDays(1)
        val newStreak = if (continued) previousStreak + 1 else 1
        val newLongest = maxOf(previousLongest, newStreak)
        val isNewBest = newStreak > previousLongest

        persistence.upsert(
            LoginStreakDto(
                discordId = discordId,
                guildId = guildId,
                currentStreak = newStreak,
                longestStreak = newLongest,
                lastClaimDate = today,
                totalClaims = previousTotal + 1
            )
        )

        val xpReward = resolveXpReward(guildId, newStreak)
        val creditReward = resolveCreditReward(guildId, newStreak)

        val xpGranted = if (xpReward > 0) {
            xpAwardService.award(
                discordId = discordId,
                guildId = guildId,
                amount = xpReward,
                reason = "daily_streak",
                channelId = channelId,
                countsAgainstDailyCap = false,
                at = at
            )
        } else 0L

        val creditsGranted = if (creditReward > 0) {
            socialCreditAwardService.award(
                discordId = discordId,
                guildId = guildId,
                amount = creditReward,
                reason = "daily_streak",
                countsAgainstDailyCap = false,
                at = at
            )
        } else 0L

        eventPublisher.publishEvent(
            StreakClaimedEvent(
                discordId = discordId,
                guildId = guildId,
                currentStreak = newStreak,
                longestStreak = newLongest,
                channelId = channelId
            )
        )

        return ClaimResult.Granted(
            currentStreak = newStreak,
            longestStreak = newLongest,
            xpGranted = xpGranted,
            creditsGranted = creditsGranted,
            isNewBest = isNewBest
        )
    }

    private fun resolveXpReward(guildId: Long, streak: Int): Long {
        val base = configLong(guildId, ConfigDto.Configurations.STREAK_BASE_REWARD_XP, DEFAULT_BASE_XP)
        val perDay = configLong(guildId, ConfigDto.Configurations.STREAK_PER_DAY_BONUS_XP, DEFAULT_PER_DAY_BONUS_XP)
        val max = configLong(guildId, ConfigDto.Configurations.STREAK_MAX_REWARD_XP, DEFAULT_MAX_XP)
        if (base <= 0L && perDay <= 0L) return 0L
        val raw = base + perDay * (streak - 1).coerceAtLeast(0)
        return raw.coerceAtMost(max).coerceAtLeast(0L)
    }

    private fun resolveCreditReward(guildId: Long, streak: Int): Long {
        val base = configLong(guildId, ConfigDto.Configurations.STREAK_BASE_REWARD_CREDIT, DEFAULT_BASE_CREDIT)
        val perDay = configLong(guildId, ConfigDto.Configurations.STREAK_PER_DAY_BONUS_CREDIT, DEFAULT_PER_DAY_BONUS_CREDIT)
        val max = configLong(guildId, ConfigDto.Configurations.STREAK_MAX_REWARD_CREDIT, DEFAULT_MAX_CREDIT)
        if (base <= 0L && perDay <= 0L) return 0L
        val raw = base + perDay * (streak - 1).coerceAtLeast(0)
        return raw.coerceAtMost(max).coerceAtLeast(0L)
    }

    private fun configLong(
        guildId: Long,
        key: ConfigDto.Configurations,
        default: Long
    ): Long {
        val raw = configService.getConfigByName(key.configValue, guildId.toString())?.value
        val parsed = raw?.toLongOrNull()
        return if (parsed != null && parsed >= 0L) parsed else default
    }

    companion object {
        const val DEFAULT_BASE_XP: Long = 50L
        const val DEFAULT_PER_DAY_BONUS_XP: Long = 5L
        const val DEFAULT_MAX_XP: Long = 300L

        const val DEFAULT_BASE_CREDIT: Long = 25L
        const val DEFAULT_PER_DAY_BONUS_CREDIT: Long = 5L
        const val DEFAULT_MAX_CREDIT: Long = 200L
    }
}

package database.service.guild.impl

import common.events.AchievementUnlockedEvent
import database.dto.AchievementDto
import database.dto.AchievementProgressDto
import database.dto.UserAchievementDto
import database.persistence.guild.AchievementPersistence
import database.service.guild.AchievementService
import database.service.guild.AchievementService.AchievementView
import database.service.guild.AchievementService.ProgressResult
import database.service.social.SocialCreditAwardService
import database.service.leveling.XpAwardService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Achievement engine. Single source of truth for "did this user just
 * cross a threshold?" — every progress hook in the codebase routes
 * through [progress]/[unlock] so cache invalidation, reward awarding,
 * and event publication stay coherent.
 *
 * Unlock semantics:
 *   - One-shot achievements (threshold = 1): single [unlock] call →
 *     row in `user_achievement` + reward + event. Repeat calls no-op.
 *   - Counter achievements: each [progress] call upserts the counter
 *     row. When `progress >= threshold` and not yet unlocked, the
 *     unlock path fires (reward + event), idempotent thereafter.
 */
@Service
@Transactional
class DefaultAchievementService(
    private val persistence: AchievementPersistence,
    private val xpAwardService: XpAwardService,
    private val socialCreditAwardService: SocialCreditAwardService,
    private val eventPublisher: ApplicationEventPublisher
) : AchievementService {

    override fun listAll(): List<AchievementDto> = persistence.listAll()

    override fun getByCode(code: String): AchievementDto? = persistence.getByCode(code)

    override fun progress(
        discordId: Long,
        guildId: Long,
        code: String,
        delta: Long,
        channelId: Long?
    ): ProgressResult {
        val achievement = persistence.getByCode(code)
            ?: return ProgressResult(null, 0L, unlocked = false, alreadyUnlocked = false)
        val achievementId = achievement.id
            ?: return ProgressResult(achievement, 0L, unlocked = false, alreadyUnlocked = false)

        if (persistence.owns(discordId, guildId, achievementId)) {
            return ProgressResult(achievement, achievement.threshold, unlocked = false, alreadyUnlocked = true)
        }

        val safeDelta = delta.coerceAtLeast(0L)
        val existing = persistence.getProgress(discordId, guildId, achievementId)
        val newProgress = (existing?.progress ?: 0L) + safeDelta
        if (safeDelta > 0L || existing == null) {
            persistence.upsertProgress(
                AchievementProgressDto(
                    discordId = discordId,
                    guildId = guildId,
                    achievementId = achievementId,
                    progress = newProgress,
                    updatedAt = Instant.now()
                )
            )
        }

        if (newProgress < achievement.threshold) {
            return ProgressResult(achievement, newProgress, unlocked = false, alreadyUnlocked = false)
        }

        return finalizeUnlock(discordId, guildId, achievement, achievementId, newProgress, channelId)
    }

    override fun unlock(
        discordId: Long,
        guildId: Long,
        code: String,
        channelId: Long?
    ): ProgressResult {
        val achievement = persistence.getByCode(code)
            ?: return ProgressResult(null, 0L, unlocked = false, alreadyUnlocked = false)
        val achievementId = achievement.id
            ?: return ProgressResult(achievement, 0L, unlocked = false, alreadyUnlocked = false)

        if (persistence.owns(discordId, guildId, achievementId)) {
            return ProgressResult(achievement, achievement.threshold, unlocked = false, alreadyUnlocked = true)
        }

        return finalizeUnlock(discordId, guildId, achievement, achievementId, achievement.threshold, channelId)
    }

    override fun setProgress(
        discordId: Long,
        guildId: Long,
        code: String,
        value: Long,
        channelId: Long?
    ): ProgressResult {
        val achievement = persistence.getByCode(code)
            ?: return ProgressResult(null, 0L, unlocked = false, alreadyUnlocked = false)
        val achievementId = achievement.id
            ?: return ProgressResult(achievement, 0L, unlocked = false, alreadyUnlocked = false)

        if (persistence.owns(discordId, guildId, achievementId)) {
            return ProgressResult(achievement, achievement.threshold, unlocked = false, alreadyUnlocked = true)
        }

        val newProgress = value.coerceIn(0L, achievement.threshold)
        val existing = persistence.getProgress(discordId, guildId, achievementId)
        if (existing?.progress != newProgress) {
            persistence.upsertProgress(
                AchievementProgressDto(
                    discordId = discordId,
                    guildId = guildId,
                    achievementId = achievementId,
                    progress = newProgress,
                    updatedAt = Instant.now()
                )
            )
        }

        if (newProgress < achievement.threshold) {
            return ProgressResult(achievement, newProgress, unlocked = false, alreadyUnlocked = false)
        }

        return finalizeUnlock(discordId, guildId, achievement, achievementId, newProgress, channelId)
    }

    override fun listFor(discordId: Long, guildId: Long): List<AchievementView> {
        val owned = persistence.listOwnedByUser(discordId, guildId).associateBy { it.achievementId }
        val progressByAchievement = persistence.listProgressByUser(discordId, guildId).associateBy { it.achievementId }
        val catalogue = persistence.listAll()
        return catalogue
            .filter { a -> !a.hidden || owned.containsKey(a.id) }
            .map { a ->
                val unlock = owned[a.id]
                val progressVal = unlock?.let { a.threshold }
                    ?: progressByAchievement[a.id]?.progress
                    ?: 0L
                AchievementView(
                    achievement = a,
                    unlockedAt = unlock?.unlockedAt,
                    progress = progressVal
                )
            }
    }

    override fun progressByCodesForGuild(
        guildId: Long,
        codes: Collection<String>,
    ): List<AchievementService.ProgressByCode> =
        persistence.progressByCodesForGuild(guildId, codes).map { row ->
            AchievementService.ProgressByCode(
                discordId = row.discordId,
                code = row.code,
                progress = row.progress,
            )
        }

    private fun finalizeUnlock(
        discordId: Long,
        guildId: Long,
        achievement: AchievementDto,
        achievementId: Long,
        progressValue: Long,
        channelId: Long?
    ): ProgressResult {
        persistence.recordUnlock(
            UserAchievementDto(
                discordId = discordId,
                guildId = guildId,
                achievementId = achievementId,
                unlockedAt = Instant.now()
            )
        )

        if (achievement.xpReward > 0) {
            xpAwardService.award(
                discordId = discordId,
                guildId = guildId,
                amount = achievement.xpReward.toLong(),
                reason = "achievement_${achievement.code}",
                channelId = channelId,
                countsAgainstDailyCap = false
            )
        }
        if (achievement.creditReward > 0L) {
            socialCreditAwardService.award(
                discordId = discordId,
                guildId = guildId,
                amount = achievement.creditReward,
                reason = "achievement_${achievement.code}",
                countsAgainstDailyCap = false
            )
        }

        eventPublisher.publishEvent(
            AchievementUnlockedEvent(
                discordId = discordId,
                guildId = guildId,
                achievementId = achievementId,
                achievementCode = achievement.code,
                name = achievement.name,
                description = achievement.description,
                icon = achievement.icon,
                channelId = channelId
            )
        )

        return ProgressResult(
            achievement = achievement,
            newProgress = progressValue,
            unlocked = true,
            alreadyUnlocked = false
        )
    }
}

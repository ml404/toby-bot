package database.service

import common.leveling.LevelCurve
import database.dto.ConfigDto
import database.dto.VoiceCreditDailyDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Single entry point for awarding positive social credit. All award paths
 * (command completion, voice-session, intro playback, web UI participation)
 * route through here so the daily cap accounting and cache invalidation stay
 * in one place.
 */
@Service
@Transactional
class SocialCreditAwardService(
    private val userService: UserService,
    private val voiceCreditDailyService: VoiceCreditDailyService,
    private val configService: ConfigService
) {
    /**
     * Add [amount] credits to the user, respecting the daily cap when
     * [countsAgainstDailyCap] is true. Returns the amount that was actually
     * granted (clamped to remaining daily headroom). No-ops and returns 0 for
     * non-positive [amount] or unknown users.
     *
     * The daily cap is resolved per-guild from the [ConfigDto.Configurations.DAILY_CREDIT_CAP]
     * config, falling back to [DEFAULT_DAILY_CAP] when unset or unparseable.
     */
    fun award(
        discordId: Long,
        guildId: Long,
        amount: Long,
        reason: String,
        countsAgainstDailyCap: Boolean = true,
        at: Instant = Instant.now(),
    ): Long {
        if (amount <= 0L) return 0L

        // Resolve the user first so we never debit the daily cap for a phantom
        // award to a user that doesn't exist.
        val user = userService.getUserById(discordId, guildId) ?: return 0L

        val granted = if (countsAgainstDailyCap) {
            clampToDailyCap(discordId, guildId, amount, at)
        } else {
            amount
        }
        if (granted <= 0L) return 0L

        user.socialCredit = (user.socialCredit ?: 0L) + granted
        userService.updateUser(user)
        return granted
    }

    private fun clampToDailyCap(
        discordId: Long,
        guildId: Long,
        requested: Long,
        at: Instant
    ): Long {
        val today = LocalDate.ofInstant(at, ZoneOffset.UTC)
        val existing = voiceCreditDailyService.get(discordId, guildId, today)
        val usedToday = existing?.credits ?: 0L
        val dailyCap = resolveDailyCap(discordId, guildId)
        val headroom = (dailyCap - usedToday).coerceAtLeast(0L)
        val granted = requested.coerceAtMost(headroom)
        if (granted > 0L) {
            voiceCreditDailyService.upsert(
                VoiceCreditDailyDto(
                    discordId = discordId,
                    guildId = guildId,
                    earnDate = today,
                    credits = usedToday + granted
                )
            )
        }
        return granted
    }

    /**
     * Resolved daily cap for [discordId] in [guildId]: the per-guild
     * configured base (or [DEFAULT_DAILY_CAP] when unset) plus the user's
     * level bonus (`level * DAILY_CAP_PER_LEVEL_BONUS`, default +10/level).
     * The leveling perk is config-driven so server owners can disable it
     * by setting `DAILY_CAP_PER_LEVEL_BONUS=0`.
     */
    fun resolveDailyCap(discordId: Long, guildId: Long): Long {
        val base = resolveBaseDailyCap(guildId)
        val perLevelBonus = resolvePerLevelBonus(guildId)
        if (perLevelBonus == 0L) return base
        val user = userService.getUserById(discordId, guildId) ?: return base
        val level = LevelCurve.levelForXp(user.xp)
        return base + perLevelBonus * level
    }

    private fun resolveBaseDailyCap(guildId: Long): Long {
        val raw = configService.getConfigByName(
            ConfigDto.Configurations.DAILY_CREDIT_CAP.configValue,
            guildId.toString()
        )?.value
        val parsed = raw?.toLongOrNull()
        return if (parsed != null && parsed >= 0L) parsed else DEFAULT_DAILY_CAP
    }

    private fun resolvePerLevelBonus(guildId: Long): Long {
        val raw = configService.getConfigByName(
            ConfigDto.Configurations.DAILY_CAP_PER_LEVEL_BONUS.configValue,
            guildId.toString()
        )?.value
        val parsed = raw?.toLongOrNull()
        return if (parsed != null && parsed >= 0L) parsed else DEFAULT_DAILY_CAP_PER_LEVEL_BONUS
    }

    companion object {
        // Fallback when DAILY_CREDIT_CAP config is unset or unparseable for the guild.
        const val DEFAULT_DAILY_CAP: Long = 90L

        // Fallback when DAILY_CAP_PER_LEVEL_BONUS is unset or unparseable.
        const val DEFAULT_DAILY_CAP_PER_LEVEL_BONUS: Long = 10L
    }
}

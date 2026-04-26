package database.service

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
    private val voiceCreditDailyService: VoiceCreditDailyService
) {
    /**
     * Add [amount] credits to the user, respecting the daily cap when
     * [countsAgainstDailyCap] is true. Returns the amount that was actually
     * granted (clamped to remaining daily headroom). No-ops and returns 0 for
     * non-positive [amount] or unknown users.
     */
    fun award(
        discordId: Long,
        guildId: Long,
        amount: Long,
        reason: String,
        countsAgainstDailyCap: Boolean = true,
        at: Instant = Instant.now(),
        dailyCap: Long = DEFAULT_DAILY_CAP,
    ): Long {
        if (amount <= 0L) return 0L

        // Resolve the user first so we never debit the daily cap for a phantom
        // award to a user that doesn't exist.
        val user = userService.getUserById(discordId, guildId) ?: return 0L

        val granted = if (countsAgainstDailyCap) {
            clampToDailyCap(discordId, guildId, amount, at, dailyCap)
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
        at: Instant,
        dailyCap: Long
    ): Long {
        val today = LocalDate.ofInstant(at, ZoneOffset.UTC)
        val existing = voiceCreditDailyService.get(discordId, guildId, today)
        val usedToday = existing?.credits ?: 0L
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

    companion object {
        // Matches the voice credit cap so all daily-capped sources share one bucket.
        const val DEFAULT_DAILY_CAP: Long = 90L
    }
}

package database.service

import database.dto.ConfigDto
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Identifier for the spam-able single-shot casino games subject to the
 * per-user cooldown. Multi-player table games (poker, multi-table
 * blackjack) are turn-driven and excluded — they self-throttle through
 * the per-actor shot clock instead.
 */
enum class CasinoGameKey {
    COINFLIP, SLOTS, DICE, HIGHLOW, SCRATCH, BACCARAT, KENO, BLACKJACK_SOLO, CASINOHOLDEM, DUEL,
}

/**
 * Per-user, per-game cooldown to defeat autoclicker spam. The user-row
 * pessimistic lock taken by `WagerHelper.checkAndLock` only stops
 * *concurrent* duplicate plays inside one transaction; it does nothing
 * to throttle a script that fires sequential commands as fast as the
 * service responds. This service plugs that gap.
 *
 * State is process-local, keyed `(discordId, gameKey)`. The bot is a
 * single instance today; if/when it scales to multiple instances this
 * needs to migrate to a shared store (Redis or a `casino_cooldown` row).
 *
 * The cooldown duration is admin-configurable per guild via the
 * [ConfigDto.Configurations.CASINO_COOLDOWN_SECONDS] config key, clamped
 * to [MIN_COOLDOWN_SECONDS]..[MAX_COOLDOWN_SECONDS]. Set to 0 to disable.
 */
@Component
class CasinoCooldownService(
    private val configService: ConfigService,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Key(val discordId: Long, val game: CasinoGameKey)

    private val lastInvokes: ConcurrentHashMap<Key, Long> = ConcurrentHashMap()

    sealed interface AcquireResult {
        data object Ok : AcquireResult
        data class OnCooldown(val remainingMs: Long) : AcquireResult
    }

    /**
     * Check whether [discordId] may start a new wager on [game] right
     * now. Returns [AcquireResult.OnCooldown] with the remaining time if
     * blocked, otherwise [AcquireResult.Ok]. The caller MUST follow up
     * with [arm] only after the wager has been successfully debited —
     * an aborted wager (insufficient funds, invalid stake) shouldn't
     * consume the cooldown.
     */
    fun tryAcquire(discordId: Long, guildId: Long, game: CasinoGameKey): AcquireResult {
        val cooldownSeconds = cooldownSeconds(guildId)
        if (cooldownSeconds <= 0L) return AcquireResult.Ok
        val cooldownMs = cooldownSeconds * 1_000L
        val now = clock()
        val key = Key(discordId, game)
        val last = lastInvokes[key] ?: return AcquireResult.Ok
        val elapsed = now - last
        if (elapsed >= cooldownMs) return AcquireResult.Ok
        return AcquireResult.OnCooldown(remainingMs = cooldownMs - elapsed)
    }

    /**
     * Arm the cooldown timer for [discordId] / [game]. Call this only
     * after a successful wager debit — failed pre-checks must not
     * trigger the timer or a typo will lock a player out.
     */
    fun arm(discordId: Long, game: CasinoGameKey) {
        lastInvokes[Key(discordId, game)] = clock()
    }

    /** Test/admin hook: forget every recorded cooldown. */
    fun reset() {
        lastInvokes.clear()
    }

    /** Test/admin hook: forget the cooldown for a single user. */
    fun reset(discordId: Long) {
        lastInvokes.keys.removeIf { it.discordId == discordId }
    }

    private fun cooldownSeconds(guildId: Long): Long {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.CASINO_COOLDOWN_SECONDS.configValue,
            guildId.toString(),
        )
        val raw = cfg?.value?.toLongOrNull() ?: return DEFAULT_COOLDOWN_SECONDS
        return raw.coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS)
    }

    companion object {
        const val DEFAULT_COOLDOWN_SECONDS: Long = 3L
        const val MIN_COOLDOWN_SECONDS: Long = 0L
        const val MAX_COOLDOWN_SECONDS: Long = 30L
    }
}

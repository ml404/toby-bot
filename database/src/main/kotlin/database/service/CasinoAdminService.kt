package database.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Casino remediation operations that don't have a natural home in the
 * minigame services. Wraps `JackpotService` and `UserService` so the
 * Discord moderation command and the web admin tab both go through one
 * transactional path.
 *
 * Permission checks are the caller's responsibility — this service does
 * the mutation only. The Discord command verifies guild owner or
 * superuser before invoking; the web layer verifies via the moderation
 * `canModerate` predicate.
 */
@Service
@Transactional
class CasinoAdminService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
) {

    sealed interface RefundOutcome {
        data class Ok(val drained: Long, val newPool: Long, val newSourceBalance: Long) : RefundOutcome
        data class Insufficient(val have: Long, val needed: Long) : RefundOutcome
        data class InvalidAmount(val amount: Long) : RefundOutcome
    }

    /**
     * Zero the per-guild jackpot pool. Returns the amount drained.
     */
    fun resetJackpot(guildId: Long): Long = jackpotService.resetPool(guildId)

    /**
     * Debit [amount] from [sourceDiscordId] and deposit the same amount
     * into the per-guild jackpot pool. Used when a player has been
     * clawed-back from a confirmed exploit and the credits should be
     * returned to the pool that funded them.
     */
    fun refundToJackpot(
        sourceDiscordId: Long,
        guildId: Long,
        amount: Long,
    ): RefundOutcome {
        if (amount <= 0L) return RefundOutcome.InvalidAmount(amount)
        val user = userService.getUserByIdForUpdate(sourceDiscordId, guildId)
        val balance = user?.socialCredit ?: 0L
        if (balance < amount) return RefundOutcome.Insufficient(balance, amount)
        user!!.socialCredit = balance - amount
        userService.updateUser(user)
        val newPool = jackpotService.addToPool(guildId, amount)
        return RefundOutcome.Ok(
            drained = amount,
            newPool = newPool,
            newSourceBalance = user.socialCredit ?: (balance - amount),
        )
    }
}

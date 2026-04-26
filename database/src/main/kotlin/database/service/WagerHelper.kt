package database.service

import database.dto.UserDto

/**
 * Shared spine for the wager-style minigame services
 * ([SlotsService], [CoinflipService], [DiceService], [HighlowService],
 * [ScratchService]). Each had its own copy of:
 *   - validate stake bounds → InvalidStake
 *   - lock the user row → UnknownUser if absent
 *   - check balance vs. stake → InsufficientCredits if short
 *   - run game logic → returns a multiplier
 *   - mutate balance: newBalance = balance + (multiplier × stake) - stake
 *   - persist + return Win/Lose
 *
 * Keeping each service's bespoke `Outcome` sealed type was important —
 * Win and Lose carry game-specific payload (symbols, sides, predicted
 * value, cells, etc). So this helper only centralises the
 * common pre-/post-mutation steps; the service translates [BalanceCheck]
 * to its own outcome type and calls the game logic between them.
 */
internal sealed interface BalanceCheck {
    data class Ok(val user: UserDto, val balance: Long) : BalanceCheck
    data class Insufficient(val stake: Long, val have: Long) : BalanceCheck
    data class InvalidStake(val min: Long, val max: Long) : BalanceCheck
    data object UnknownUser : BalanceCheck
}

internal data class WagerResolution(val payout: Long, val net: Long, val newBalance: Long)

internal object WagerHelper {

    /**
     * Validates [stake] is within [minStake]..[maxStake], locks the user
     * via [UserService.getUserByIdForUpdate], and confirms balance >= stake.
     * Caller switches on the result and translates each variant into its
     * own service-specific outcome.
     */
    fun checkAndLock(
        userService: UserService,
        discordId: Long,
        guildId: Long,
        stake: Long,
        minStake: Long,
        maxStake: Long
    ): BalanceCheck {
        if (stake !in minStake..maxStake) {
            return BalanceCheck.InvalidStake(minStake, maxStake)
        }
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return BalanceCheck.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < stake) return BalanceCheck.Insufficient(stake, balance)
        return BalanceCheck.Ok(user, balance)
    }

    /**
     * Applies a [multiplier] win/loss to a locked user. payout = multiplier
     * × stake (0 on a loss); net = payout - stake (positive win, -stake
     * loss). Persists via [UserService.updateUser] and returns the
     * resolved (payout, net, newBalance) triple. Must be called with a
     * user obtained from [checkAndLock]'s `Ok` branch — the [balance]
     * argument should match what was checked there to avoid TOCTOU drift.
     */
    fun applyMultiplier(
        userService: UserService,
        user: UserDto,
        balance: Long,
        stake: Long,
        multiplier: Long
    ): WagerResolution {
        val payout = multiplier * stake
        val net = payout - stake
        user.socialCredit = balance + net
        userService.updateUser(user)
        return WagerResolution(payout = payout, net = net, newBalance = user.socialCredit ?: 0L)
    }

    /**
     * Fractional-multiplier variant for games whose payout schedule
     * isn't a clean integer (currently only [database.economy.Highlow]'s
     * anchor-aware payouts). payout truncates toward zero so any
     * remainder stays with the house, matching the Long overload's
     * implicit semantics.
     */
    fun applyMultiplier(
        userService: UserService,
        user: UserDto,
        balance: Long,
        stake: Long,
        multiplier: Double
    ): WagerResolution {
        val payout = (stake * multiplier).toLong()
        val net = payout - stake
        user.socialCredit = balance + net
        userService.updateUser(user)
        return WagerResolution(payout = payout, net = net, newBalance = user.socialCredit ?: 0L)
    }
}

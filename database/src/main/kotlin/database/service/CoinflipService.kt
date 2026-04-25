package database.service

import database.economy.Coinflip
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic flip path for the `/coinflip` minigame. Both Discord
 * `/coinflip` and the web `/casino/{guildId}/coinflip` page call through
 * here, mirroring the [SlotsService] pattern: lock the user row first
 * via [UserService.getUserByIdForUpdate], validate inputs, mutate,
 * persist, all inside a single `@Transactional` boundary.
 */
@Service
@Transactional
class CoinflipService(
    private val userService: UserService,
    private val coinflip: Coinflip = Coinflip(),
    private val random: Random = Random.Default
) {

    sealed interface FlipOutcome {
        data class Win(
            val stake: Long,
            val payout: Long,
            val net: Long,
            val landed: Coinflip.Side,
            val predicted: Coinflip.Side,
            val newBalance: Long
        ) : FlipOutcome

        data class Lose(
            val stake: Long,
            val landed: Coinflip.Side,
            val predicted: Coinflip.Side,
            val newBalance: Long
        ) : FlipOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : FlipOutcome
        data class InvalidStake(val min: Long, val max: Long) : FlipOutcome
        data object UnknownUser : FlipOutcome
    }

    fun flip(discordId: Long, guildId: Long, stake: Long, predicted: Coinflip.Side): FlipOutcome {
        return when (val check = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, Coinflip.MIN_STAKE, Coinflip.MAX_STAKE
        )) {
            is BalanceCheck.InvalidStake -> FlipOutcome.InvalidStake(check.min, check.max)
            BalanceCheck.UnknownUser -> FlipOutcome.UnknownUser
            is BalanceCheck.Insufficient -> FlipOutcome.InsufficientCredits(check.stake, check.have)
            is BalanceCheck.Ok -> {
                val flip = coinflip.flip(predicted, random)
                val r = WagerHelper.applyMultiplier(userService, check.user, check.balance, stake, flip.multiplier)
                if (flip.isWin) {
                    FlipOutcome.Win(
                        stake = stake,
                        payout = r.payout,
                        net = r.net,
                        landed = flip.landed,
                        predicted = flip.predicted,
                        newBalance = r.newBalance
                    )
                } else {
                    FlipOutcome.Lose(
                        stake = stake,
                        landed = flip.landed,
                        predicted = flip.predicted,
                        newBalance = r.newBalance
                    )
                }
            }
        }
    }
}

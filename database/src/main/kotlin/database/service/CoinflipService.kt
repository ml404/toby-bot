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
 *
 * Web callers can request `autoTopUp` to sell TOBY for the credit
 * shortfall via [CasinoTopUpHelper]; the resulting trade row is tagged
 * `CASINO_TOPUP`.
 */
@Service
@Transactional
class CoinflipService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
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
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : FlipOutcome

        data class Lose(
            val stake: Long,
            val landed: Coinflip.Side,
            val predicted: Coinflip.Side,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L,
        ) : FlipOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : FlipOutcome
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : FlipOutcome
        data class InvalidStake(val min: Long, val max: Long) : FlipOutcome
        data object UnknownUser : FlipOutcome
    }

    fun flip(
        discordId: Long,
        guildId: Long,
        stake: Long,
        predicted: Coinflip.Side,
        autoTopUp: Boolean = false
    ): FlipOutcome {
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, Coinflip.MIN_STAKE, Coinflip.MAX_STAKE, autoTopUp
        )) {
            is TopUpResolution.InvalidStake -> return FlipOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return FlipOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return FlipOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return FlipOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val flip = coinflip.flip(predicted, random)
        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, flip.multiplier)
        return if (flip.isWin) {
            val jackpot = JackpotHelper.rollOnWin(jackpotService, configService, userService, resolved.user, guildId, random)
            FlipOutcome.Win(
                stake = stake,
                payout = wager.payout,
                net = wager.net,
                landed = flip.landed,
                predicted = flip.predicted,
                newBalance = wager.newBalance + jackpot,
                jackpotPayout = jackpot,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            FlipOutcome.Lose(
                stake = stake,
                landed = flip.landed,
                predicted = flip.predicted,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
                lossTribute = tribute,
            )
        }
    }
}

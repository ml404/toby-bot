package database.service

import common.casino.CasinoCommonFailure
import database.dto.ConfigDto
import database.economy.Roulette
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic spin path for the `/roulette` minigame. Both the Discord
 * `/roulette` command and the web `/casino/{guildId}/roulette` page call
 * through here so the debit/credit maths and the random draw only live
 * in one place.
 *
 * Pattern mirrors [SlotsService] / [CoinflipService]: lock the user row
 * via [UserService.getUserByIdForUpdate], validate inputs against the
 * live balance, mutate, persist — all inside a single `@Transactional`
 * boundary so concurrent `/roulette` calls from the same user (Discord
 * + web at once, or just spam-clicking) can't double-spend the stake.
 *
 * Wins also roll [JackpotHelper] for a chance to bank the per-guild
 * jackpot pool; losses divert a tribute fraction into it (same shared
 * pool that every casino game feeds into and pays out from).
 */
@Service
@Transactional
class RouletteService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val roulette: Roulette = Roulette(),
    private val random: Random = Random.Default,
) {

    sealed interface SpinOutcome {
        data class Win(
            val stake: Long,
            val bet: Roulette.Bet,
            val landed: Int,
            val color: Roulette.Color,
            val straightNumber: Int?,
            val multiplier: Long,
            val payout: Long,
            val net: Long,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : SpinOutcome

        data class Lose(
            val stake: Long,
            val bet: Roulette.Bet,
            val landed: Int,
            val color: Roulette.Color,
            val straightNumber: Int?,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L,
        ) : SpinOutcome

        data class InvalidNumber(val min: Int, val max: Int) : SpinOutcome

        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            SpinOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            SpinOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data class InvalidStake(override val min: Long, override val max: Long) :
            SpinOutcome, CasinoCommonFailure.InvalidStake
        data object UnknownUser : SpinOutcome, CasinoCommonFailure.UnknownUser
    }

    fun spin(
        discordId: Long,
        guildId: Long,
        stake: Long,
        bet: Roulette.Bet,
        straightNumber: Int? = null,
        autoTopUp: Boolean = false,
    ): SpinOutcome {
        // STRAIGHT requires a 0-36 pick; non-STRAIGHT bets ignore any value
        // the caller passed (the Discord command and web controller both
        // null the field for outside bets, but defending here keeps the
        // service contract self-validating).
        val effectiveNumber = if (bet == Roulette.Bet.STRAIGHT) straightNumber else null
        if (bet == Roulette.Bet.STRAIGHT &&
            (effectiveNumber == null || effectiveNumber !in 0..Roulette.MAX_NUMBER)
        ) {
            return SpinOutcome.InvalidNumber(0, Roulette.MAX_NUMBER)
        }

        val minStake = configService.cfgLong(
            ConfigDto.Configurations.ROULETTE_MIN_STAKE, guildId, default = Roulette.MIN_STAKE, min = 1L
        )
        val maxStake = configService.cfgLongMax(
            ConfigDto.Configurations.ROULETTE_MAX_STAKE, guildId, default = Roulette.MAX_STAKE, min = minStake
        )
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, minStake, maxStake, autoTopUp,
        )) {
            is TopUpResolution.InvalidStake -> return SpinOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return SpinOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return SpinOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return SpinOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val spin = roulette.spin(bet, effectiveNumber, random)
        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, spin.multiplier)
        return if (spin.isWin) {
            val jackpot = JackpotHelper.rollOnWin(
                jackpotService, configService, userService, resolved.user, guildId,
                stake, random,
            )
            SpinOutcome.Win(
                stake = stake,
                bet = bet,
                landed = spin.landed,
                color = spin.color,
                straightNumber = effectiveNumber,
                multiplier = spin.multiplier,
                payout = wager.payout,
                net = wager.net,
                newBalance = wager.newBalance + jackpot,
                jackpotPayout = jackpot,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            SpinOutcome.Lose(
                stake = stake,
                bet = bet,
                landed = spin.landed,
                color = spin.color,
                straightNumber = effectiveNumber,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
                lossTribute = tribute,
            )
        }
    }
}

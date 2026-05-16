package database.service

import common.casino.CasinoCommonFailure
import database.dto.ConfigDto
import database.economy.Plinko
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic drop path for the `/plinko` minigame. Same lock-then-mutate
 * pattern as [SlotsService] / [DiceService] via
 * [UserService.getUserByIdForUpdate] so concurrent drops from the same
 * user (Discord + web at once, or spam-clicking) can't double-spend.
 *
 * Wins (multiplier > 1×) roll [JackpotHelper] for a chance to bank the
 * per-guild jackpot pool. Losses tribute the lost portion of the stake
 * back into the pool — for partial-loss buckets (e.g. LOW profile's 0.4×
 * center) only the net loss feeds the pool, not the whole stake. Pushes
 * (multiplier == 1×) skip both branches: no win-roll, no tribute.
 */
@Service
@Transactional
class PlinkoService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val plinko: Plinko = Plinko(),
    private val random: Random = Random.Default
) {

    sealed interface DropOutcome {
        data class Win(
            val stake: Long,
            val risk: Plinko.Risk,
            val bucket: Int,
            val multiplier: Double,
            val payout: Long,
            val net: Long,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val jackpotTierIndex: Int = -1,
            val jackpotTierPayoutPct: Double = 0.0,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : DropOutcome

        data class Lose(
            val stake: Long,
            val risk: Plinko.Risk,
            val bucket: Int,
            val multiplier: Double,
            val payout: Long,
            val net: Long,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L,
        ) : DropOutcome

        data class Push(
            val stake: Long,
            val risk: Plinko.Risk,
            val bucket: Int,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : DropOutcome

        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            DropOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            DropOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data class InvalidStake(override val min: Long, override val max: Long) :
            DropOutcome, CasinoCommonFailure.InvalidStake
        data object UnknownUser : DropOutcome, CasinoCommonFailure.UnknownUser
    }

    fun drop(
        discordId: Long,
        guildId: Long,
        stake: Long,
        risk: Plinko.Risk,
        autoTopUp: Boolean = false,
    ): DropOutcome {
        val minStake = configService.cfgLong(
            ConfigDto.Configurations.PLINKO_MIN_STAKE, guildId, default = Plinko.MIN_STAKE, min = 1L
        )
        val maxStake = configService.cfgLongMax(
            ConfigDto.Configurations.PLINKO_MAX_STAKE, guildId, default = Plinko.MAX_STAKE, min = minStake
        )
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, minStake, maxStake, autoTopUp,
        )) {
            is TopUpResolution.InvalidStake -> return DropOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return DropOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return DropOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return DropOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val result = plinko.drop(risk, random)
        val wager = WagerHelper.applyMultiplier(
            userService, resolved.user, resolved.balance, stake, result.multiplier
        )
        return when {
            result.isWin -> {
                val jackpot = JackpotHelper.rollOnWin(
                    jackpotService, configService, userService, resolved.user, guildId,
                    stake, JackpotGame.PLINKO, random,
                )
                DropOutcome.Win(
                    stake = stake,
                    risk = risk,
                    bucket = result.bucket,
                    multiplier = result.multiplier,
                    payout = wager.payout,
                    net = wager.net,
                    newBalance = wager.newBalance + jackpot.amount,
                    jackpotPayout = jackpot.amount,
                    jackpotTierIndex = jackpot.tierIndex,
                    jackpotTierPayoutPct = jackpot.tierPayoutPct,
                    soldTobyCoins = resolved.soldCoins,
                    newPrice = resolved.newPrice,
                )
            }
            result.isPush -> DropOutcome.Push(
                stake = stake,
                risk = risk,
                bucket = result.bucket,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
            )
            else -> {
                // Tribute on the actual lost portion, not the full stake —
                // a 0.4× bucket loses 0.6 × stake, not the whole stake.
                val lossAmount = stake - wager.payout
                val tribute = JackpotHelper.divertOnLoss(
                    jackpotService, configService, guildId, lossAmount
                )
                DropOutcome.Lose(
                    stake = stake,
                    risk = risk,
                    bucket = result.bucket,
                    multiplier = result.multiplier,
                    payout = wager.payout,
                    net = wager.net,
                    newBalance = wager.newBalance,
                    soldTobyCoins = resolved.soldCoins,
                    newPrice = resolved.newPrice,
                    lossTribute = tribute,
                )
            }
        }
    }
}

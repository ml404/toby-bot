package database.service.casino.dice

import common.casino.CasinoCommonFailure
import common.events.DiceWonEvent
import database.dto.guild.ConfigDto
import common.economy.Dice
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random
import database.service.casino.CasinoEdgeService
import database.service.guild.ConfigService
import database.service.economy.EconomyTradeService
import database.service.economy.JackpotHelper
import database.service.economy.JackpotService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService
import database.service.pvp.WagerHelper
import database.service.economy.JackpotGame
import database.service.pvp.TopUpResolution
import database.service.guild.cfgLong
import database.service.guild.cfgLongMax

/**
 * Atomic roll path for the `/dice` minigame. Same lock-then-mutate
 * pattern as [SlotsService] / [CoinflipService] via
 * [UserService.getUserByIdForUpdate] so concurrent rolls from the same
 * user (Discord + web at once, or spam-clicking) can't double-spend.
 *
 * Web callers can request `autoTopUp` to sell TOBY for the credit
 * shortfall via [CasinoTopUpHelper]; the resulting trade row is tagged
 * `CASINO_TOPUP`.
 */
@Service
@Transactional
class DiceService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val casinoEdgeService: CasinoEdgeService,
    private val dice: Dice = Dice(),
    private val random: Random = Random.Default,
    private val eventPublisher: ApplicationEventPublisher? = null,
) {

    sealed interface RollOutcome {
        data class Win(
            val stake: Long,
            val payout: Long,
            val net: Long,
            val landed: Int,
            val predicted: Int,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val jackpotTierIndex: Int = -1,
            val jackpotTierPayoutPct: Double = 0.0,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : RollOutcome

        data class Lose(
            val stake: Long,
            val landed: Int,
            val predicted: Int,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L,
        ) : RollOutcome

        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            RollOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            RollOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data class InvalidStake(override val min: Long, override val max: Long) :
            RollOutcome, CasinoCommonFailure.InvalidStake
        data class InvalidPrediction(val min: Int, val max: Int) : RollOutcome
        data object UnknownUser : RollOutcome, CasinoCommonFailure.UnknownUser
    }

    fun roll(
        discordId: Long,
        guildId: Long,
        stake: Long,
        predicted: Int,
        autoTopUp: Boolean = false,
        clickX: Int? = null,
        clickY: Int? = null,
        mouseMoved: Boolean? = null,
    ): RollOutcome {
        if (!dice.isValidPrediction(predicted)) {
            return RollOutcome.InvalidPrediction(1, dice.sidesCount)
        }
        val minStake = configService.cfgLong(
            ConfigDto.Configurations.DICE_MIN_STAKE, guildId, default = Dice.MIN_STAKE, min = 1L
        )
        val maxStake = configService.cfgLongMax(
            ConfigDto.Configurations.DICE_MAX_STAKE, guildId, default = Dice.MAX_STAKE, min = minStake
        )
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, minStake, maxStake, autoTopUp,
        )) {
            is TopUpResolution.InvalidStake -> return RollOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return RollOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return RollOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return RollOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val fairRoll = dice.roll(predicted, random)
        // Anti-autoclicker substitution: with probability proportional
        // to the suspect's streak, replace the fair roll with a forced
        // loss landing on a random non-predicted face.
        val roll = casinoEdgeService.applyBotEdge(
            discordId = discordId,
            guildId = guildId,
            gameKey = "dice",
            clickX = clickX, clickY = clickY, mouseMoved = mouseMoved,
            edgeMaxConfig = ConfigDto.Configurations.DICE_BOT_EDGE_MAX_PCT,
            fairOutcome = fairRoll,
            asLoss = {
                // Pick any face other than the predicted one. We don't care
                // which loser face — the player only sees "you predicted X,
                // it landed Y" so any non-X reads naturally.
                val loserFace = (1..dice.sidesCount).first { it != predicted }
                Dice.Roll(landed = loserFace, predicted = predicted, multiplier = 0L)
            },
        )
        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, roll.multiplier)
        return if (roll.isWin) {
            eventPublisher?.publishEvent(DiceWonEvent(discordId = discordId, guildId = guildId))
            val jackpot = JackpotHelper.rollOnWin(
                jackpotService, configService, userService, resolved.user, guildId,
                stake, JackpotGame.DICE, random,
            )
            RollOutcome.Win(
                stake = stake,
                payout = wager.payout,
                net = wager.net,
                landed = roll.landed,
                predicted = roll.predicted,
                newBalance = wager.newBalance + jackpot.amount,
                jackpotPayout = jackpot.amount,
                jackpotTierIndex = jackpot.tierIndex,
                jackpotTierPayoutPct = jackpot.tierPayoutPct,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            RollOutcome.Lose(
                stake = stake,
                landed = roll.landed,
                predicted = roll.predicted,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
                lossTribute = tribute,
            )
        }
    }
}

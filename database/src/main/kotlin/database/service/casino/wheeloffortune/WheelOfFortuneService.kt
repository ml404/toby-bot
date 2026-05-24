package database.service.casino.wheeloffortune

import common.casino.CasinoCommonFailure
import common.events.WheelJackpotEvent
import database.dto.ConfigDto
import common.economy.WheelOfFortune
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
 * Atomic spin path for the `/wheel` minigame. Same lock-then-mutate
 * pattern as the other wager-style minigame services. The player picks
 * one of [WheelOfFortune.PICKS] multipliers up front; the wheel spins
 * once and the player wins `pick × stake` only if the landed multiplier
 * equals their picked multiplier. Otherwise the stake is lost.
 *
 * Wins roll [JackpotHelper] for a chance to bank the per-guild jackpot
 * pool; losses tribute the full stake into the pool (the wheel has no
 * partial-loss buckets).
 */
@Service
@Transactional
class WheelOfFortuneService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val casinoEdgeService: CasinoEdgeService,
    private val wheel: WheelOfFortune = WheelOfFortune(),
    private val random: Random = Random.Default,
    private val eventPublisher: ApplicationEventPublisher? = null,
) {

    sealed interface SpinOutcome {
        data class Win(
            val stake: Long,
            val pickedMultiplier: Long,
            val landedMultiplier: Long,
            val payout: Long,
            val net: Long,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val jackpotTierIndex: Int = -1,
            val jackpotTierPayoutPct: Double = 0.0,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : SpinOutcome

        data class Lose(
            val stake: Long,
            val pickedMultiplier: Long,
            val landedMultiplier: Long,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L,
        ) : SpinOutcome

        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            SpinOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            SpinOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data class InvalidStake(override val min: Long, override val max: Long) :
            SpinOutcome, CasinoCommonFailure.InvalidStake
        data class InvalidPick(val picks: List<Long>) : SpinOutcome
        data object UnknownUser : SpinOutcome, CasinoCommonFailure.UnknownUser
    }

    fun spin(
        discordId: Long,
        guildId: Long,
        stake: Long,
        pickedMultiplier: Long,
        autoTopUp: Boolean = false,
        clickX: Int? = null,
        clickY: Int? = null,
        mouseMoved: Boolean? = null,
    ): SpinOutcome {
        if (!wheel.isValidPick(pickedMultiplier)) {
            return SpinOutcome.InvalidPick(wheel.picks())
        }
        val minStake = configService.cfgLong(
            ConfigDto.Configurations.WHEEL_OF_FORTUNE_MIN_STAKE, guildId,
            default = WheelOfFortune.MIN_STAKE, min = 1L
        )
        val maxStake = configService.cfgLongMax(
            ConfigDto.Configurations.WHEEL_OF_FORTUNE_MAX_STAKE, guildId,
            default = WheelOfFortune.MAX_STAKE, min = minStake
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

        val fairResult = wheel.spin(pickedMultiplier, random)
        // Anti-autoclicker substitution: replace the fair spin with a
        // landing on any non-picked multiplier (guaranteed loss for the
        // player's pick). The wheel animation still settles naturally.
        val result = casinoEdgeService.applyBotEdge(
            discordId = discordId,
            guildId = guildId,
            gameKey = "wheel",
            clickX = clickX, clickY = clickY, mouseMoved = mouseMoved,
            edgeMaxConfig = ConfigDto.Configurations.WHEEL_OF_FORTUNE_BOT_EDGE_MAX_PCT,
            fairOutcome = fairResult,
            asLoss = {
                val loserMult = wheel.picks().first { it != pickedMultiplier }
                WheelOfFortune.Spin(landedMultiplier = loserMult, pickedMultiplier = pickedMultiplier)
            },
        )
        val multiplier = if (result.isWin) pickedMultiplier else 0L
        val wager = WagerHelper.applyMultiplier(
            userService, resolved.user, resolved.balance, stake, multiplier
        )
        return if (result.isWin) {
            // Top-tier pick (max of WheelOfFortune.PICKS) landing is the
            // jackpot achievement trigger. Lower-multiplier wins still pay
            // out but don't fire the event.
            if (pickedMultiplier == WheelOfFortune.PICKS.max()) {
                eventPublisher?.publishEvent(WheelJackpotEvent(discordId = discordId, guildId = guildId))
            }
            val jackpot = JackpotHelper.rollOnWin(
                jackpotService, configService, userService, resolved.user, guildId,
                stake, JackpotGame.WHEEL_OF_FORTUNE, random,
            )
            SpinOutcome.Win(
                stake = stake,
                pickedMultiplier = pickedMultiplier,
                landedMultiplier = result.landedMultiplier,
                payout = wager.payout,
                net = wager.net,
                newBalance = wager.newBalance + jackpot.amount,
                jackpotPayout = jackpot.amount,
                jackpotTierIndex = jackpot.tierIndex,
                jackpotTierPayoutPct = jackpot.tierPayoutPct,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            SpinOutcome.Lose(
                stake = stake,
                pickedMultiplier = pickedMultiplier,
                landedMultiplier = result.landedMultiplier,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
                lossTribute = tribute,
            )
        }
    }
}

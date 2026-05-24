package database.service.casino.coinflip

import common.casino.CasinoCommonFailure
import common.events.CoinflipWonEvent
import database.dto.ConfigDto
import common.economy.Coinflip
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
 * Atomic flip path for the `/coinflip` minigame. Both Discord
 * `/coinflip` and the web `/casino/{guildId}/coinflip` page call through
 * here, mirroring the [SlotsService] pattern: lock the user row first
 * via [UserService.getUserByIdForUpdate], validate inputs, mutate,
 * persist, all inside a single `@Transactional` boundary.
 *
 * Web callers can request `autoTopUp` to sell TOBY for the credit
 * shortfall via [CasinoTopUpHelper]; the resulting trade row is tagged
 * `CASINO_TOPUP`.
 *
 * Anti-autoclicker bias is applied via [CasinoEdgeService]: the fair
 * flip outcome is computed first, then potentially substituted for a
 * loss outcome based on the player's bot-suspicion streak. Discord
 * call paths supply `null` for the click signals, which makes the gate
 * a no-op (streak resets to 0 → bias = 0).
 */
@Service
@Transactional
class CoinflipService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val casinoEdgeService: CasinoEdgeService,
    private val coinflip: Coinflip = Coinflip(),
    private val random: Random = Random.Default,
    private val eventPublisher: ApplicationEventPublisher? = null,
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
            val jackpotTierIndex: Int = -1,
            val jackpotTierPayoutPct: Double = 0.0,
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

        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            FlipOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            FlipOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data class InvalidStake(override val min: Long, override val max: Long) :
            FlipOutcome, CasinoCommonFailure.InvalidStake
        data object UnknownUser : FlipOutcome, CasinoCommonFailure.UnknownUser
    }

    fun flip(
        discordId: Long,
        guildId: Long,
        stake: Long,
        predicted: Coinflip.Side,
        autoTopUp: Boolean = false,
        clickX: Int? = null,
        clickY: Int? = null,
        mouseMoved: Boolean? = null,
    ): FlipOutcome {
        val minStake = configService.cfgLong(
            ConfigDto.Configurations.COINFLIP_MIN_STAKE, guildId, default = Coinflip.MIN_STAKE, min = 1L
        )
        val maxStake = configService.cfgLongMax(
            ConfigDto.Configurations.COINFLIP_MAX_STAKE, guildId, default = Coinflip.MAX_STAKE, min = minStake
        )
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, minStake, maxStake, autoTopUp,
        )) {
            is TopUpResolution.InvalidStake -> return FlipOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return FlipOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return FlipOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return FlipOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val fairFlip = coinflip.flip(predicted, random)
        // Suspected autoclickers get a forced-loss substitution at the
        // configured cap; legitimate play keeps the fair outcome.
        val flip = casinoEdgeService.applyBotEdge(
            discordId = discordId,
            guildId = guildId,
            gameKey = "coinflip",
            clickX = clickX, clickY = clickY, mouseMoved = mouseMoved,
            edgeMaxConfig = ConfigDto.Configurations.COINFLIP_BOT_EDGE_MAX_PCT,
            fairOutcome = fairFlip,
            asLoss = {
                val opposite = if (predicted == Coinflip.Side.HEADS) Coinflip.Side.TAILS else Coinflip.Side.HEADS
                Coinflip.Flip(landed = opposite, predicted = predicted, multiplier = 0L)
            },
        )

        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, flip.multiplier)
        return if (flip.isWin) {
            eventPublisher?.publishEvent(CoinflipWonEvent(discordId = discordId, guildId = guildId))
            val jackpot = JackpotHelper.rollOnWin(
                jackpotService, configService, userService, resolved.user, guildId,
                stake, JackpotGame.COINFLIP, random,
            )
            FlipOutcome.Win(
                stake = stake,
                payout = wager.payout,
                net = wager.net,
                landed = flip.landed,
                predicted = flip.predicted,
                newBalance = wager.newBalance + jackpot.amount,
                jackpotPayout = jackpot.amount,
                jackpotTierIndex = jackpot.tierIndex,
                jackpotTierPayoutPct = jackpot.tierPayoutPct,
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

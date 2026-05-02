package database.service

import database.economy.ScratchCard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic scratch path for the `/scratch` minigame. Same lock-then-mutate
 * pattern as the other minigame services.
 *
 * Web callers can request `autoTopUp` to sell TOBY for the credit
 * shortfall via [CasinoTopUpHelper]; the resulting trade row is tagged
 * `CASINO_TOPUP`.
 */
@Service
@Transactional
class ScratchService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val card: ScratchCard = ScratchCard(),
    private val random: Random = Random.Default
) {

    sealed interface ScratchOutcome {
        data class Win(
            val stake: Long,
            val payout: Long,
            val net: Long,
            val cells: List<database.economy.SlotMachine.Symbol>,
            val winningSymbol: database.economy.SlotMachine.Symbol,
            val matchCount: Int,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : ScratchOutcome

        data class Lose(
            val stake: Long,
            val cells: List<database.economy.SlotMachine.Symbol>,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L,
        ) : ScratchOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : ScratchOutcome
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : ScratchOutcome
        data class InvalidStake(val min: Long, val max: Long) : ScratchOutcome
        data object UnknownUser : ScratchOutcome
    }

    fun scratch(discordId: Long, guildId: Long, stake: Long, autoTopUp: Boolean = false): ScratchOutcome {
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, ScratchCard.MIN_STAKE, ScratchCard.MAX_STAKE, autoTopUp
        )) {
            is TopUpResolution.InvalidStake -> return ScratchOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return ScratchOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return ScratchOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return ScratchOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val result = card.scratch(random)
        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, result.multiplier)
        return if (result.isWin && result.winningSymbol != null) {
            val jackpot = JackpotHelper.rollOnWin(jackpotService, configService, userService, resolved.user, guildId, random)
            ScratchOutcome.Win(
                stake = stake,
                payout = wager.payout,
                net = wager.net,
                cells = result.cells,
                winningSymbol = result.winningSymbol,
                matchCount = result.matchCount,
                newBalance = wager.newBalance + jackpot,
                jackpotPayout = jackpot,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            ScratchOutcome.Lose(
                stake = stake,
                cells = result.cells,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
                lossTribute = tribute,
            )
        }
    }
}

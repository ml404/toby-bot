package database.service

import database.economy.SlotMachine
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic spin path for the `/slots` minigame. Both the Discord `/slots`
 * command and the web `/casino/{guildId}/slots` page call through here so
 * the debit/credit maths and the random draw only live in one place.
 *
 * Pattern mirrors [EconomyTradeService]: lock the user row first
 * ([UserService.getUserByIdForUpdate]), validate inputs against the live
 * balance, mutate, persist. The whole thing runs inside one
 * `@Transactional` boundary so concurrent `/slots` invocations from the
 * same user (Discord + web at once, or just spam-clicking) can't double-
 * spend the stake.
 *
 * Wins also roll [JackpotHelper] for a chance to bank the per-guild
 * jackpot pool (fed by trade fees in [EconomyTradeService]).
 *
 * When the caller passes `autoTopUp=true` and the player's credits
 * fall short of the stake, [CasinoTopUpHelper] sells just enough TOBY
 * to cover (mirroring TitlesWebService's "Buy with TOBY" flow). The
 * resulting trade row is tagged `CASINO_TOPUP` so the chart marker
 * can show why the sale happened.
 */
@Service
@Transactional
class SlotsService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val machine: SlotMachine = SlotMachine(),
    private val random: Random = Random.Default
) {

    sealed interface SpinOutcome {
        data class Win(
            val stake: Long,
            val multiplier: Long,
            val payout: Long,
            val net: Long,
            val symbols: List<SlotMachine.Symbol>,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : SpinOutcome

        data class Lose(
            val stake: Long,
            val symbols: List<SlotMachine.Symbol>,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L
        ) : SpinOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : SpinOutcome
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : SpinOutcome
        data class InvalidStake(val min: Long, val max: Long) : SpinOutcome
        data object UnknownUser : SpinOutcome
    }

    fun spin(discordId: Long, guildId: Long, stake: Long, autoTopUp: Boolean = false): SpinOutcome {
        val initial = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, SlotMachine.MIN_STAKE, SlotMachine.MAX_STAKE
        )
        var soldCoins = 0L
        var newPrice: Double? = null
        val resolved = when (initial) {
            is BalanceCheck.InvalidStake -> return SpinOutcome.InvalidStake(initial.min, initial.max)
            BalanceCheck.UnknownUser -> return SpinOutcome.UnknownUser
            is BalanceCheck.Insufficient -> {
                if (!autoTopUp) return SpinOutcome.InsufficientCredits(initial.stake, initial.have)
                // Lock the user row to feed the helper. WagerHelper already
                // returned Insufficient — re-acquire to get the managed entity.
                val user = userService.getUserByIdForUpdate(discordId, guildId)
                    ?: return SpinOutcome.UnknownUser
                val topUp = CasinoTopUpHelper.ensureCreditsForWager(
                    tradeService, marketService, userService,
                    user, guildId, currentBalance = initial.have, stake = stake
                )
                when (topUp) {
                    is TopUpResult.InsufficientCoins ->
                        return SpinOutcome.InsufficientCoinsForTopUp(topUp.needed, topUp.have)
                    TopUpResult.MarketUnavailable ->
                        return SpinOutcome.InsufficientCredits(initial.stake, initial.have)
                    is TopUpResult.ToppedUp -> {
                        soldCoins = topUp.soldCoins
                        newPrice = topUp.newPrice
                        BalanceCheck.Ok(topUp.user, topUp.balance)
                    }
                }
            }
            is BalanceCheck.Ok -> initial
        }

        val pull = machine.pull(random)
        val r = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, pull.multiplier)
        return if (pull.isWin) {
            val jackpot = JackpotHelper.rollOnWin(jackpotService, userService, resolved.user, guildId, random)
            SpinOutcome.Win(
                stake = stake,
                multiplier = pull.multiplier,
                payout = r.payout,
                net = r.net,
                symbols = pull.symbols,
                newBalance = r.newBalance + jackpot,
                jackpotPayout = jackpot,
                soldTobyCoins = soldCoins,
                newPrice = newPrice
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            SpinOutcome.Lose(
                stake = stake,
                symbols = pull.symbols,
                newBalance = r.newBalance,
                soldTobyCoins = soldCoins,
                newPrice = newPrice,
                lossTribute = tribute
            )
        }
    }
}

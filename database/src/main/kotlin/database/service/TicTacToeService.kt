package database.service

import common.events.TicTacToeResolvedEvent
import database.dto.ConfigDto
import database.dto.UserDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Head-to-head Tic-Tac-Toe wager between two users. Three phases —
 * mirrors `/rps`'s shape but without the hidden-pick stage, since
 * TTT moves are open. Moves themselves are tracked in
 * [database.tictactoe.TicTacToeSessionRegistry]; this service stays
 * pure wager-math.
 *
 *   - [startMatch] — pre-flight balance / stake-bounds check. Does NOT
 *     debit. Lets the slash command refuse a doomed offer up-front.
 *   - [acceptMatch] — atomic debit. Locks both users in ascending
 *     discord-id order, re-verifies both balances, debits the stake
 *     from each. The board state is held in the in-memory session
 *     registry (not this service).
 *   - [resolveMatch] — credits the winner pot minus jackpot tribute on
 *     a win, OR refunds both stakes on a draw. Single entry point for
 *     both happy-path resolution and forfeit/timeout settlement so
 *     the branching wager arithmetic lives in one place.
 *
 * Loss tribute is computed the same way as `/duel` / `/rps` (via
 * [JackpotHelper]) — every wager game feeds the same per-guild
 * jackpot pool so the economy stays cohesive.
 *
 * Has **no JDA dependency by design** — that's the regression the
 * Profile cards Spring-cycle (PR #551) taught us. Services reachable
 * from a `Command` bean must not pull `JDA` from the container.
 */
@Service
@Transactional
class TicTacToeService @Autowired constructor(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val configService: ConfigService,
    private val xpAwardService: XpAwardService,
    private val eventPublisher: ApplicationEventPublisher? = null,
) {

    sealed interface StartOutcome {
        data class Ok(val initiatorBalance: Long) : StartOutcome
        data class InvalidStake(val min: Long, val max: Long) : StartOutcome
        data class InvalidOpponent(val reason: Reason) : StartOutcome {
            enum class Reason { SELF, BOT }
        }
        data class InitiatorInsufficient(val have: Long, val needed: Long) : StartOutcome
        data class OpponentInsufficient(val have: Long, val needed: Long) : StartOutcome
        data object UnknownInitiator : StartOutcome
        data object UnknownOpponent : StartOutcome
    }

    sealed interface AcceptOutcome {
        data class Ok(val initiatorNewBalance: Long, val opponentNewBalance: Long) : AcceptOutcome
        data class InitiatorInsufficient(val have: Long, val needed: Long) : AcceptOutcome
        data class OpponentInsufficient(val have: Long, val needed: Long) : AcceptOutcome
        data object UnknownInitiator : AcceptOutcome
        data object UnknownOpponent : AcceptOutcome
    }

    sealed interface ResolveOutcome {
        data class Win(
            val winnerDiscordId: Long,
            val loserDiscordId: Long,
            val stake: Long,
            val pot: Long,
            val winnerNewBalance: Long,
            val loserNewBalance: Long,
            val lossTribute: Long,
            val xpGranted: Long,
        ) : ResolveOutcome

        /** Board full with no winner; both stakes refunded. */
        data class Draw(
            val stake: Long,
            val initiatorNewBalance: Long,
            val opponentNewBalance: Long,
        ) : ResolveOutcome

        data object UnknownInitiator : ResolveOutcome
        data object UnknownOpponent : ResolveOutcome
    }

    fun startMatch(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): StartOutcome {
        val (minStake, maxStake) = readStakeBounds(guildId)
        if (stake < minStake || stake > maxStake) {
            return StartOutcome.InvalidStake(minStake, maxStake)
        }
        if (initiatorDiscordId == opponentDiscordId) {
            return StartOutcome.InvalidOpponent(StartOutcome.InvalidOpponent.Reason.SELF)
        }
        val initiator = userService.getUserById(initiatorDiscordId, guildId)
            ?: return StartOutcome.UnknownInitiator
        val opponent = userService.getUserById(opponentDiscordId, guildId)
            ?: return StartOutcome.UnknownOpponent

        val initiatorBalance = initiator.socialCredit ?: 0L
        if (initiatorBalance < stake) {
            return StartOutcome.InitiatorInsufficient(initiatorBalance, stake)
        }
        val opponentBalance = opponent.socialCredit ?: 0L
        if (opponentBalance < stake) {
            return StartOutcome.OpponentInsufficient(opponentBalance, stake)
        }
        return StartOutcome.Ok(initiatorBalance)
    }

    fun acceptMatch(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): AcceptOutcome {
        // Free-play short-circuit: no debits needed.
        if (stake <= 0L) {
            val initiator = userService.getUserById(initiatorDiscordId, guildId)
                ?: return AcceptOutcome.UnknownInitiator
            val opponent = userService.getUserById(opponentDiscordId, guildId)
                ?: return AcceptOutcome.UnknownOpponent
            return AcceptOutcome.Ok(
                initiatorNewBalance = initiator.socialCredit ?: 0L,
                opponentNewBalance = opponent.socialCredit ?: 0L,
            )
        }
        val locked = userService.lockUsersInAscendingOrder(
            listOf(initiatorDiscordId, opponentDiscordId), guildId
        )
        val initiator = locked[initiatorDiscordId] ?: return AcceptOutcome.UnknownInitiator
        val opponent = locked[opponentDiscordId] ?: return AcceptOutcome.UnknownOpponent

        val initiatorBalance = initiator.socialCredit ?: 0L
        if (initiatorBalance < stake) {
            return AcceptOutcome.InitiatorInsufficient(initiatorBalance, stake)
        }
        val opponentBalance = opponent.socialCredit ?: 0L
        if (opponentBalance < stake) {
            return AcceptOutcome.OpponentInsufficient(opponentBalance, stake)
        }
        initiator.socialCredit = initiatorBalance - stake
        opponent.socialCredit = opponentBalance - stake
        userService.updateUser(initiator)
        userService.updateUser(opponent)
        return AcceptOutcome.Ok(
            initiatorNewBalance = initiator.socialCredit ?: 0L,
            opponentNewBalance = opponent.socialCredit ?: 0L,
        )
    }

    /**
     * Settle a match. The caller passes [winnerDiscordId] / [loserDiscordId]
     * as the verdict — `null` winner means draw (board full, no line) and
     * both stakes refund. Passing a non-null [winnerDiscordId] covers
     * three real cases that all collapse to the same wager arithmetic:
     *   1. winner completed a 3-in-a-row
     *   2. loser forfeited mid-game
     *   3. loser timed out on a move
     *
     * Idempotent against the user table only if called once per match —
     * the in-memory session registry is responsible for atomically
     * removing the session via `consumeForResolution` before this fires.
     */
    fun resolveMatch(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
        winnerDiscordId: Long?,
    ): ResolveOutcome {
        // Stake-free play: no debits to settle.
        if (stake <= 0L) {
            return resolveFreePlay(initiatorDiscordId, opponentDiscordId, guildId, winnerDiscordId)
        }
        val locked = userService.lockUsersInAscendingOrder(
            listOf(initiatorDiscordId, opponentDiscordId), guildId
        )
        val initiator = locked[initiatorDiscordId] ?: return ResolveOutcome.UnknownInitiator
        val opponent = locked[opponentDiscordId] ?: return ResolveOutcome.UnknownOpponent

        val initiatorBalance = initiator.socialCredit ?: 0L
        val opponentBalance = opponent.socialCredit ?: 0L

        if (winnerDiscordId == null) {
            // Draw — refund both stakes.
            initiator.socialCredit = initiatorBalance + stake
            opponent.socialCredit = opponentBalance + stake
            userService.updateUser(initiator)
            userService.updateUser(opponent)
            return ResolveOutcome.Draw(
                stake = stake,
                initiatorNewBalance = initiator.socialCredit ?: 0L,
                opponentNewBalance = opponent.socialCredit ?: 0L,
            )
        }
        val (winner, loser) = when (winnerDiscordId) {
            initiatorDiscordId -> initiator to opponent
            opponentDiscordId -> opponent to initiator
            else -> return ResolveOutcome.UnknownInitiator // unknown winner — shouldn't happen
        }
        return payWinner(winner = winner, loser = loser, stake = stake, guildId = guildId)
    }

    private fun payWinner(
        winner: UserDto,
        loser: UserDto,
        stake: Long,
        guildId: Long,
    ): ResolveOutcome.Win {
        val winnerStartBalance = winner.socialCredit ?: 0L
        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
        val pot = 2L * stake
        val winnerPayout = pot - tribute
        winner.socialCredit = winnerStartBalance + winnerPayout
        userService.updateUser(winner)
        val xpGranted = xpAwardService.award(
            discordId = winner.discordId,
            guildId = guildId,
            amount = WIN_XP,
            reason = "tictactoe:win",
        )
        val outcome = ResolveOutcome.Win(
            winnerDiscordId = winner.discordId,
            loserDiscordId = loser.discordId,
            stake = stake,
            pot = pot,
            winnerNewBalance = winner.socialCredit ?: 0L,
            loserNewBalance = loser.socialCredit ?: 0L,
            lossTribute = tribute,
            xpGranted = xpGranted,
        )
        publishResolved(outcome, guildId)
        return outcome
    }

    private fun resolveFreePlay(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        winnerDiscordId: Long?,
    ): ResolveOutcome {
        if (winnerDiscordId == null) {
            return ResolveOutcome.Draw(stake = 0L, initiatorNewBalance = 0L, opponentNewBalance = 0L)
        }
        val loserDiscordId = when (winnerDiscordId) {
            initiatorDiscordId -> opponentDiscordId
            opponentDiscordId -> initiatorDiscordId
            else -> return ResolveOutcome.UnknownInitiator
        }
        val xpGranted = xpAwardService.award(
            discordId = winnerDiscordId,
            guildId = guildId,
            amount = WIN_XP,
            reason = "tictactoe:win",
        )
        val outcome = ResolveOutcome.Win(
            winnerDiscordId = winnerDiscordId,
            loserDiscordId = loserDiscordId,
            stake = 0L,
            pot = 0L,
            winnerNewBalance = 0L,
            loserNewBalance = 0L,
            lossTribute = 0L,
            xpGranted = xpGranted,
        )
        publishResolved(outcome, guildId)
        return outcome
    }

    /**
     * Surfaces the resolution to `AchievementEventHandler` for
     * `first_tictactoe_win` / `tictactoe_wins_*` / `tictactoe_losses_*`
     * progression. Draws never publish — no winner, no loser. Free-play
     * wins DO publish so achievements unlock regardless of whether
     * anyone bet.
     */
    private fun publishResolved(outcome: ResolveOutcome.Win, guildId: Long) {
        eventPublisher?.publishEvent(
            TicTacToeResolvedEvent(
                winnerDiscordId = outcome.winnerDiscordId,
                loserDiscordId = outcome.loserDiscordId,
                guildId = guildId,
                stake = outcome.stake,
                pot = outcome.pot,
            )
        )
    }

    private fun readStakeBounds(guildId: Long): Pair<Long, Long> {
        val min = configService.cfgLong(
            ConfigDto.Configurations.TICTACTOE_MIN_STAKE, guildId, default = MIN_STAKE, min = 0L
        )
        val max = configService.cfgLongMax(
            ConfigDto.Configurations.TICTACTOE_MAX_STAKE, guildId, default = MAX_STAKE, min = min
        )
        return min to max
    }

    companion object {
        /** Allow stake-free play: 0 is a valid minimum. */
        const val MIN_STAKE: Long = 0L
        const val MAX_STAKE: Long = 500L

        /** XP awarded to the winner regardless of stake. Daily-cap-respecting via XpAwardService. */
        const val WIN_XP: Long = 10L
    }
}

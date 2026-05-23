package database.service

import common.events.TicTacToeResolvedEvent
import database.dto.ConfigDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Head-to-head Tic-Tac-Toe wager between two users. TTT-specific
 * resolve branches live here; the shared wager primitives (start
 * preflight, accept debit, winner pay, draw refund) delegate to
 * [PvpWagerService] so the same arithmetic powers `/rps`, `/tictactoe`
 * (and the planned `/connect4`).
 *
 *   - [startMatch] / [acceptMatch] are thin pass-throughs to the
 *     shared service.
 *   - [resolveMatch] collapses the three terminal cases (3-in-a-row,
 *     forfeit walkover, timeout walkover) into a single `winner /
 *     no-winner` decision the caller hands in.
 *
 * Publishes [TicTacToeResolvedEvent] on every winner-bearing match —
 * free play included — so achievements unlock regardless of whether
 * anyone bet. Draws never publish.
 *
 * Has **no JDA dependency by design** (PR #551 lesson).
 */
@Service
@Transactional
class TicTacToeService @Autowired constructor(
    private val pvpWagerService: PvpWagerService,
    private val eventPublisher: ApplicationEventPublisher? = null,
) {

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

        /** Unreachable defensive case — one of the player rows vanished mid-resolve. */
        data object Unknown : ResolveOutcome
    }

    fun startMatch(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): PvpWagerService.StartOutcome {
        val (min, max) = pvpWagerService.readStakeBounds(
            guildId, ConfigDto.Configurations.TICTACTOE_MIN_STAKE, ConfigDto.Configurations.TICTACTOE_MAX_STAKE,
            defaultMin = MIN_STAKE, defaultMax = MAX_STAKE,
        )
        return pvpWagerService.preflightStart(
            initiatorDiscordId = initiatorDiscordId,
            opponentDiscordId = opponentDiscordId,
            guildId = guildId,
            stake = stake,
            minStake = min,
            maxStake = max,
        )
    }

    fun acceptMatch(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): PvpWagerService.AcceptOutcome =
        pvpWagerService.debitBoth(initiatorDiscordId, opponentDiscordId, guildId, stake)

    /**
     * Settle a match. [winnerDiscordId] = `null` means draw (board
     * full, no line) and both stakes refund. A non-null winner covers
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
        if (winnerDiscordId == null) {
            val refund = pvpWagerService.refundBoth(initiatorDiscordId, opponentDiscordId, stake, guildId)
            return ResolveOutcome.Draw(
                stake = stake,
                initiatorNewBalance = refund.initiatorNewBalance,
                opponentNewBalance = refund.opponentNewBalance,
            )
        }
        val loserDiscordId = when (winnerDiscordId) {
            initiatorDiscordId -> opponentDiscordId
            opponentDiscordId -> initiatorDiscordId
            else -> return ResolveOutcome.Unknown // caller passed an id that's not in this match
        }
        val pay = pvpWagerService.payWinner(
            winnerDiscordId = winnerDiscordId, loserDiscordId = loserDiscordId,
            stake = stake, guildId = guildId, xpReason = WIN_XP_REASON, xpAmount = WIN_XP,
        ) ?: return ResolveOutcome.Unknown
        val outcome = ResolveOutcome.Win(
            winnerDiscordId = winnerDiscordId,
            loserDiscordId = loserDiscordId,
            stake = stake,
            pot = pay.pot,
            winnerNewBalance = pay.winnerNewBalance,
            loserNewBalance = pay.loserNewBalance,
            lossTribute = pay.lossTribute,
            xpGranted = pay.xpGranted,
        )
        publishResolved(outcome, guildId)
        return outcome
    }

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

    companion object {
        /** Allow stake-free play: 0 is a valid minimum. */
        const val MIN_STAKE: Long = 0L
        const val MAX_STAKE: Long = 500L

        /** XP awarded to the winner regardless of stake. */
        const val WIN_XP: Long = 10L
        private const val WIN_XP_REASON = "tictactoe:win"
    }
}

package database.boardgame

import database.dto.ConfigDto
import database.service.PvpWagerService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Transactional

/**
 * Game-agnostic base for the wager service of turn-based visible-board
 * PvP games (`/tictactoe`, `/connect4`, …). Owns the bits that don't
 * depend on the game's board / engine / event class:
 *
 *  - [ResolveOutcome] sealed type (Win / Draw / Unknown) — every
 *    board-game collapses its terminal cases (3-in-a-row, 4-in-a-row,
 *    forfeit walkover, timeout walkover) into a single `winner /
 *    no-winner` decision the caller hands in
 *  - [startMatch] / [acceptMatch] thin pass-throughs to [PvpWagerService]
 *    (handles the per-guild stake bounds lookup and the wager debit)
 *  - [resolveMatch] routing — null winner ⇒ refund draw, non-null
 *    winner ⇒ pay + publish event
 *  - [publishResolved] event-fan-out plumbing (calls subclass's
 *    [makeEvent] factory)
 *
 * Subclasses provide the per-game knobs ([minStakeKey], [maxStakeKey],
 * [xpReason], [makeEvent]) and inherit ~120 lines of behaviour.
 *
 * Has **no JDA dependency by design** (PR #551 lesson) — every
 * subclass is reachable from a `Command` bean and must stay free of
 * JDA in the container.
 */
@Transactional
abstract class TurnBasedBoardWagerService<TEvent>(
    protected val pvpWagerService: PvpWagerService,
    private val eventPublisher: ApplicationEventPublisher?,
) {

    /** Shared resolve outcome for every turn-based board wager game. */
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

        /** Unreachable defensive case — one of the player rows vanished mid-resolve, or the caller passed an id not in the match. */
        data object Unknown : ResolveOutcome
    }

    /** Per-game stake-bounds config keys + defaults + XP-grant tuning. */
    protected abstract val minStakeKey: ConfigDto.Configurations
    protected abstract val maxStakeKey: ConfigDto.Configurations
    protected open val defaultMinStake: Long = DEFAULT_MIN_STAKE
    protected open val defaultMaxStake: Long = DEFAULT_MAX_STAKE
    protected open val winXp: Long = DEFAULT_WIN_XP

    /** Per-game daily-cap-respecting tag passed to [XpAwardService.award], e.g. `"tictactoe:win"`. */
    protected abstract val xpReason: String

    /** Per-game achievement event factory — invoked on every winner-bearing resolve, including free play. */
    protected abstract fun makeEvent(
        winnerDiscordId: Long,
        loserDiscordId: Long,
        guildId: Long,
        stake: Long,
        pot: Long,
    ): TEvent

    fun startMatch(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): PvpWagerService.StartOutcome {
        val (min, max) = pvpWagerService.readStakeBounds(
            guildId, minStakeKey, maxStakeKey,
            defaultMin = defaultMinStake, defaultMax = defaultMaxStake,
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
     * Settle a match. [winnerDiscordId] = `null` means draw (no line,
     * board full or both passed) and both stakes refund. A non-null
     * winner covers three real cases that all collapse to the same
     * wager arithmetic:
     *   1. winner completed a winning line
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
            stake = stake, guildId = guildId, xpReason = xpReason, xpAmount = winXp,
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
            makeEvent(
                winnerDiscordId = outcome.winnerDiscordId,
                loserDiscordId = outcome.loserDiscordId,
                guildId = guildId,
                stake = outcome.stake,
                pot = outcome.pot,
            ),
        )
    }

    companion object {
        /** Allow stake-free play: 0 is a valid minimum across the board. */
        const val DEFAULT_MIN_STAKE: Long = 0L
        const val DEFAULT_MAX_STAKE: Long = 500L

        /** XP awarded to the winner regardless of stake. Daily-cap-respecting via XpAwardService. */
        const val DEFAULT_WIN_XP: Long = 10L
    }
}

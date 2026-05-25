package database.tictactoe

import common.pvp.tictactoe.TicTacToeEngine
import database.boardgame.TurnBasedBoardSessionRegistry
import database.configuration.RegistryScheduler
import database.pvp.PvpSessionRegistry
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService

/**
 * In-memory book of live `/tictactoe` matches. The state machine
 * (PENDING → LIVE), Caffeine backing store, and pending / per-move
 * shot-clock plumbing live in [TurnBasedBoardSessionRegistry]; this
 * class adds the TTT-specific [Session] subclass with board / turn
 * state and the [applyMove] method that drives [TicTacToeEngine].
 */
@Component
class TicTacToeSessionRegistry(
    pendingTtl: Duration = DEFAULT_PENDING_TTL,
    moveTtl: Duration = DEFAULT_MOVE_TTL,
    scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    maximumSessions: Long = MAX_SESSIONS,
    maxLifetime: Duration = MAX_LIFETIME,
) : TurnBasedBoardSessionRegistry<TicTacToeSessionRegistry.Session>(
    pendingTtl, moveTtl, scheduler, maximumSessions, maxLifetime,
) {

    class Session(
        id: Long,
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant,
        var board: TicTacToeEngine.Board = TicTacToeEngine.empty(),
        /** Whose turn it is; only meaningful in LIVE. Initiator starts as X. */
        var currentTurn: TicTacToeEngine.Mark = TicTacToeEngine.Mark.X,
        /** Set on terminal — winning line for embed highlighting, null on draw. */
        var winningLine: List<Int>? = null,
        /** Set on terminal — Mark of the winner, null on draw / no-resolution. */
        var winner: TicTacToeEngine.Mark? = null,
    ) : TurnBasedBoardSessionRegistry.Session(id, guildId, initiatorDiscordId, opponentDiscordId, stake, createdAt) {

        /** Discord id of the player whose turn it currently is. */
        fun currentActorDiscordId(): Long = when (currentTurn) {
            TicTacToeEngine.Mark.X -> initiatorDiscordId
            TicTacToeEngine.Mark.O -> opponentDiscordId
        }

        /** Mark assigned to [discordId]; null if [discordId] isn't in this match. */
        fun markFor(discordId: Long): TicTacToeEngine.Mark? = when (discordId) {
            initiatorDiscordId -> TicTacToeEngine.Mark.X
            opponentDiscordId -> TicTacToeEngine.Mark.O
            else -> null
        }
    }

    override fun newSession(
        id: Long,
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant,
    ): Session = Session(id, guildId, initiatorDiscordId, opponentDiscordId, stake, createdAt)

    /**
     * Apply a TTT move to the session. Validates that the caller is
     * the current actor and the session is LIVE; delegates the board
     * math to [TicTacToeEngine.applyMove]. On a terminal result the
     * session stays in place — the caller is expected to call
     * [consumeForResolution] to atomically drain it.
     *
     * Returns:
     *  - the engine's [TicTacToeEngine.MoveResult] when the call lands
     *  - `null` when the session no longer exists, the caller isn't a
     *    participant, the session isn't LIVE, or it isn't the caller's
     *    turn — i.e. anything the button shouldn't treat as a move
     */
    fun applyMove(
        id: Long,
        discordId: Long,
        cell: Int,
        onMoveTimeout: (Session) -> Unit = {},
    ): TicTacToeEngine.MoveResult? {
        val session = get(id) ?: return null
        return synchronized(session) {
            if (session.state != PvpSessionRegistry.Session.State.LIVE) return@synchronized null
            val mark = session.markFor(discordId) ?: return@synchronized null
            if (mark != session.currentTurn) return@synchronized null
            val result = TicTacToeEngine.applyMove(session.board, cell, mark)
            when (result) {
                is TicTacToeEngine.MoveResult.Continued -> {
                    session.board = result.board
                    session.currentTurn = other(mark)
                    session.moveNumber += 1
                    armMoveClock(session, onMoveTimeout)
                }
                is TicTacToeEngine.MoveResult.Win -> {
                    session.board = result.board
                    session.winningLine = result.winningLine
                    session.winner = result.winner
                    session.moveNumber += 1
                    cancelMoveClock(session.id)
                }
                is TicTacToeEngine.MoveResult.Draw -> {
                    session.board = result.board
                    session.moveNumber += 1
                    cancelMoveClock(session.id)
                }
                TicTacToeEngine.MoveResult.IllegalCell,
                TicTacToeEngine.MoveResult.Occupied -> { /* board unchanged */ }
            }
            result
        }
    }

    private fun other(mark: TicTacToeEngine.Mark): TicTacToeEngine.Mark = when (mark) {
        TicTacToeEngine.Mark.X -> TicTacToeEngine.Mark.O
        TicTacToeEngine.Mark.O -> TicTacToeEngine.Mark.X
    }
}

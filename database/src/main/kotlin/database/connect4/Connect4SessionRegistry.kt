package database.connect4

import common.connect4.Connect4Engine
import database.boardgame.TurnBasedBoardSessionRegistry
import database.configuration.RegistryScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService

/**
 * In-memory book of live `/connect4` matches. The state machine
 * (PENDING → LIVE), Caffeine backing store, and pending / per-move
 * shot-clock plumbing live in [TurnBasedBoardSessionRegistry]; this
 * class adds the C4-specific [Session] subclass with board / turn
 * state and the [applyMove] method that drives [Connect4Engine].
 *
 * Initiator plays RED (drops first); opponent plays YELLOW.
 */
@Component
class Connect4SessionRegistry(
    pendingTtl: Duration = DEFAULT_PENDING_TTL,
    moveTtl: Duration = DEFAULT_MOVE_TTL,
    scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    maximumSessions: Long = MAX_SESSIONS,
    maxLifetime: Duration = MAX_LIFETIME,
) : TurnBasedBoardSessionRegistry<Connect4SessionRegistry.Session>(
    pendingTtl, moveTtl, scheduler, maximumSessions, maxLifetime,
) {

    class Session(
        id: Long,
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant,
        var board: Connect4Engine.Board = Connect4Engine.empty(),
        /** Whose turn it is; only meaningful in LIVE. Initiator starts as RED. */
        var currentTurn: Connect4Engine.Mark = Connect4Engine.Mark.RED,
        /** Set on terminal — winning line for embed highlighting, null on draw. */
        var winningLine: List<Int>? = null,
        /** Set on terminal — Mark of the winner, null on draw / no-resolution. */
        var winner: Connect4Engine.Mark? = null,
        /** Set after every move — the row the disc landed in. Lets the renderer
         *  highlight the just-placed cell on Continued or Win re-renders. */
        var lastDroppedRow: Int? = null,
        var lastDroppedCol: Int? = null,
    ) : TurnBasedBoardSessionRegistry.Session(id, guildId, initiatorDiscordId, opponentDiscordId, stake, createdAt) {

        /** Discord id of the player whose turn it currently is. */
        fun currentActorDiscordId(): Long = when (currentTurn) {
            Connect4Engine.Mark.RED -> initiatorDiscordId
            Connect4Engine.Mark.YELLOW -> opponentDiscordId
        }

        /** Mark assigned to [discordId]; null if [discordId] isn't in this match. */
        fun markFor(discordId: Long): Connect4Engine.Mark? = when (discordId) {
            initiatorDiscordId -> Connect4Engine.Mark.RED
            opponentDiscordId -> Connect4Engine.Mark.YELLOW
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
     * Apply a C4 move (a column drop) to the session. Validates that
     * the caller is the current actor and the session is LIVE;
     * delegates the board math (including gravity) to
     * [Connect4Engine.applyMove]. On a terminal result the session
     * stays in place — the caller is expected to call
     * [consumeForResolution] to atomically drain it.
     *
     * Returns:
     *  - the engine's [Connect4Engine.MoveResult] when the call lands
     *  - `null` when the session no longer exists, the caller isn't a
     *    participant, the session isn't LIVE, or it isn't the caller's
     *    turn
     */
    fun applyMove(
        id: Long,
        discordId: Long,
        column: Int,
        onMoveTimeout: (Session) -> Unit = {},
    ): Connect4Engine.MoveResult? {
        val session = get(id) ?: return null
        return synchronized(session) {
            if (session.state != TurnBasedBoardSessionRegistry.Session.State.LIVE) return@synchronized null
            val mark = session.markFor(discordId) ?: return@synchronized null
            if (mark != session.currentTurn) return@synchronized null
            val result = Connect4Engine.applyMove(session.board, column, mark)
            when (result) {
                is Connect4Engine.MoveResult.Continued -> {
                    session.board = result.board
                    session.currentTurn = other(mark)
                    session.lastDroppedRow = result.droppedRow
                    session.lastDroppedCol = column
                    session.moveNumber += 1
                    armMoveClock(session, onMoveTimeout)
                }
                is Connect4Engine.MoveResult.Win -> {
                    session.board = result.board
                    session.winningLine = result.winningLine
                    session.winner = result.winner
                    session.lastDroppedRow = result.droppedRow
                    session.lastDroppedCol = column
                    session.moveNumber += 1
                    cancelMoveClock(session.id)
                }
                is Connect4Engine.MoveResult.Draw -> {
                    session.board = result.board
                    session.moveNumber += 1
                    cancelMoveClock(session.id)
                }
                Connect4Engine.MoveResult.InvalidColumn,
                Connect4Engine.MoveResult.ColumnFull -> { /* board unchanged */ }
            }
            result
        }
    }

    private fun other(mark: Connect4Engine.Mark): Connect4Engine.Mark = when (mark) {
        Connect4Engine.Mark.RED -> Connect4Engine.Mark.YELLOW
        Connect4Engine.Mark.YELLOW -> Connect4Engine.Mark.RED
    }
}

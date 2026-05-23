package database.tictactoe

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import common.tictactoe.TicTacToeEngine
import database.configuration.RegistryScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory book of live `/tictactoe` matches. Two stages:
 *   - PENDING — `/tictactoe` posted; opponent hasn't yet hit Accept.
 *     Stakes are NOT yet debited from anyone.
 *   - LIVE — opponent accepted; both stakes locked. Players alternate
 *     placing marks on the board via [applyMove]; the initiator (X)
 *     moves first by convention. When a move completes a line or fills
 *     the board, the caller drains the session (via
 *     [consumeForResolution]) and the service resolves the wager.
 *
 * **Storage**: backed by a Caffeine [Cache] (via `asMap()` for the
 * familiar `ConcurrentMap` ops) so we get a hard upper bound on
 * concurrent sessions ([MAX_SESSIONS]) and a TTL backstop
 * ([MAX_LIFETIME]) on entries that the scheduler somehow misses.
 * Matches the convention used by [database.rps.RpsSessionRegistry] —
 * belt-and-suspenders memory protection beyond the scheduler-driven
 * phase callbacks.
 *
 * **Shot clock**: re-armed after every successful move via
 * [applyMove]'s internal `armMoveClock`. If the current actor doesn't
 * place a mark within [moveTtl], the registry fires
 * `onMoveTimeout(session)` — the caller (button) treats the timeout
 * as a forfeit by the current actor. Pending-phase timeouts work
 * identically to RPS.
 */
@Component
class TicTacToeSessionRegistry(
    val pendingTtl: Duration = DEFAULT_PENDING_TTL,
    val moveTtl: Duration = DEFAULT_MOVE_TTL,
    private val scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    maximumSessions: Long = MAX_SESSIONS,
    maxLifetime: Duration = MAX_LIFETIME,
) {

    data class Session(
        val id: Long,
        val guildId: Long,
        val initiatorDiscordId: Long,
        val opponentDiscordId: Long,
        val stake: Long,
        val createdAt: Instant,
        var board: TicTacToeEngine.Board = TicTacToeEngine.empty(),
        /** Whose turn it is; only meaningful in LIVE. Initiator starts as X. */
        var currentTurn: TicTacToeEngine.Mark = TicTacToeEngine.Mark.X,
        /** Move number, starts at 0; used to key shot-clock stale-fire detection. */
        var moveNumber: Int = 0,
        var state: State = State.PENDING,
        /** Set on terminal — winning line for embed highlighting, null on draw. */
        var winningLine: List<Int>? = null,
        /** Set on terminal — Mark of the winner, null on draw / no-resolution. */
        var winner: TicTacToeEngine.Mark? = null,
    ) {
        enum class State { PENDING, LIVE }

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

    private val cache: Cache<Long, Session> = Caffeine.newBuilder()
        .expireAfterWrite(maxLifetime)
        .maximumSize(maximumSessions)
        .build()
    private val sessions: ConcurrentMap<Long, Session> = cache.asMap()
    private val seq = AtomicLong()

    /** Per-session pending move-clock task. Keyed by sessionId. */
    private val moveClocks: ConcurrentMap<Long, ScheduledFuture<*>> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Register a fresh PENDING offer. Schedules a timeout that fires
     * only if the offer is still pending — once accepted (LIVE) the
     * pending timer is a no-op and the per-move timer takes over.
     */
    fun register(
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant = Instant.now(),
        onPendingTimeout: (Session) -> Unit = {},
    ): Session {
        val id = seq.incrementAndGet()
        val session = Session(
            id = id,
            guildId = guildId,
            initiatorDiscordId = initiatorDiscordId,
            opponentDiscordId = opponentDiscordId,
            stake = stake,
            createdAt = createdAt,
        )
        sessions[id] = session
        scheduler.schedule({
            val current = sessions[id]
            if (current != null && current.state == Session.State.PENDING) {
                val expired = sessions.remove(id)
                if (expired != null) runCatching { onPendingTimeout(expired) }
            }
        }, pendingTtl.toMillis(), TimeUnit.MILLISECONDS)
        return session
    }

    /**
     * Transition PENDING → LIVE on the opponent's Accept. Returns the
     * session or null if it already expired or was cancelled. Arms the
     * first per-move timeout; the caller passes [onMoveTimeout] for
     * the auto-forfeit path.
     */
    fun accept(id: Long, onMoveTimeout: (Session) -> Unit = {}): Session? {
        val session = sessions[id] ?: return null
        synchronized(session) {
            if (session.state != Session.State.PENDING) return null
            session.state = Session.State.LIVE
        }
        armMoveClock(session, onMoveTimeout)
        return session
    }

    /** Atomic remove for decline (opponent rejects PENDING offer). */
    fun decline(id: Long): Session? {
        val session = sessions[id] ?: return null
        synchronized(session) {
            if (session.state != Session.State.PENDING) return null
            return sessions.remove(id)
        }
    }

    /**
     * Apply a move to the session. Validates that the caller is the
     * current actor and the session is LIVE; delegates the board math
     * to [TicTacToeEngine.applyMove]. On a terminal result (Win / Draw)
     * the session is left in place — the caller is expected to call
     * [consumeForResolution] to atomically drain it.
     *
     * Re-arms the per-move shot clock on a Continued result so the
     * other player now has [moveTtl] to respond.
     *
     * Returns:
     *  - the engine's [TicTacToeEngine.MoveResult] when the call lands
     *    (note: Continued / Win / Draw are all "landed")
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
        val session = sessions[id] ?: return null
        return synchronized(session) {
            if (session.state != Session.State.LIVE) return@synchronized null
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

    /**
     * Atomic remove for resolution — call once a Win / Draw landed or
     * a forfeit / timeout is being settled. Cancels any pending move
     * clock so it can't double-fire after the session is drained.
     */
    fun consumeForResolution(id: Long): Session? {
        val removed = sessions.remove(id) ?: return null
        cancelMoveClock(id)
        return removed
    }

    /**
     * Atomic remove for forfeit (a player gives up mid-game). Returns
     * the session or null if already gone. Cancels the move clock.
     */
    fun forfeit(id: Long): Session? = consumeForResolution(id)

    fun get(id: Long): Session? = sessions[id]

    private fun armMoveClock(session: Session, onMoveTimeout: (Session) -> Unit) {
        cancelMoveClock(session.id)
        val expectedMove = session.moveNumber
        val future = scheduler.schedule({
            val current = sessions[session.id] ?: return@schedule
            synchronized(current) {
                // Stale fire: someone already moved on (move counter
                // advanced) or the session left LIVE — drop it.
                if (current.state != Session.State.LIVE) return@synchronized
                if (current.moveNumber != expectedMove) return@synchronized
                val expired = sessions.remove(session.id)
                if (expired != null) runCatching { onMoveTimeout(expired) }
            }
        }, moveTtl.toMillis(), TimeUnit.MILLISECONDS)
        moveClocks[session.id] = future
    }

    private fun cancelMoveClock(id: Long) {
        val task = moveClocks.remove(id) ?: return
        task.cancel(false)
    }

    private fun other(mark: TicTacToeEngine.Mark): TicTacToeEngine.Mark = when (mark) {
        TicTacToeEngine.Mark.X -> TicTacToeEngine.Mark.O
        TicTacToeEngine.Mark.O -> TicTacToeEngine.Mark.X
    }

    companion object {
        val DEFAULT_PENDING_TTL: Duration = Duration.ofMinutes(3)
        val DEFAULT_MOVE_TTL: Duration = Duration.ofMinutes(1)

        /**
         * Hard upper bound on concurrent in-flight sessions. Same
         * order of magnitude as [database.rps.RpsSessionRegistry] —
         * generous enough that a real PvP burst doesn't trip it, low
         * enough that a malformed `/tictactoe` flood can't OOM the
         * heap. Caffeine evicts LRU above the cap.
         */
        const val MAX_SESSIONS: Long = 10_000L

        /**
         * Backstop lifetime per session: pending TTL (3m) + up to 9
         * moves × move TTL (1m each) + slop = 12 minutes. Caffeine
         * evicts entries past this even if a scheduled task somehow
         * didn't run.
         */
        val MAX_LIFETIME: Duration = Duration.ofMinutes(12)
    }
}

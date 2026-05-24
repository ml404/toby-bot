package database.boardgame

import database.configuration.RegistryScheduler
import database.pvp.PvpSessionRegistry
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Turn-based visible-board specialisation of [PvpSessionRegistry] for
 * `/tictactoe`, `/connect4`, future `/checkers` etc. The base owns the
 * Caffeine cache, the PENDING → LIVE state machine, the pending-phase
 * timeout, and the indexers; this class adds the per-move shot clock
 * that turn-based games need.
 *
 * The subclass owns:
 *  - The [Session] subclass — adds game-specific fields (board,
 *    currentTurn, winner, winningLine) and convenience accessors
 *    (`currentActorDiscordId`, `markFor`)
 *  - The [newSession] factory the base calls from
 *    [PvpSessionRegistry.register]
 *  - The game-specific `applyMove(id, discordId, moveParam, ...)`
 *    method that locks the session, validates state + turn, drives
 *    the engine, mutates the session's game-specific fields, and
 *    calls [armMoveClock] / [cancelMoveClock] to manage the shot
 *    clock
 *
 * The base intentionally does NOT define `applyMove` — the move
 * parameter (cell index vs column index) and the engine `MoveResult`
 * shapes differ enough between games that forcing them behind a
 * generic adapter would hurt more than help. Two ~40-line per-game
 * `applyMove` methods are cheaper than a generic-heavy base.
 */
abstract class TurnBasedBoardSessionRegistry<TSession : TurnBasedBoardSessionRegistry.Session>(
    pendingTtl: Duration = DEFAULT_PENDING_TTL,
    val moveTtl: Duration = DEFAULT_MOVE_TTL,
    scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    maximumSessions: Long = PvpSessionRegistry.MAX_SESSIONS,
    maxLifetime: Duration = PvpSessionRegistry.MAX_LIFETIME,
) : PvpSessionRegistry<TSession>(pendingTtl, scheduler, maximumSessions, maxLifetime) {

    /**
     * Turn-based session — extends the game-agnostic base with the
     * shot-clock key. [moveNumber] is incremented by the subclass's
     * `applyMove` after a successful move so a stale timer fire can
     * detect "the actor already moved on".
     */
    abstract class Session(
        id: Long,
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant,
        var moveNumber: Int = 0,
        state: State = State.PENDING,
    ) : PvpSessionRegistry.Session(id, guildId, initiatorDiscordId, opponentDiscordId, stake, createdAt, state)

    /** Per-session pending shot-clock task. Keyed by sessionId. */
    private val moveClocks: ConcurrentMap<Long, ScheduledFuture<*>> = ConcurrentHashMap()

    /**
     * Transition PENDING → LIVE on the opponent's Accept. Returns the
     * session or null if it already expired or was cancelled. Arms
     * the first per-move timeout; the caller passes [onMoveTimeout]
     * for the auto-forfeit path.
     */
    fun accept(id: Long, onMoveTimeout: (TSession) -> Unit = {}): TSession? {
        val session = transitionPendingToLive(id) ?: return null
        armMoveClock(session, onMoveTimeout)
        return session
    }

    /**
     * Cancels the per-move shot clock in addition to removing the
     * session. Forfeit / Win / Draw and stale-fire all flow through
     * here so the clock-cancel contract holds in one place.
     */
    override fun consumeForResolution(id: Long): TSession? {
        val removed = super.consumeForResolution(id) ?: return null
        cancelMoveClock(id)
        return removed
    }

    /**
     * Cancel any pending move clock for [session] and arm a fresh one
     * keyed on the session's current [Session.moveNumber]. If the
     * scheduled task fires after another move has already advanced
     * the counter, the stale-fire check inside the task is a no-op.
     *
     * Called by the subclass's `applyMove` after a Continued result —
     * the next actor now has [moveTtl] to respond before they're
     * auto-forfeited.
     */
    protected fun armMoveClock(session: TSession, onMoveTimeout: (TSession) -> Unit) {
        cancelMoveClock(session.id)
        val expectedMove = session.moveNumber
        val future = scheduler.schedule({
            val current = sessions[session.id] ?: return@schedule
            synchronized(current) {
                // Stale fire: someone already moved on (counter advanced)
                // or the session left LIVE — drop it.
                if (current.state != PvpSessionRegistry.Session.State.LIVE) return@synchronized
                if (current.moveNumber != expectedMove) return@synchronized
                val expired = sessions.remove(session.id)
                if (expired != null) runCatching { onMoveTimeout(expired) }
            }
        }, moveTtl.toMillis(), TimeUnit.MILLISECONDS)
        moveClocks[session.id] = future
    }

    /**
     * Cancel any pending move clock for [id]. Idempotent. Called by
     * the subclass's `applyMove` after a Win / Draw result and by
     * [consumeForResolution].
     */
    protected fun cancelMoveClock(id: Long) {
        val task = moveClocks.remove(id) ?: return
        task.cancel(false)
    }

    companion object {
        val DEFAULT_PENDING_TTL: Duration = Duration.ofMinutes(3)
        val DEFAULT_MOVE_TTL: Duration = Duration.ofMinutes(1)
    }
}

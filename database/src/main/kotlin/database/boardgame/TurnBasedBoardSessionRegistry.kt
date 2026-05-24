package database.boardgame

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import database.configuration.RegistryScheduler
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Game-agnostic base for turn-based visible-board PvP wager registries
 * (`/tictactoe`, `/connect4`, future `/checkers` etc.). Owns the bits
 * that don't depend on the game's board / mark / engine:
 *
 *  - Caffeine-backed `Cache` with a hard upper bound on concurrent
 *    sessions ([MAX_SESSIONS]) and a TTL backstop ([MAX_LIFETIME]) so
 *    a malformed-flood or scheduler-dropped task can't leak entries
 *  - PENDING → LIVE state machine with atomic transitions
 *  - Pending-phase timeout scheduled at register-time
 *  - Per-move shot-clock plumbing exposed to subclasses via the
 *    protected [armMoveClock] / [cancelMoveClock] pair — the subclass
 *    calls into them from its `applyMove` after a Continued / Win /
 *    Draw result
 *
 * Self-typed on [TSession] so the registry methods (`get`, `decline`,
 * `forfeit`, `consumeForResolution`) return the concrete subclass
 * without callers having to cast.
 *
 * The subclass owns:
 *  - The [Session] subclass — adds game-specific fields (board,
 *    currentTurn, winner, winningLine) and convenience accessors
 *    (`currentActorDiscordId`, `markFor`)
 *  - The [newSession] factory the base calls from [register]
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
 *
 * Lost-on-restart is acceptable: mid-flight matches are short-lived
 * and the worst case (a session evaporates mid-move) just refunds
 * anything already debited.
 */
abstract class TurnBasedBoardSessionRegistry<TSession : TurnBasedBoardSessionRegistry.Session>(
    val pendingTtl: Duration = DEFAULT_PENDING_TTL,
    val moveTtl: Duration = DEFAULT_MOVE_TTL,
    private val scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    maximumSessions: Long = MAX_SESSIONS,
    maxLifetime: Duration = MAX_LIFETIME,
) {

    /**
     * Game-agnostic session fields. The per-game subclass extends this
     * with the board, current turn, winner, and any other state the
     * engine needs to mutate. [moveNumber] is the shot-clock key — the
     * subclass increments it after a successful move so a stale timer
     * fire can detect "the actor already moved on".
     */
    abstract class Session(
        val id: Long,
        val guildId: Long,
        val initiatorDiscordId: Long,
        val opponentDiscordId: Long,
        val stake: Long,
        val createdAt: Instant,
        var moveNumber: Int = 0,
        var state: State = State.PENDING,
    ) {
        enum class State { PENDING, LIVE }
    }

    /**
     * Subclass-provided factory used by [register]. Receives the
     * session-id the base allocated; the subclass builds its concrete
     * [Session] subclass with that id + a fresh board and starting
     * turn.
     */
    protected abstract fun newSession(
        id: Long,
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant,
    ): TSession

    /**
     * Caffeine cache + `asMap()` view. Every read/write below uses
     * [sessions] (the ConcurrentMap view) for atomic primitives;
     * [cache] is kept only so the `expireAfterWrite` / `maximumSize`
     * configuration stays in scope and the GC root chain is explicit.
     */
    private val cache: Cache<Long, TSession> = Caffeine.newBuilder()
        .expireAfterWrite(maxLifetime)
        .maximumSize(maximumSessions)
        .build()
    private val sessions: ConcurrentMap<Long, TSession> = cache.asMap()
    private val seq = AtomicLong()

    /** Per-session pending shot-clock task. Keyed by sessionId. */
    private val moveClocks: ConcurrentMap<Long, ScheduledFuture<*>> = ConcurrentHashMap()

    /**
     * Register a fresh PENDING offer. Schedules a pending-phase
     * timeout that fires only if the offer is still PENDING when it
     * elapses — once accepted (LIVE) the pending timer is a no-op and
     * the per-move timer takes over.
     */
    fun register(
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant = Instant.now(),
        onPendingTimeout: (TSession) -> Unit = {},
    ): TSession {
        val id = seq.incrementAndGet()
        val session = newSession(id, guildId, initiatorDiscordId, opponentDiscordId, stake, createdAt)
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
     * session or null if it already expired or was cancelled. Arms
     * the first per-move timeout; the caller passes [onMoveTimeout]
     * for the auto-forfeit path.
     */
    fun accept(id: Long, onMoveTimeout: (TSession) -> Unit = {}): TSession? {
        val session = sessions[id] ?: return null
        synchronized(session) {
            if (session.state != Session.State.PENDING) return null
            session.state = Session.State.LIVE
        }
        armMoveClock(session, onMoveTimeout)
        return session
    }

    /** Atomic remove for decline (opponent rejects PENDING offer). */
    fun decline(id: Long): TSession? {
        val session = sessions[id] ?: return null
        synchronized(session) {
            if (session.state != Session.State.PENDING) return null
            return sessions.remove(id)
        }
    }

    /**
     * Atomic remove for resolution — call once a Win / Draw landed or
     * a forfeit / timeout is being settled. Cancels any pending move
     * clock so it can't double-fire after the session is drained.
     */
    fun consumeForResolution(id: Long): TSession? {
        val removed = sessions.remove(id) ?: return null
        cancelMoveClock(id)
        return removed
    }

    /**
     * Atomic remove for forfeit (a player gives up mid-game). Returns
     * the session or null if already gone. Cancels the move clock.
     */
    fun forfeit(id: Long): TSession? = consumeForResolution(id)

    fun get(id: Long): TSession? = sessions[id]

    /**
     * List PENDING offers where [discordId] is the opponent — the web
     * inbox feed. Scans the Caffeine map (O(n), capped at
     * [MAX_SESSIONS]); fine because n is bounded and the alternative
     * (a parallel index) would have to be kept in sync through every
     * state transition. Same approach as the per-game RPS / Duel
     * registries.
     */
    fun pendingForOpponent(discordId: Long, guildId: Long): List<TSession> =
        sessions.values.filter {
            it.state == Session.State.PENDING &&
                it.guildId == guildId &&
                it.opponentDiscordId == discordId
        }

    /** Mirror of [pendingForOpponent] for outgoing offers (web outbox). */
    fun pendingForInitiator(discordId: Long, guildId: Long): List<TSession> =
        sessions.values.filter {
            it.state == Session.State.PENDING &&
                it.guildId == guildId &&
                it.initiatorDiscordId == discordId
        }

    /**
     * List LIVE sessions where [discordId] is one of the two participants
     * — the web active-game feed. Used by the polling endpoint that
     * keeps the board in sync between Discord and web surfaces.
     */
    fun liveFor(discordId: Long, guildId: Long): List<TSession> =
        sessions.values.filter {
            it.state == Session.State.LIVE &&
                it.guildId == guildId &&
                (it.initiatorDiscordId == discordId || it.opponentDiscordId == discordId)
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
                if (current.state != Session.State.LIVE) return@synchronized
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
     * [consumeForResolution] / [forfeit].
     */
    protected fun cancelMoveClock(id: Long) {
        val task = moveClocks.remove(id) ?: return
        task.cancel(false)
    }

    companion object {
        val DEFAULT_PENDING_TTL: Duration = Duration.ofMinutes(3)
        val DEFAULT_MOVE_TTL: Duration = Duration.ofMinutes(1)

        /**
         * Hard upper bound on concurrent in-flight sessions. Generous
         * enough that a real PvP burst (a server runs a tournament)
         * doesn't trip it, low enough that a malformed-flood can't
         * OOM the heap. Caffeine evicts LRU above the cap.
         */
        const val MAX_SESSIONS: Long = 10_000L

        /**
         * Backstop lifetime per session: covers pending TTL + the
         * longest reasonable game's worth of per-move TTLs + slop.
         * Caffeine evicts entries past this even if a scheduled task
         * somehow didn't run.
         */
        val MAX_LIFETIME: Duration = Duration.ofMinutes(12)
    }
}

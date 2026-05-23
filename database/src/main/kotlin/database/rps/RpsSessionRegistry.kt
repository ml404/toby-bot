package database.rps

import database.configuration.RegistryScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory book of live `/rps` matches. Mirrors the shape of
 * [database.duel.PendingDuelRegistry] — same `ConcurrentHashMap +
 * AtomicLong` skeleton, same TTL-expiry-with-callback, same atomic
 * `consumeFor…` removes for race safety between picks, forfeit, and
 * timeout.
 *
 * Two stages live here:
 *   - PENDING — `/rps` posted; opponent hasn't yet hit Accept. Stakes
 *     are NOT yet debited from anyone.
 *   - LIVE — opponent accepted; both stakes locked. Each side privately
 *     submits a [RpsEngine.Choice] via [recordPick]; once both have, the
 *     caller drains the session (via [consumeForResolution]) and
 *     resolves the wager.
 *
 * Lost-on-restart is acceptable: mid-flight matches are short-lived and
 * the worst case (a session evaporates mid-pick) just refunds anything
 * already debited — better than carrying half-state across deploys.
 */
@Component
class RpsSessionRegistry(
    val pendingTtl: Duration = DEFAULT_PENDING_TTL,
    val pickTtl: Duration = DEFAULT_PICK_TTL,
    private val scheduler: ScheduledExecutorService = RegistryScheduler.instance,
) {

    data class Session(
        val id: Long,
        val guildId: Long,
        val initiatorDiscordId: Long,
        val opponentDiscordId: Long,
        val stake: Long,
        val createdAt: Instant,
        /** Picks indexed by discord id; both populated → ready to resolve. */
        val picks: MutableMap<Long, RpsEngine.Choice> = mutableMapOf(),
        var state: State = State.PENDING,
    ) {
        enum class State { PENDING, LIVE }
        val bothPicked: Boolean get() = picks.size == 2
    }

    private val sessions = ConcurrentHashMap<Long, Session>()
    private val seq = AtomicLong()

    /**
     * Register a fresh PENDING offer. Schedules a timeout that fires
     * only if the offer is still pending — once accepted (LIVE) the
     * pending timer is a no-op and the pick timer takes over.
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
            // Only fire if still pending — once accepted, the session
            // transitions to LIVE and the pick timer takes over below.
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
     * session or null if it already expired or was cancelled. Schedules
     * a pick-phase timeout. The atomic compare-and-set on `state`
     * means concurrent Accept/Decline/timeout collapses to one winner.
     */
    fun accept(id: Long, onPickTimeout: (Session) -> Unit = {}): Session? {
        val session = sessions[id] ?: return null
        synchronized(session) {
            if (session.state != Session.State.PENDING) return null
            session.state = Session.State.LIVE
        }
        scheduler.schedule({
            // Only fire if no resolution has consumed the session yet.
            val current = sessions[id]
            if (current != null && current.state == Session.State.LIVE && !current.bothPicked) {
                val expired = sessions.remove(id)
                if (expired != null) runCatching { onPickTimeout(expired) }
            }
        }, pickTtl.toMillis(), TimeUnit.MILLISECONDS)
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
     * Records a player's pick. Returns the updated session (always
     * non-null on success) so callers can read [Session.bothPicked]
     * and decide whether to call [consumeForResolution]. Returns null
     * if the session no longer exists or the player isn't in it.
     */
    fun recordPick(id: Long, discordId: Long, choice: RpsEngine.Choice): Session? {
        val session = sessions[id] ?: return null
        synchronized(session) {
            if (session.state != Session.State.LIVE) return null
            if (discordId != session.initiatorDiscordId && discordId != session.opponentDiscordId) return null
            session.picks[discordId] = choice
            return session
        }
    }

    /**
     * Atomic remove for resolution — call only once [Session.bothPicked]
     * is true. Returns the session or null if another thread already
     * resolved it.
     */
    fun consumeForResolution(id: Long): Session? = sessions.remove(id)

    /**
     * Atomic remove for forfeit (a player gives up mid-pick or the
     * Accept-side initiator wants to back out). Returns the session
     * or null if already gone.
     */
    fun forfeit(id: Long): Session? = sessions.remove(id)

    fun get(id: Long): Session? = sessions[id]

    companion object {
        val DEFAULT_PENDING_TTL: Duration = Duration.ofMinutes(3)
        val DEFAULT_PICK_TTL: Duration = Duration.ofMinutes(2)
    }
}

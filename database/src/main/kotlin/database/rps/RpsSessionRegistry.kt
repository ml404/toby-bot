package database.rps

import common.pvp.rps.RpsEngine
import database.configuration.RegistryScheduler
import database.pvp.PvpSessionRegistry
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Simultaneous-pick specialisation of [PvpSessionRegistry] for `/rps`.
 * The base owns the Caffeine cache, the PENDING → LIVE state machine,
 * the pending-phase timeout, the indexers, and the resolution/forfeit
 * remove primitives. This class adds:
 *
 *  - A [Session] subclass with the per-player [Session.picks] map and
 *    [Session.bothPicked] convenience flag
 *  - An [accept] override that schedules a single LIVE-phase pick
 *    timeout — RPS is simultaneous, so there's no per-move shot clock,
 *    just one timer that fires if both players don't pick within
 *    [pickTtl]
 *  - [recordPick] for stashing a player's [RpsEngine.Choice] under lock
 *
 * Lifecycle:
 *   - PENDING — `/rps` posted; opponent hasn't yet hit Accept. Stakes
 *     NOT yet debited.
 *   - LIVE — opponent accepted; both stakes locked. Each side privately
 *     submits a [RpsEngine.Choice] via [recordPick]; once
 *     [Session.bothPicked] is true the caller drains the session via
 *     [PvpSessionRegistry.consumeForResolution] and resolves the wager.
 */
@Component
class RpsSessionRegistry(
    pendingTtl: Duration = DEFAULT_PENDING_TTL,
    val pickTtl: Duration = DEFAULT_PICK_TTL,
    scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    maximumSessions: Long = PvpSessionRegistry.MAX_SESSIONS,
    maxLifetime: Duration = MAX_LIFETIME,
) : PvpSessionRegistry<RpsSessionRegistry.Session>(pendingTtl, scheduler, maximumSessions, maxLifetime) {

    class Session(
        id: Long,
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant,
        /** Picks indexed by discord id; both populated → ready to resolve. */
        val picks: MutableMap<Long, RpsEngine.Choice> = mutableMapOf(),
        state: State = State.PENDING,
    ) : PvpSessionRegistry.Session(id, guildId, initiatorDiscordId, opponentDiscordId, stake, createdAt, state) {
        val bothPicked: Boolean get() = picks.size == 2
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
     * Transition PENDING → LIVE on the opponent's Accept and schedule
     * the LIVE-phase pick timer. The atomic compare-and-set on `state`
     * in [transitionPendingToLive] means concurrent Accept / Decline /
     * pending-timeout collapses to one winner. The pick timer fires
     * only if no resolution drained the session in the meantime.
     */
    fun accept(id: Long, onPickTimeout: (Session) -> Unit = {}): Session? {
        val session = transitionPendingToLive(id) ?: return null
        scheduler.schedule({
            // Only fire if no resolution has consumed the session yet.
            val current = sessions[id]
            if (current != null && current.state == PvpSessionRegistry.Session.State.LIVE && !current.bothPicked) {
                val expired = sessions.remove(id)
                if (expired != null) runCatching { onPickTimeout(expired) }
            }
        }, pickTtl.toMillis(), TimeUnit.MILLISECONDS)
        return session
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
            if (session.state != PvpSessionRegistry.Session.State.LIVE) return null
            if (discordId != session.initiatorDiscordId && discordId != session.opponentDiscordId) return null
            session.picks[discordId] = choice
            return session
        }
    }

    companion object {
        val DEFAULT_PENDING_TTL: Duration = Duration.ofMinutes(3)
        val DEFAULT_PICK_TTL: Duration = Duration.ofMinutes(2)

        /**
         * Backstop lifetime for any single RPS session: pending TTL
         * (3m) + pick TTL (2m) + 1m slop = 6 minutes. Narrower than
         * the [PvpSessionRegistry.MAX_LIFETIME] default (12 minutes,
         * sized for the longest turn-based games) because RPS is
         * always short.
         */
        val MAX_LIFETIME: Duration = Duration.ofMinutes(6)
    }
}

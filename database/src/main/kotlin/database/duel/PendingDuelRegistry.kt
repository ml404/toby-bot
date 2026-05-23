package database.duel

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import database.configuration.RegistryScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory book of pending duel offers (the Accept/Decline window
 * between `/duel` and the opponent's button click; see [DEFAULT_TTL]).
 * Shared across the whole Spring context so the Discord button handler
 * and the web controller see the same offers — there is exactly one
 * authoritative bean.
 *
 * Mid-flight offers are lost on bot restart. Acceptable since the TTL
 * is short and offers don't move credits until accepted.
 *
 * **Storage**: backed by a Caffeine [Cache] via `asMap()` so we get a
 * hard upper bound on concurrent offers ([MAX_OFFERS]) and a TTL
 * backstop ([MAX_LIFETIME]) for entries the scheduler somehow misses.
 * Matches the convention used by `MessageChatListener.lastAwardAt` and
 * the SSE registry — belt-and-suspenders memory protection beyond the
 * scheduler-driven [onTimeout] callbacks. The scheduler remains the
 * primary mechanism because the callback closes over JDA message-edit
 * state Caffeine's removalListener can't reach; the cache TTL is the
 * leak guard if a scheduled task throws or the executor queue
 * saturates.
 */
@Component
class PendingDuelRegistry(
    val ttl: Duration = DEFAULT_TTL,
    private val scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    maximumOffers: Long = MAX_OFFERS,
    maxLifetime: Duration = MAX_LIFETIME,
) {
    data class PendingDuel(
        val id: Long,
        val guildId: Long,
        val initiatorDiscordId: Long,
        val opponentDiscordId: Long,
        val stake: Long,
        val createdAt: Instant
    )

    /**
     * Caffeine cache + `asMap()` view. Every read/write below uses
     * [offers] (the ConcurrentMap view) for atomic primitives; [cache]
     * is kept only so the `expireAfterWrite` / `maximumSize`
     * configuration stays in scope.
     */
    private val cache: Cache<Long, PendingDuel> = Caffeine.newBuilder()
        .expireAfterWrite(maxLifetime)
        .maximumSize(maximumOffers)
        .build()
    private val offers: ConcurrentMap<Long, PendingDuel> = cache.asMap()
    private val seq = AtomicLong()

    /**
     * Stores a new pending offer with a unique id and schedules expiry
     * at `now + ttl`. If the offer is still in the registry when the
     * timer fires, [onTimeout] is invoked exactly once. If the offer
     * was already consumed (accept) or cancelled (decline) before the
     * timer fires, the timer is a no-op.
     */
    fun register(
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant = Instant.now(),
        onTimeout: (PendingDuel) -> Unit = {}
    ): PendingDuel {
        val id = seq.incrementAndGet()
        val offer = PendingDuel(
            id = id,
            guildId = guildId,
            initiatorDiscordId = initiatorDiscordId,
            opponentDiscordId = opponentDiscordId,
            stake = stake,
            createdAt = createdAt
        )
        offers[id] = offer
        scheduler.schedule({
            val expired = offers.remove(id)
            if (expired != null) {
                runCatching { onTimeout(expired) }
            }
        }, ttl.toMillis(), TimeUnit.MILLISECONDS)
        return offer
    }

    /** Atomic remove for accept. Returns the offer or null if already gone. */
    fun consumeForAccept(id: Long): PendingDuel? = offers.remove(id)

    /** Atomic remove for decline. Returns the offer or null if already gone. */
    fun cancel(id: Long): PendingDuel? = offers.remove(id)

    /** All offers where [discordId] is the opponent — used by the web inbox. */
    fun pendingForOpponent(discordId: Long, guildId: Long): List<PendingDuel> =
        offers.values.filter { it.opponentDiscordId == discordId && it.guildId == guildId }

    /** All offers where [discordId] is the initiator — used by the web outbox. */
    fun pendingForInitiator(discordId: Long, guildId: Long): List<PendingDuel> =
        offers.values.filter { it.initiatorDiscordId == discordId && it.guildId == guildId }

    fun get(id: Long): PendingDuel? = offers[id]

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofMinutes(3)

        /**
         * Hard upper bound on concurrent in-flight offers. Generous
         * enough that a server-wide tournament doesn't trip it, low
         * enough that a malformed `/duel` flood can't OOM the heap.
         * Caffeine evicts least-recently-used entries above the cap.
         */
        const val MAX_OFFERS: Long = 10_000L

        /**
         * Backstop lifetime for any single offer: default pending TTL
         * (3m) + 1m slop = 4 minutes. Caffeine evicts past this even
         * if the scheduler-driven timeout somehow didn't run; protects
         * memory if a scheduled task throws or the executor saturates.
         */
        val MAX_LIFETIME: Duration = Duration.ofMinutes(4)

        /** "3m" / "90s" / "1h 5m" — short-form TTL display for embed copy and web UI. */
        fun formatTtl(ttl: Duration): String {
            val total = ttl.seconds.coerceAtLeast(0)
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            val seconds = total % 60
            return when {
                hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                hours > 0 -> "${hours}h"
                minutes > 0 && seconds > 0 -> "${minutes}m ${seconds}s"
                minutes > 0 -> "${minutes}m"
                else -> "${seconds}s"
            }
        }
    }
}

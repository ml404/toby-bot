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
 * Short-TTL cache of "I just resolved a duel — anyone polling for
 * their outgoing offers should learn about it before the entry
 * expires." The only consumer today is `DuelWebService` projecting
 * resolutions into the `/duel/{guildId}/outgoing` response so the
 * initiator's browser can replay the same accept-time animation the
 * acceptor saw.
 *
 * Read-once semantics: [consumeForInitiator] removes matching entries
 * so a long-running page doesn't replay the animation on every 5s
 * poll. The TTL is purely a safety net for entries no one ever
 * consumed (initiator was offline / not on /duel).
 *
 * In-memory only — like [PendingDuelRegistry]. A bot restart between
 * resolve and poll just means the animation doesn't play; balances
 * are already authoritative in the DB.
 *
 * **Storage**: backed by a Caffeine [Cache] via `asMap()`. Bounds
 * concurrent retained resolutions at [MAX_ENTRIES] and adds a TTL
 * backstop ([MAX_LIFETIME]) for entries that the scheduler-driven
 * eviction somehow misses. Same belt-and-suspenders pattern as
 * `RpsSessionRegistry` and `PendingDuelRegistry`.
 */
@Component
class RecentDuelResolutions(
    val ttl: Duration = DEFAULT_TTL,
    private val scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    maximumEntries: Long = MAX_ENTRIES,
    maxLifetime: Duration = MAX_LIFETIME,
) {
    data class Resolution(
        val guildId: Long,
        val initiatorDiscordId: Long,
        val opponentDiscordId: Long,
        val winnerDiscordId: Long,
        val loserDiscordId: Long,
        val stake: Long,
        val pot: Long,
        val lossTribute: Long,
        val resolvedAt: Instant,
    )

    private val cache: Cache<Long, Resolution> = Caffeine.newBuilder()
        .expireAfterWrite(maxLifetime)
        .maximumSize(maximumEntries)
        .build()
    private val entries: ConcurrentMap<Long, Resolution> = cache.asMap()
    private val seq = AtomicLong()

    /** Store a resolution and schedule its eviction at `now + ttl`. */
    fun record(resolution: Resolution) {
        val id = seq.incrementAndGet()
        entries[id] = resolution
        scheduler.schedule({ entries.remove(id) }, ttl.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * All matching entries for [discordId] as the initiator in [guildId],
     * removed from the cache so subsequent polls don't replay them.
     * Returns entries in `resolvedAt` order (oldest first) — the
     * caller can decide whether to play all or just the newest.
     */
    fun consumeForInitiator(discordId: Long, guildId: Long): List<Resolution> {
        val matchingIds = entries.entries.asSequence()
            .filter { (_, r) -> r.initiatorDiscordId == discordId && r.guildId == guildId }
            .map { it.key }
            .toList()
        if (matchingIds.isEmpty()) return emptyList()
        return matchingIds
            .mapNotNull { id -> entries.remove(id) }
            .sortedBy { it.resolvedAt }
    }

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofSeconds(30)

        /**
         * Hard upper bound on concurrent retained resolutions. A 30s
         * TTL × any plausible duel-resolution rate fits well under this
         * — the cap is purely a malformed-flood guard, not a normal-
         * traffic limit.
         */
        const val MAX_ENTRIES: Long = 10_000L

        /**
         * Backstop lifetime: default TTL (30s) + 30s slop = 1 minute.
         * Caffeine evicts past this even if the scheduler's
         * `entries.remove(id)` didn't fire.
         */
        val MAX_LIFETIME: Duration = Duration.ofMinutes(1)
    }
}

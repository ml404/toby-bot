package database.duel

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
 */
@Component
class PendingDuelRegistry(
    val ttl: Duration = DEFAULT_TTL,
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
) {
    data class PendingDuel(
        val id: Long,
        val guildId: Long,
        val initiatorDiscordId: Long,
        val opponentDiscordId: Long,
        val stake: Long,
        val createdAt: Instant
    )

    private val offers = ConcurrentHashMap<Long, PendingDuel>()
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

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofMinutes(3)

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

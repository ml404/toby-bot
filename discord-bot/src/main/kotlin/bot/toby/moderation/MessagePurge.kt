package bot.toby.moderation

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.requests.RestAction
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The two rules Discord imposes on deleting messages in bulk, in one place so
 * `/purge` and the **Purge from here** right-click entry can't drift apart on
 * them.
 */
object MessagePurge {

    /** Discord's bulk-delete endpoint takes at most this many messages. */
    const val MAX_BULK = 100

    /** Bulk-delete refuses anything older, with no partial success. */
    private const val MAX_AGE_DAYS = 14L

    data class Deletable(val messages: List<Message>, val tooOld: Int) {
        /** Appended to the outcome so a short count isn't read as a bug. */
        val suffix: String get() = if (tooOld > 0) " ($tooOld older than 14 days skipped)" else ""
    }

    /**
     * Splits off what the bulk endpoint will actually accept.
     *
     * Anything past the cutoff is dropped rather than deleted one at a time:
     * a hundred individual deletes would burn the channel's rate limit for no
     * real win on a command whose whole point is speed.
     */
    fun deletable(messages: List<Message>, now: Instant = Instant.now()): Deletable {
        val cutoff = now.minus(MAX_AGE_DAYS, ChronoUnit.DAYS)
        val recent = messages.filter { it.timeCreated.toInstant().isAfter(cutoff) }
        return Deletable(recent, messages.size - recent.size)
    }

    /**
     * The bulk endpoint rejects a single-message batch, so one goes through the
     * ordinary delete — which is also the only one of the two that can carry an
     * audit-log reason.
     */
    fun delete(channel: GuildMessageChannel, messages: List<Message>, reason: String): RestAction<Void> =
        if (messages.size == 1) {
            messages.single().delete().reason(reason)
        } else {
            channel.deleteMessages(messages)
        }
}

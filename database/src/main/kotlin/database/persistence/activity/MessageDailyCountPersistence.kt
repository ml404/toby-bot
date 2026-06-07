package database.persistence.activity

import database.dto.activity.MessageDailyCountDto
import java.time.LocalDate

/**
 * Read + upsert plumbing for the per-guild per-day message counter
 * powering the moderation Activity tab. Writes are upserts so the
 * `MessageActivityBuffer` flush loop can hand in `(guildId, day, delta)`
 * without worrying about whether the row already exists.
 */
interface MessageDailyCountPersistence {
    /** Counters for [guildId] on dates `>= since`, ascending by day. */
    fun findByGuildSince(guildId: Long, since: LocalDate): List<MessageDailyCountDto>

    /**
     * Add [delta] to the counter for `(guildId, dayStart)`, creating the
     * row if it doesn't exist. The counter is monotonic — pass a positive
     * delta. `updatedAt` is bumped on every call.
     */
    fun increment(guildId: Long, dayStart: LocalDate, delta: Long)

    /**
     * Most recent message-activity day per guild, across all guilds, in a
     * single grouped query. Powers the operator dashboard's active-vs-quiet
     * liveness split — guilds absent from the result have never recorded a
     * message day.
     */
    fun findLastActiveByGuild(): Map<Long, LocalDate>
}

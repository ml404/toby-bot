package bot.toby.voice

import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class VoiceCompanyTracker {

    private data class Tracker(
        @Volatile var companyAccumSeconds: Long = 0L,
        @Volatile var companyStartedAt: Instant? = null
    )

    private val trackers = ConcurrentHashMap<Pair<Long, Long>, Tracker>()

    fun startTracking(userId: Long, guildId: Long, channel: AudioChannel, now: Instant) {
        val tracker = Tracker()
        if (hasCompany(channel, userId)) {
            tracker.companyStartedAt = now
        }
        trackers[userId to guildId] = tracker
    }

    fun stopTracking(userId: Long, guildId: Long, now: Instant): Long {
        val tracker = trackers.remove(userId to guildId) ?: return 0L
        tracker.companyStartedAt?.let { started ->
            tracker.companyAccumSeconds += maxOf(0L, now.epochSecond - started.epochSecond)
        }
        return tracker.companyAccumSeconds
    }

    fun reconcileChannel(channel: AudioChannel, now: Instant) {
        val guildId = channel.guild.idLong
        channel.members.forEach { member ->
            if (member.user.isBot) return@forEach
            val key = member.idLong to guildId
            val tracker = trackers[key] ?: return@forEach
            val companyNow = hasCompany(channel, member.idLong)
            val startedAt = tracker.companyStartedAt
            when {
                companyNow && startedAt == null -> tracker.companyStartedAt = now
                !companyNow && startedAt != null -> {
                    tracker.companyAccumSeconds += maxOf(0L, now.epochSecond - startedAt.epochSecond)
                    tracker.companyStartedAt = null
                }
            }
        }
    }

    private fun hasCompany(channel: AudioChannel, exceptUserId: Long): Boolean {
        return channel.members.any { other ->
            !other.user.isBot && other.idLong != exceptUserId
        }
    }
}

package bot.toby.intro

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Remembers, per (guild, user), when an intro last played and which one.
 *
 * Two problems this solves, both from the voice-join path having no memory:
 *
 *  - **Replay spam.** Every `GuildVoiceUpdateEvent` join re-triggered the
 *    intro, so channel-hopping — or a flaky connection reconnecting — replayed
 *    it each time, and awarded the intro-play credit and XP each time too.
 *  - **Repeats.** The pick was uniform over all slots, with nothing stopping
 *    the same intro coming up several joins running (see [IntroSelection]).
 *
 * In-memory: a redeploy forgetting that someone joined a minute ago is
 * harmless, and it keeps a hot path off the database.
 */
@Service
class IntroPlaybackTracker {

    private data class Play(val introId: String?, val at: Instant)

    // Entries only matter for the length of the cooldown; expiring a little
    // after that keeps `lastPlayedIntroId` useful across a short absence
    // without holding state for idle users.
    private val plays = Caffeine.newBuilder()
        .expireAfterWrite(RETENTION_MINUTES, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build<String, Play>()

    /**
     * True when this user's intro played recently enough that joining again
     * shouldn't retrigger it.
     */
    fun onCooldown(guildId: Long, discordId: Long, now: Instant = Instant.now()): Boolean {
        val last = plays.getIfPresent(key(guildId, discordId)) ?: return false
        return Duration.between(last.at, now) < COOLDOWN
    }

    /** The intro played last time, so the next pick can avoid repeating it. */
    fun lastPlayedIntroId(guildId: Long, discordId: Long): String? =
        plays.getIfPresent(key(guildId, discordId))?.introId

    fun record(guildId: Long, discordId: Long, introId: String?, now: Instant = Instant.now()) {
        plays.put(key(guildId, discordId), Play(introId, now))
    }

    private fun key(guildId: Long, discordId: Long) = "$guildId:$discordId"

    companion object {
        /**
         * Long enough to absorb channel hops and reconnect flapping, short
         * enough that genuinely leaving and coming back still plays you in.
         */
        val COOLDOWN: Duration = Duration.ofSeconds(60)

        private const val RETENTION_MINUTES = 30L
    }
}

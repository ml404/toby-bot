package bot.toby.intro

import com.github.benmanes.caffeine.cache.Caffeine
import database.dto.music.MusicDto
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * Short-lived snapshots of intros deleted through `/deleteintro`, so the
 * confirmation's **Undo** button can put one back.
 *
 * The web dashboard has had undo since it stored a `DeletedIntroSnapshot` in
 * the HTTP session; Discord had nothing — a mis-click on the select menu was
 * final, and re-uploading meant finding the file or URL again. This is the
 * Discord-side equivalent, keyed the same way (guild + user) and with the same
 * ten-minute window.
 *
 * In-memory on purpose: a redeploy dropping a few pending undos is fine, and
 * it keeps deleted audio out of long-term storage.
 */
@Service
class IntroUndoStore {

    data class Snapshot(
        val guildId: Long,
        val discordId: Long,
        val index: Int,
        val fileName: String?,
        val introVolume: Int,
        val musicBlob: ByteArray?,
        val startMs: Int?,
        val endMs: Int?,
    ) {
        // ByteArray in a data class gives reference equality; MusicDto has the
        // same shape. Nothing compares snapshots today, so rather than a subtly
        // wrong generated equals, make it explicit that identity is the id.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val snapshots = Caffeine.newBuilder()
        .expireAfterWrite(UNDO_WINDOW_MINUTES, TimeUnit.MINUTES)
        .maximumSize(1_000)
        .build<String, Snapshot>()

    /**
     * @param actorDiscordId who pressed delete — the undo button is theirs to
     *        press, which for a super-user managing someone else's intros is
     *        not the same person as [MusicDto.userDto].
     */
    fun remember(intro: MusicDto, actorDiscordId: Long? = null) {
        val user = intro.userDto ?: return
        val guildId = user.guildId
        val discordId = user.discordId
        snapshots.put(
            key(guildId, actorDiscordId ?: discordId),
            Snapshot(
                guildId = guildId,
                discordId = discordId,
                index = intro.index ?: 1,
                fileName = intro.fileName,
                introVolume = intro.introVolume ?: 100,
                musicBlob = intro.musicBlob?.copyOf(),
                startMs = intro.startMs,
                endMs = intro.endMs,
            ),
        )
    }

    /** Returns and clears the pending snapshot — undo is single-use. */
    fun take(guildId: Long, discordId: Long): Snapshot? {
        val cacheKey = key(guildId, discordId)
        return snapshots.getIfPresent(cacheKey)?.also { snapshots.invalidate(cacheKey) }
    }

    private fun key(guildId: Long, discordId: Long) = "$guildId:$discordId"

    companion object {
        /** Matches the web dashboard's undo window. */
        const val UNDO_WINDOW_MINUTES = 10L
    }
}

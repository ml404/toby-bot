package database.service.user

import database.dto.user.SharedCubeDto
import database.dto.user.SharedCubeKind
import java.time.Instant

interface SharedCubeService {
    /**
     * Mints a new shareable snapshot of [cards] under a fresh random token
     * and returns it. Each call is a new snapshot (immutable), so editing and
     * re-sharing produces a new link rather than changing an old one.
     *
     * [kind] decides how opening the link renders: a cube list or a dealt
     * set of packs.
     */
    fun create(
        discordId: Long,
        name: String,
        cards: String,
        at: Instant = Instant.now(),
        kind: SharedCubeKind = SharedCubeKind.CUBE,
    ): SharedCubeDto

    fun get(token: String): SharedCubeDto?

    /**
     * How many snapshots [discordId] has published. Snapshots are immutable
     * and never overwritten, so without a ceiling one account could mint them
     * without limit — this is what the web layer's cap is checked against.
     */
    fun countForUser(discordId: Long): Long

    /**
     * Deletes snapshots older than [olderThan], returning how many went.
     * Nothing else ever removes a row: a share link is meant to outlive the
     * draft it was made for, but not by years.
     */
    fun purge(olderThan: Instant): Int
}

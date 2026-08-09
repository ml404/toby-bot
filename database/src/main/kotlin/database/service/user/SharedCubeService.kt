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
}

package database.service.user

import database.dto.user.SharedCubeDto
import java.time.Instant

interface SharedCubeService {
    /**
     * Mints a new shareable snapshot of [cards] under a fresh random token
     * and returns it. Each call is a new snapshot (immutable), so editing and
     * re-sharing produces a new link rather than changing an old one.
     */
    fun create(discordId: Long, name: String, cards: String, at: Instant = Instant.now()): SharedCubeDto

    fun get(token: String): SharedCubeDto?
}

package database.service.user

import database.dto.user.CubeListDto
import java.time.Instant

interface CubeListService {
    fun listForUser(discordId: Long): List<CubeListDto>

    fun get(discordId: Long, name: String): CubeListDto?

    /**
     * Save (or overwrite) the list named [name] for [discordId]. Idempotent
     * on the name: re-saving refreshes the cards and bumps `updated_at` while
     * preserving the original `created_at`.
     */
    fun save(discordId: Long, name: String, cards: String, at: Instant = Instant.now()): CubeListDto

    /** Idempotent — true if a row was actually removed. */
    fun delete(discordId: Long, name: String): Boolean
}

package database.persistence.user

import database.dto.user.CubeListDto

interface CubeListPersistence {
    fun listForUser(discordId: Long): List<CubeListDto>

    fun get(discordId: Long, name: String): CubeListDto?

    /**
     * Insert when no row matches the (discord_id, name) key; otherwise refresh
     * the existing row's `cards` and `updated_at`. Re-saving a list under a
     * name the user already used overwrites it rather than erroring.
     */
    fun upsert(row: CubeListDto): CubeListDto

    /** Idempotent — returns the number of rows actually removed (0 or 1). */
    fun delete(discordId: Long, name: String): Int
}

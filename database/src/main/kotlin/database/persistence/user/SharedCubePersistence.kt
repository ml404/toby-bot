package database.persistence.user

import database.dto.user.SharedCubeDto
import java.time.Instant

interface SharedCubePersistence {
    fun get(token: String): SharedCubeDto?

    /** Inserts a new snapshot. Tokens are unique, so this is never an update. */
    fun insert(row: SharedCubeDto): SharedCubeDto

    /** How many snapshots this user has published, for the per-user cap. */
    fun countForUser(discordId: Long): Long

    /** Deletes every snapshot created before [cutoff]; returns how many went. */
    fun deleteOlderThan(cutoff: Instant): Int
}

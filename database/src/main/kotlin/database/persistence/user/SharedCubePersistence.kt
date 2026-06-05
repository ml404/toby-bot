package database.persistence.user

import database.dto.user.SharedCubeDto

interface SharedCubePersistence {
    fun get(token: String): SharedCubeDto?

    /** Inserts a new snapshot. Tokens are unique, so this is never an update. */
    fun insert(row: SharedCubeDto): SharedCubeDto
}

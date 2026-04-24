package database.persistence

import database.dto.TitleDto
import database.dto.UserOwnedTitleDto

interface TitlePersistence {
    fun listAll(): List<TitleDto>
    fun getById(id: Long): TitleDto?
    fun getByLabel(label: String): TitleDto?

    fun listOwned(discordId: Long): List<UserOwnedTitleDto>
    fun owns(discordId: Long, titleId: Long): Boolean
    fun recordPurchase(owned: UserOwnedTitleDto): UserOwnedTitleDto
}

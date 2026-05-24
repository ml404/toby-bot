package database.persistence.guild

import database.dto.TitleDto
import database.dto.UserOwnedTitleDto

interface TitlePersistence {
    fun listAll(): List<TitleDto>
    fun getById(id: Long): TitleDto?
    fun getByLabel(label: String): TitleDto?

    fun listOwned(discordId: Long): List<UserOwnedTitleDto>
    fun owns(discordId: Long, titleId: Long): Boolean
    fun recordPurchase(owned: UserOwnedTitleDto): UserOwnedTitleDto

    /**
     * Merge a mutated [TitleDto] back into the persistence context.
     * Used by the leveling moderation page to update `required_level`
     * on existing rows. Returns the managed instance after flush.
     */
    fun update(title: TitleDto): TitleDto
}

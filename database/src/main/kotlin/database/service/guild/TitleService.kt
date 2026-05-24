package database.service.guild

import database.dto.guild.TitleDto
import database.dto.guild.UserOwnedTitleDto

interface TitleService {
    fun listAll(): List<TitleDto>
    fun getById(id: Long): TitleDto?
    fun getByLabel(label: String): TitleDto?

    fun listOwned(discordId: Long): List<UserOwnedTitleDto>
    fun owns(discordId: Long, titleId: Long): Boolean
    fun recordPurchase(discordId: Long, titleId: Long): UserOwnedTitleDto

    /**
     * Update the level gate on an existing title. Returns the updated
     * row, or null if no title exists with [titleId]. [requiredLevel]
     * must be `>= 0`; the caller is expected to validate before calling.
     * A value of 0 means "no gate" (default).
     */
    fun updateRequiredLevel(titleId: Long, requiredLevel: Int): TitleDto?
}

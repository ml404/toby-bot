package database.service.guild.impl

import database.dto.guild.TitleDto
import database.dto.guild.UserOwnedTitleDto
import database.persistence.guild.TitlePersistence
import database.service.guild.TitleService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultTitleService @Autowired constructor(
    private val persistence: TitlePersistence
) : TitleService {

    override fun listAll(): List<TitleDto> = persistence.listAll()
    override fun getById(id: Long): TitleDto? = persistence.getById(id)
    override fun getByLabel(label: String): TitleDto? = persistence.getByLabel(label)
    override fun listOwned(discordId: Long): List<UserOwnedTitleDto> = persistence.listOwned(discordId)
    override fun owns(discordId: Long, titleId: Long): Boolean = persistence.owns(discordId, titleId)

    override fun recordPurchase(discordId: Long, titleId: Long): UserOwnedTitleDto {
        val owned = UserOwnedTitleDto(
            discordId = discordId,
            titleId = titleId,
            boughtAt = Instant.now()
        )
        return persistence.recordPurchase(owned)
    }

    override fun updateRequiredLevel(titleId: Long, requiredLevel: Int): TitleDto? {
        val existing = persistence.getById(titleId) ?: return null
        existing.requiredLevel = requiredLevel
        return persistence.update(existing)
    }
}

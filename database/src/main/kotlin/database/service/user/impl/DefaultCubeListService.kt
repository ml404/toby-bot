package database.service.user.impl

import database.dto.user.CubeListDto
import database.persistence.user.CubeListPersistence
import database.service.user.CubeListService
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultCubeListService(
    private val persistence: CubeListPersistence,
) : CubeListService {

    override fun listForUser(discordId: Long): List<CubeListDto> =
        persistence.listForUser(discordId)

    override fun get(discordId: Long, name: String): CubeListDto? =
        persistence.get(discordId, name)

    override fun save(discordId: Long, name: String, cards: String, at: Instant): CubeListDto {
        val existing = persistence.get(discordId, name)
        return persistence.upsert(
            CubeListDto(
                discordId = discordId,
                name = name,
                cards = cards,
                createdAt = existing?.createdAt ?: at,
                updatedAt = at,
            )
        )
    }

    override fun delete(discordId: Long, name: String): Boolean =
        persistence.delete(discordId, name) > 0
}

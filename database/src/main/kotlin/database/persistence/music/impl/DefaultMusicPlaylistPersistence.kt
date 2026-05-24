package database.persistence.music.impl

import database.dto.MusicPlaylistDto
import database.persistence.music.MusicPlaylistPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.NoResultException
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultMusicPlaylistPersistence internal constructor() : MusicPlaylistPersistence {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun listByGuild(guildId: Long): List<MusicPlaylistDto> {
        val q: TypedQuery<MusicPlaylistDto> =
            entityManager.createNamedQuery("MusicPlaylistDto.getByGuild", MusicPlaylistDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun listByGuildAndOwner(guildId: Long, ownerDiscordId: Long): List<MusicPlaylistDto> {
        val q: TypedQuery<MusicPlaylistDto> =
            entityManager.createNamedQuery("MusicPlaylistDto.getByGuildAndOwner", MusicPlaylistDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("ownerId", ownerDiscordId)
        return q.resultList
    }

    override fun getByGuildOwnerAndName(guildId: Long, ownerDiscordId: Long, name: String): MusicPlaylistDto? {
        val q: TypedQuery<MusicPlaylistDto> =
            entityManager.createNamedQuery("MusicPlaylistDto.getByGuildOwnerAndName", MusicPlaylistDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("ownerId", ownerDiscordId)
        q.setParameter("name", name)
        return try {
            q.singleResult
        } catch (_: NoResultException) {
            null
        }
    }

    override fun getById(id: Long): MusicPlaylistDto? {
        val q: TypedQuery<MusicPlaylistDto> =
            entityManager.createNamedQuery("MusicPlaylistDto.getById", MusicPlaylistDto::class.java)
        q.setParameter("id", id)
        return try {
            q.singleResult
        } catch (_: NoResultException) {
            null
        }
    }

    override fun save(dto: MusicPlaylistDto): MusicPlaylistDto {
        entityManager.persist(dto)
        entityManager.flush()
        return dto
    }

    override fun update(dto: MusicPlaylistDto): MusicPlaylistDto {
        val merged = entityManager.merge(dto)
        entityManager.flush()
        return merged
    }

    override fun deleteById(id: Long) {
        val q = entityManager.createNamedQuery("MusicPlaylistDto.deleteById")
        q.setParameter("id", id)
        q.executeUpdate()
    }
}

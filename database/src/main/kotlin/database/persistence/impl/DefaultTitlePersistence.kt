package database.persistence.impl

import database.dto.TitleDto
import database.dto.UserOwnedTitleDto
import database.persistence.TitlePersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultTitlePersistence : TitlePersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun listAll(): List<TitleDto> {
        val q: TypedQuery<TitleDto> = entityManager.createNamedQuery("TitleDto.getAll", TitleDto::class.java)
        return q.resultList
    }

    override fun getById(id: Long): TitleDto? = entityManager.find(TitleDto::class.java, id)

    override fun getByLabel(label: String): TitleDto? {
        val q: TypedQuery<TitleDto> = entityManager.createNamedQuery("TitleDto.getByLabel", TitleDto::class.java)
        q.setParameter("label", label)
        return q.resultList.firstOrNull()
    }

    override fun listOwned(discordId: Long): List<UserOwnedTitleDto> {
        val q: TypedQuery<UserOwnedTitleDto> =
            entityManager.createNamedQuery("UserOwnedTitleDto.getByUser", UserOwnedTitleDto::class.java)
        q.setParameter("discordId", discordId)
        return q.resultList
    }

    override fun owns(discordId: Long, titleId: Long): Boolean {
        val q = entityManager.createNamedQuery("UserOwnedTitleDto.exists")
        q.setParameter("discordId", discordId)
        q.setParameter("titleId", titleId)
        val count = (q.singleResult as? Number)?.toLong() ?: 0L
        return count > 0
    }

    override fun recordPurchase(owned: UserOwnedTitleDto): UserOwnedTitleDto {
        entityManager.persist(owned)
        entityManager.flush()
        return owned
    }
}

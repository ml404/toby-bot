package database.persistence.impl

import database.dto.MonsterTemplateDto
import database.persistence.MonsterTemplatePersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
@Transactional
class DefaultMonsterTemplatePersistence : MonsterTemplatePersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(template: MonsterTemplateDto): MonsterTemplateDto {
        val now = LocalDateTime.now()
        template.updatedAt = now
        return if (template.id == 0L) {
            template.createdAt = now
            entityManager.persist(template)
            entityManager.flush()
            template
        } else {
            val merged = entityManager.merge(template)
            entityManager.flush()
            merged
        }
    }

    override fun getById(id: Long): MonsterTemplateDto? {
        val q = entityManager.createNamedQuery("MonsterTemplateDto.getById", MonsterTemplateDto::class.java)
        q.setParameter("id", id)
        return runCatching { q.singleResult }.getOrNull()
    }

    override fun listByDm(dmDiscordId: Long): List<MonsterTemplateDto> {
        val q = entityManager.createNamedQuery("MonsterTemplateDto.getByDm", MonsterTemplateDto::class.java)
        q.setParameter("dmDiscordId", dmDiscordId)
        return q.resultList
    }

    override fun deleteById(id: Long) {
        val q = entityManager.createNamedQuery("MonsterTemplateDto.deleteById")
        q.setParameter("id", id)
        q.executeUpdate()
    }
}

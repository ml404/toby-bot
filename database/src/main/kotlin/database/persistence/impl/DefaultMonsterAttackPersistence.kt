package database.persistence.impl

import database.dto.MonsterAttackDto
import database.persistence.MonsterAttackPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
@Transactional
class DefaultMonsterAttackPersistence : MonsterAttackPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(attack: MonsterAttackDto): MonsterAttackDto {
        return entityManager.saveOrMerge(attack, isNew = { it.id == 0L }) {
            it.createdAt = LocalDateTime.now()
        }
    }

    override fun getById(id: Long): MonsterAttackDto? {
        val q = entityManager.createNamedQuery("MonsterAttackDto.getById", MonsterAttackDto::class.java)
        q.setParameter("id", id)
        return runCatching { q.singleResult }.getOrNull()
    }

    override fun listByTemplate(templateId: Long): List<MonsterAttackDto> {
        val q = entityManager.createNamedQuery("MonsterAttackDto.listByTemplate", MonsterAttackDto::class.java)
        q.setParameter("templateId", templateId)
        return q.resultList
    }

    override fun countByTemplate(templateId: Long): Long {
        val q = entityManager.createNamedQuery("MonsterAttackDto.countByTemplate")
        q.setParameter("templateId", templateId)
        return (q.singleResult as Number).toLong()
    }

    override fun deleteById(id: Long) {
        val q = entityManager.createNamedQuery("MonsterAttackDto.deleteById")
        q.setParameter("id", id)
        q.executeUpdate()
    }
}

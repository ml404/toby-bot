package database.persistence.impl

import database.dto.LevelRoleRewardDto
import database.persistence.LevelRoleRewardPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultLevelRoleRewardPersistence : LevelRoleRewardPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun listForGuild(guildId: Long): List<LevelRoleRewardDto> {
        val q: TypedQuery<LevelRoleRewardDto> = entityManager
            .createNamedQuery("LevelRoleRewardDto.getGuildAll", LevelRoleRewardDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun get(guildId: Long, level: Int): LevelRoleRewardDto? {
        val q: TypedQuery<LevelRoleRewardDto> = entityManager
            .createNamedQuery("LevelRoleRewardDto.getByGuildAndLevel", LevelRoleRewardDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("level", level)
        return q.resultList.firstOrNull()
    }

    override fun listInRange(
        guildId: Long,
        fromExclusive: Int,
        toInclusive: Int
    ): List<LevelRoleRewardDto> {
        val q: TypedQuery<LevelRoleRewardDto> = entityManager
            .createNamedQuery("LevelRoleRewardDto.getInRange", LevelRoleRewardDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("fromExclusive", fromExclusive)
        q.setParameter("toInclusive", toInclusive)
        return q.resultList
    }

    override fun upsert(reward: LevelRoleRewardDto): LevelRoleRewardDto {
        val existing = get(reward.guildId, reward.level)
        return if (existing == null) {
            entityManager.persist(reward)
            entityManager.flush()
            reward
        } else {
            existing.roleId = reward.roleId
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }

    override fun delete(guildId: Long, level: Int) {
        val existing = get(guildId, level) ?: return
        entityManager.remove(existing)
        entityManager.flush()
    }
}

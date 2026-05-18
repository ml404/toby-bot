package database.persistence.impl

import database.dto.AchievementDto
import database.dto.AchievementProgressDto
import database.dto.UserAchievementDto
import database.persistence.AchievementPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultAchievementPersistence : AchievementPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun listAll(): List<AchievementDto> {
        val q: TypedQuery<AchievementDto> =
            entityManager.createNamedQuery("AchievementDto.getAll", AchievementDto::class.java)
        return q.resultList
    }

    override fun getByCode(code: String): AchievementDto? {
        val q: TypedQuery<AchievementDto> =
            entityManager.createNamedQuery("AchievementDto.getByCode", AchievementDto::class.java)
        q.setParameter("code", code)
        return q.resultList.firstOrNull()
    }

    override fun getById(id: Long): AchievementDto? =
        entityManager.find(AchievementDto::class.java, id)

    override fun save(achievement: AchievementDto): AchievementDto {
        return if (achievement.id == null) {
            entityManager.persist(achievement)
            entityManager.flush()
            achievement
        } else {
            val merged = entityManager.merge(achievement)
            entityManager.flush()
            merged
        }
    }

    override fun listOwnedByUser(discordId: Long, guildId: Long): List<UserAchievementDto> {
        val q: TypedQuery<UserAchievementDto> =
            entityManager.createNamedQuery("UserAchievementDto.getByUser", UserAchievementDto::class.java)
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun owns(discordId: Long, guildId: Long, achievementId: Long): Boolean {
        val q = entityManager.createNamedQuery("UserAchievementDto.exists")
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        q.setParameter("achievementId", achievementId)
        val count = (q.singleResult as? Number)?.toLong() ?: 0L
        return count > 0
    }

    override fun recordUnlock(unlock: UserAchievementDto): UserAchievementDto {
        entityManager.persist(unlock)
        entityManager.flush()
        return unlock
    }

    override fun getProgress(
        discordId: Long,
        guildId: Long,
        achievementId: Long
    ): AchievementProgressDto? {
        val q: TypedQuery<AchievementProgressDto> =
            entityManager.createNamedQuery("AchievementProgressDto.get", AchievementProgressDto::class.java)
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        q.setParameter("achievementId", achievementId)
        return q.resultList.firstOrNull()
    }

    override fun listProgressByUser(discordId: Long, guildId: Long): List<AchievementProgressDto> {
        val q: TypedQuery<AchievementProgressDto> =
            entityManager.createNamedQuery("AchievementProgressDto.getByUser", AchievementProgressDto::class.java)
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun upsertProgress(row: AchievementProgressDto): AchievementProgressDto {
        val existing = getProgress(row.discordId, row.guildId, row.achievementId)
        return if (existing == null) {
            entityManager.persist(row)
            entityManager.flush()
            row
        } else {
            existing.progress = row.progress
            existing.updatedAt = row.updatedAt
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }
}

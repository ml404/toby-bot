package database.persistence.guild.impl

import database.dto.AchievementDto
import database.dto.AchievementProgressDto
import database.dto.UserAchievementDto
import database.persistence.guild.AchievementPersistence
import database.persistence.guild.ProgressByCodeRow
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import database.persistence.saveOrMerge

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

    override fun save(achievement: AchievementDto): AchievementDto =
        entityManager.saveOrMerge(achievement, isNew = { it.id == null })

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
        // Find by composite (discordId, guildId, achievementId); merge mutates the
        // existing row in place to preserve its surrogate id, otherwise insert new.
        val existing = getProgress(row.discordId, row.guildId, row.achievementId)
        val target = existing?.apply {
            progress = row.progress
            updatedAt = row.updatedAt
        } ?: row
        return entityManager.saveOrMerge(target, isNew = { existing == null })
    }

    override fun progressByCodesForGuild(
        guildId: Long,
        codes: Collection<String>,
    ): List<ProgressByCodeRow> {
        if (codes.isEmpty()) return emptyList()
        // One pass: join progress + catalog, filter by code, keep non-zero so the
        // result set scales with active winners rather than members × codes.
        val jpql = "select p.discordId, a.code, p.progress " +
            "from AchievementProgressDto p, AchievementDto a " +
            "where a.id = p.achievementId " +
            "and p.guildId = :guildId " +
            "and a.code in :codes " +
            "and p.progress > 0"
        val query = entityManager.createQuery(jpql)
        query.setParameter("guildId", guildId)
        query.setParameter("codes", codes)
        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Array<Any?>>
        return rows.map { cols ->
            ProgressByCodeRow(
                discordId = (cols[0] as Number).toLong(),
                code = cols[1] as String,
                progress = (cols[2] as Number).toLong(),
            )
        }
    }
}

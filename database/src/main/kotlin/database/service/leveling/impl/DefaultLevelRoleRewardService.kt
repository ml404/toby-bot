package database.service.leveling.impl

import database.dto.LevelRoleRewardDto
import database.persistence.LevelRoleRewardPersistence
import database.service.leveling.LevelRoleRewardService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class DefaultLevelRoleRewardService @Autowired constructor(
    private val persistence: LevelRoleRewardPersistence
) : LevelRoleRewardService {
    override fun listForGuild(guildId: Long): List<LevelRoleRewardDto> =
        persistence.listForGuild(guildId)

    override fun get(guildId: Long, level: Int): LevelRoleRewardDto? =
        persistence.get(guildId, level)

    override fun listInRange(
        guildId: Long,
        fromExclusive: Int,
        toInclusive: Int
    ): List<LevelRoleRewardDto> = persistence.listInRange(guildId, fromExclusive, toInclusive)

    override fun upsert(reward: LevelRoleRewardDto): LevelRoleRewardDto = persistence.upsert(reward)

    override fun delete(guildId: Long, level: Int) = persistence.delete(guildId, level)
}

package database.service.impl

import database.dto.TeamPresetDto
import database.persistence.TeamPresetPersistence
import database.service.TeamPresetService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultTeamPresetService : TeamPresetService {
    @Autowired
    private lateinit var teamPresetPersistence: TeamPresetPersistence

    @Cacheable(value = ["team-presets"], key = "'guild-' + #guildId")
    override fun listForGuild(guildId: Long): List<TeamPresetDto> =
        teamPresetPersistence.listByGuild(guildId)

    override fun getByName(guildId: Long, name: String): TeamPresetDto? =
        teamPresetPersistence.getByGuildAndName(guildId, name)

    override fun getById(id: Long): TeamPresetDto? = teamPresetPersistence.getById(id)

    @CacheEvict(value = ["team-presets"], allEntries = true)
    override fun upsertPreset(
        guildId: Long,
        name: String,
        memberIds: List<Long>,
        createdByDiscordId: Long,
    ): TeamPresetDto {
        val trimmedName = name.trim()
        val existing = teamPresetPersistence.getByGuildAndName(guildId, trimmedName)
        return if (existing == null) {
            val dto = TeamPresetDto(
                guildId = guildId,
                name = trimmedName,
                createdByDiscordId = createdByDiscordId,
            )
            dto.memberIdList = memberIds.distinct()
            teamPresetPersistence.save(dto)
        } else {
            existing.name = trimmedName
            existing.memberIdList = memberIds.distinct()
            existing.updatedAt = Instant.now()
            teamPresetPersistence.update(existing)
        }
    }

    @CacheEvict(value = ["team-presets"], allEntries = true)
    override fun deletePreset(id: Long) {
        teamPresetPersistence.deleteById(id)
    }

    @CacheEvict(value = ["team-presets"], allEntries = true)
    override fun deleteAllForGuild(guildId: Long) {
        teamPresetPersistence.deleteAllByGuild(guildId)
    }

    @CacheEvict(value = ["team-presets"], allEntries = true)
    override fun clearCache() {
    }
}

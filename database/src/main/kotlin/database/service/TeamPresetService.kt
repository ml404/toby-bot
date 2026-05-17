package database.service

import database.dto.TeamPresetDto

interface TeamPresetService {
    fun listForGuild(guildId: Long): List<TeamPresetDto>
    fun getByName(guildId: Long, name: String): TeamPresetDto?
    fun getById(id: Long): TeamPresetDto?

    /**
     * Create or overwrite by `(guildId, lowercase name)`. If a preset
     * with the same name already exists for the guild, its member list
     * is replaced and [TeamPresetDto.updatedAt] bumps. Returns the
     * persisted DTO.
     */
    fun upsertPreset(
        guildId: Long,
        name: String,
        memberIds: List<Long>,
        createdByDiscordId: Long,
    ): TeamPresetDto

    fun deletePreset(id: Long)
    fun deleteAllForGuild(guildId: Long)
    fun clearCache()
}

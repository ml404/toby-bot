package database.persistence

import database.dto.MusicPlaylistDto

interface MusicPlaylistPersistence {
    fun listByGuild(guildId: Long): List<MusicPlaylistDto>
    fun listByGuildAndOwner(guildId: Long, ownerDiscordId: Long): List<MusicPlaylistDto>
    fun getByGuildOwnerAndName(guildId: Long, ownerDiscordId: Long, name: String): MusicPlaylistDto?
    fun getById(id: Long): MusicPlaylistDto?
    fun save(dto: MusicPlaylistDto): MusicPlaylistDto
    fun update(dto: MusicPlaylistDto): MusicPlaylistDto
    fun deleteById(id: Long)
}

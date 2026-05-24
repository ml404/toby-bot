package database.service.music.impl

import database.dto.MusicPlaylistDto
import database.dto.MusicPlaylistItemDto
import database.persistence.music.MusicPlaylistPersistence
import database.service.music.MusicPlaylistService
import database.service.music.MusicPlaylistService.PlaylistItemInput
import database.service.music.MusicPlaylistService.PlaylistNameTakenException
import org.springframework.stereotype.Service

@Service
class DefaultMusicPlaylistService(
    private val persistence: MusicPlaylistPersistence,
) : MusicPlaylistService {

    override fun listForGuild(guildId: Long): List<MusicPlaylistDto> =
        persistence.listByGuild(guildId)

    override fun listForUserInGuild(guildId: Long, ownerDiscordId: Long): List<MusicPlaylistDto> =
        persistence.listByGuildAndOwner(guildId, ownerDiscordId)

    override fun getById(id: Long): MusicPlaylistDto? = persistence.getById(id)

    override fun create(
        guildId: Long,
        ownerDiscordId: Long,
        name: String,
        items: List<PlaylistItemInput>,
    ): MusicPlaylistDto {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Playlist name cannot be blank" }
        require(trimmed.length <= 80) { "Playlist name must be at most 80 characters" }
        require(items.isNotEmpty()) { "Playlist must contain at least one track" }

        if (persistence.getByGuildOwnerAndName(guildId, ownerDiscordId, trimmed) != null) {
            throw PlaylistNameTakenException("A playlist named '$trimmed' already exists for this user in this guild")
        }

        val playlist = MusicPlaylistDto(
            guildId = guildId,
            ownerDiscordId = ownerDiscordId,
            name = trimmed,
        )
        items.forEachIndexed { index, input ->
            val item = MusicPlaylistItemDto(
                playlist = playlist,
                position = index,
                identifier = input.identifier,
                title = input.title,
                author = input.author,
                durationMs = input.durationMs,
                sourceName = input.sourceName,
            )
            playlist.items.add(item)
        }
        return persistence.save(playlist)
    }

    override fun deleteById(id: Long) {
        persistence.deleteById(id)
    }
}

package database.service.music.impl

import database.dto.music.MusicPlaylistDto
import database.dto.music.MusicPlaylistItemDto
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
        require(items.isNotEmpty()) { "Playlist must contain at least one track" }
        val trimmed = validatedName(name)
        requireNameFree(guildId, ownerDiscordId, trimmed)

        val playlist = MusicPlaylistDto(
            guildId = guildId,
            ownerDiscordId = ownerDiscordId,
            name = trimmed,
        )
        items.forEachIndexed { index, input ->
            playlist.items.add(input.toItem(playlist, index))
        }
        return persistence.save(playlist)
    }

    override fun createEmpty(
        guildId: Long,
        ownerDiscordId: Long,
        name: String,
    ): MusicPlaylistDto {
        val trimmed = validatedName(name)
        requireNameFree(guildId, ownerDiscordId, trimmed)
        return persistence.save(
            MusicPlaylistDto(guildId = guildId, ownerDiscordId = ownerDiscordId, name = trimmed),
        )
    }

    override fun addItem(playlistId: Long, item: PlaylistItemInput): MusicPlaylistDto? {
        val playlist = persistence.getById(playlistId) ?: return null
        playlist.items.add(item.toItem(playlist, playlist.items.size))
        resequence(playlist)
        return persistence.update(playlist)
    }

    override fun removeItem(playlistId: Long, itemId: Long): MusicPlaylistDto? {
        val playlist = persistence.getById(playlistId) ?: return null
        val removed = playlist.items.removeIf { it.id == itemId }
        if (!removed) return null
        resequence(playlist)
        return persistence.update(playlist)
    }

    override fun reorderItems(playlistId: Long, fromIndex: Int, toIndex: Int): MusicPlaylistDto? {
        val playlist = persistence.getById(playlistId) ?: return null
        val ordered = playlist.items.sortedBy { it.position }.toMutableList()
        if (fromIndex !in ordered.indices || toIndex !in ordered.indices) return null
        if (fromIndex != toIndex) {
            ordered.add(toIndex, ordered.removeAt(fromIndex))
        }
        // Reassign positions on the existing item entities — we deliberately
        // don't clear()/re-add the collection, which under orphanRemoval would
        // delete then re-insert every row. Only the position column changes.
        ordered.forEachIndexed { index, item -> item.position = index }
        return persistence.update(playlist)
    }

    override fun rename(playlistId: Long, newName: String): MusicPlaylistDto? {
        val playlist = persistence.getById(playlistId) ?: return null
        val trimmed = validatedName(newName)
        val clash = persistence.getByGuildOwnerAndName(playlist.guildId, playlist.ownerDiscordId, trimmed)
        if (clash != null && clash.id != playlist.id) {
            throw PlaylistNameTakenException(
                "A playlist named '$trimmed' already exists for this user in this guild",
            )
        }
        playlist.name = trimmed
        return persistence.update(playlist)
    }

    override fun deleteById(id: Long) {
        persistence.deleteById(id)
    }

    private fun validatedName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Playlist name cannot be blank" }
        require(trimmed.length <= 80) { "Playlist name must be at most 80 characters" }
        return trimmed
    }

    private fun requireNameFree(guildId: Long, ownerDiscordId: Long, name: String) {
        if (persistence.getByGuildOwnerAndName(guildId, ownerDiscordId, name) != null) {
            throw PlaylistNameTakenException(
                "A playlist named '$name' already exists for this user in this guild",
            )
        }
    }

    /** Re-number positions 0..n by current position order, without
        reordering the JPA collection itself (avoids orphan churn). */
    private fun resequence(playlist: MusicPlaylistDto) {
        playlist.items.sortedBy { it.position }.forEachIndexed { index, item -> item.position = index }
    }

    private fun PlaylistItemInput.toItem(playlist: MusicPlaylistDto, position: Int) =
        MusicPlaylistItemDto(
            playlist = playlist,
            position = position,
            identifier = identifier,
            title = title,
            author = author,
            durationMs = durationMs,
            sourceName = sourceName,
        )
}

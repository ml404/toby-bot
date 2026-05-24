package database.service.music

import database.dto.music.MusicPlaylistDto

interface MusicPlaylistService {

    data class PlaylistItemInput(
        val identifier: String,
        val title: String?,
        val author: String?,
        val durationMs: Long?,
        val sourceName: String?,
    )

    fun listForGuild(guildId: Long): List<MusicPlaylistDto>
    fun listForUserInGuild(guildId: Long, ownerDiscordId: Long): List<MusicPlaylistDto>
    fun getById(id: Long): MusicPlaylistDto?

    /**
     * Create a new playlist for `(guildId, ownerDiscordId, name)`. Throws
     * [PlaylistNameTakenException] if the user already has one with that name in this guild.
     */
    fun create(
        guildId: Long,
        ownerDiscordId: Long,
        name: String,
        items: List<PlaylistItemInput>,
    ): MusicPlaylistDto

    fun deleteById(id: Long)

    class PlaylistNameTakenException(message: String) : RuntimeException(message)
}

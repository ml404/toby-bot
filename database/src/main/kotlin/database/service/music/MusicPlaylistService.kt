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

    /**
     * Create an empty playlist so it can be curated track-by-track from the
     * web UI without ever touching the live queue. Same name validation /
     * uniqueness rules as [create].
     */
    fun createEmpty(
        guildId: Long,
        ownerDiscordId: Long,
        name: String,
    ): MusicPlaylistDto

    /** Append a track to the end of the playlist. Returns null if it doesn't exist. */
    fun addItem(playlistId: Long, item: PlaylistItemInput): MusicPlaylistDto?

    /**
     * Remove the item with [itemId] from the playlist and re-sequence the
     * remaining positions. Returns null if the playlist or item doesn't exist.
     */
    fun removeItem(playlistId: Long, itemId: Long): MusicPlaylistDto?

    /**
     * Move the item at [fromIndex] to [toIndex] (positions re-sequenced).
     * Returns null if the playlist is missing or either index is out of range.
     */
    fun reorderItems(playlistId: Long, fromIndex: Int, toIndex: Int): MusicPlaylistDto?

    /**
     * Rename the playlist. Throws [PlaylistNameTakenException] if the owner
     * already has another playlist with that name in the guild. Returns null
     * if the playlist doesn't exist.
     */
    fun rename(playlistId: Long, newName: String): MusicPlaylistDto?

    fun deleteById(id: Long)

    class PlaylistNameTakenException(message: String) : RuntimeException(message)
}

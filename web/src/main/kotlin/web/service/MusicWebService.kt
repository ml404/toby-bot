package web.service

import core.music.MusicControlGateway
import core.music.MusicControlGateway.GuildPlayerState
import core.music.MusicControlGateway.LoadResult
import core.music.MusicControlGateway.TrackInfo
import database.service.music.MusicPlaylistService
import database.service.music.MusicPlaylistService.PlaylistItemInput
import database.service.user.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import web.util.GuildMembership

@Service
class MusicWebService(
    private val gateway: MusicControlGateway,
    private val playlistService: MusicPlaylistService,
    private val jda: JDA,
    private val introWebService: IntroWebService,
    private val membership: GuildMembership,
    private val userService: UserService,
) {
    data class GuildCardView(
        val id: String,
        val name: String,
        val iconUrl: String?,
        val nowPlayingTitle: String?,
    )

    data class PlaylistSummary(
        val id: Long,
        val name: String,
        val ownerDiscordId: Long,
        val trackCount: Int,
    )

    data class PlaylistItemView(
        val id: Long,
        val title: String?,
        val author: String?,
        val durationMs: Long?,
        val sourceName: String?,
        val uri: String?,
    )

    /** Full playlist contents for the web editor. [canEdit] is true only for
        the owner — other guild members can view a playlist but not change it. */
    data class PlaylistDetail(
        val id: Long,
        val name: String,
        val ownerDiscordId: Long,
        val canEdit: Boolean,
        val items: List<PlaylistItemView>,
    )

    data class PlaylistMutation(val ok: Boolean, val detail: PlaylistDetail? = null, val message: String? = null)

    fun getGuildName(guildId: Long): String? = jda.getGuildById(guildId)?.name

    fun listGuildsForUser(accessToken: String, discordId: Long): List<GuildCardView> {
        return introWebService.getMutualGuilds(accessToken).mapNotNull { info ->
            val guildId = info.id.toLongOrNull() ?: return@mapNotNull null
            val now = gateway.getState(guildId)?.nowPlaying?.title
            GuildCardView(info.id, info.name, info.iconUrl, now)
        }.sortedBy { it.name.lowercase() }
    }

    fun getState(guildId: Long): GuildPlayerState? {
        val raw = gateway.getState(guildId) ?: return null
        val guild = jda.getGuildById(guildId) ?: return raw
        return raw.copy(
            nowPlaying = raw.nowPlaying?.let { enrichRequester(it, guild) },
            queue = raw.queue.map { enrichRequester(it, guild) },
        )
    }

    /**
     * Fill in the requester's display name + avatar URL from the guild's
     * cached members. Returns the input unchanged when there's no requester
     * id (e.g. preview / removed-track results) or the member has left the
     * guild and isn't cached.
     */
    fun enrichRequester(track: TrackInfo, guildId: Long): TrackInfo {
        val guild = jda.getGuildById(guildId) ?: return track
        return enrichRequester(track, guild)
    }

    private fun enrichRequester(track: TrackInfo, guild: net.dv8tion.jda.api.entities.Guild): TrackInfo {
        val requesterId = track.requesterDiscordId ?: return track
        val member = guild.getMemberById(requesterId) ?: return track
        return track.copy(
            requesterDisplayName = member.effectiveName,
            requesterAvatarUrl = member.effectiveAvatarUrl,
        )
    }

    fun load(guildId: Long, query: String, discordId: Long): LoadResult =
        gateway.load(guildId, query.trim(), discordId)

    fun search(guildId: Long, query: String, limit: Int = 10): List<TrackInfo> =
        gateway.search(guildId, query.trim(), limit)

    fun pause(guildId: Long): Boolean = gateway.pause(guildId)
    fun resume(guildId: Long): Boolean = gateway.resume(guildId)
    fun skip(guildId: Long, count: Int): Boolean = gateway.skip(guildId, count)
    fun stop(guildId: Long): Boolean = gateway.stop(guildId)

    fun setVolume(guildId: Long, volume: Int): Boolean = gateway.setVolume(guildId, volume)
    fun seek(guildId: Long, positionMs: Long): Boolean = gateway.seek(guildId, positionMs)
    fun setLooping(guildId: Long, looping: Boolean): Boolean = gateway.setLooping(guildId, looping)

    fun reorderQueue(guildId: Long, fromIndex: Int, toIndex: Int): Boolean =
        gateway.reorderQueue(guildId, fromIndex, toIndex)

    fun removeFromQueue(guildId: Long, index: Int): TrackInfo? =
        gateway.removeFromQueue(guildId, index)

    fun listPlaylistsForGuild(guildId: Long): List<PlaylistSummary> =
        playlistService.listForGuild(guildId).map {
            PlaylistSummary(
                id = it.id ?: -1L,
                name = it.name,
                ownerDiscordId = it.ownerDiscordId,
                trackCount = it.items.size,
            )
        }

    /**
     * Snapshot the current queue (including the now-playing track at position 0)
     * into a new playlist owned by [discordId].
     */
    fun saveCurrentQueueAsPlaylist(guildId: Long, discordId: Long, name: String): Long {
        val state = gateway.getState(guildId)
            ?: throw IllegalStateException("Player state unavailable for guild $guildId")
        val tracks = buildList {
            state.nowPlaying?.let { add(it) }
            addAll(state.queue)
        }
        if (tracks.isEmpty()) {
            throw IllegalArgumentException("Nothing to save — queue is empty.")
        }
        val items = tracks.map { it.toPlaylistInput() }
        val saved = playlistService.create(guildId, discordId, name, items)
        return saved.id ?: -1L
    }

    /** Create an empty playlist so it can be curated track-by-track without
        playing anything. Returns the new playlist's id. */
    fun createEmptyPlaylist(guildId: Long, discordId: Long, name: String): Long =
        playlistService.createEmpty(guildId, discordId, name).id ?: -1L

    /**
     * Full contents of a playlist for the editor. Any guild member may view;
     * `canEdit` flags whether the requester owns it. Returns null when the
     * playlist is missing or belongs to another guild.
     */
    fun getPlaylistDetail(guildId: Long, playlistId: Long, discordId: Long): PlaylistDetail? {
        val playlist = playlistService.getById(playlistId) ?: return null
        if (playlist.guildId != guildId) return null
        return playlist.toDetail(discordId)
    }

    /** Append a resolved track to a playlist the requester owns. */
    fun addTrackToPlaylist(
        guildId: Long,
        playlistId: Long,
        discordId: Long,
        input: PlaylistItemInput,
    ): PlaylistMutation {
        ownedPlaylistOrError(guildId, playlistId, discordId)?.let { return it }
        if (input.identifier.isBlank()) return PlaylistMutation(false, message = "Track is missing an identifier.")
        val updated = playlistService.addItem(playlistId, input) ?: return notFound()
        return PlaylistMutation(true, updated.toDetail(discordId))
    }

    fun removeTrackFromPlaylist(guildId: Long, playlistId: Long, discordId: Long, itemId: Long): PlaylistMutation {
        ownedPlaylistOrError(guildId, playlistId, discordId)?.let { return it }
        val updated = playlistService.removeItem(playlistId, itemId)
            ?: return PlaylistMutation(false, message = "Track not found in playlist.")
        return PlaylistMutation(true, updated.toDetail(discordId))
    }

    fun reorderPlaylist(guildId: Long, playlistId: Long, discordId: Long, from: Int, to: Int): PlaylistMutation {
        ownedPlaylistOrError(guildId, playlistId, discordId)?.let { return it }
        val updated = playlistService.reorderItems(playlistId, from, to)
            ?: return PlaylistMutation(false, message = "Invalid track positions.")
        return PlaylistMutation(true, updated.toDetail(discordId))
    }

    /** May throw [MusicPlaylistService.PlaylistNameTakenException]. */
    fun renamePlaylist(guildId: Long, playlistId: Long, discordId: Long, name: String): PlaylistMutation {
        ownedPlaylistOrError(guildId, playlistId, discordId)?.let { return it }
        val updated = playlistService.rename(playlistId, name) ?: return notFound()
        return PlaylistMutation(true, updated.toDetail(discordId))
    }

    /**
     * Returns a failure [PlaylistMutation] when the playlist is missing, in a
     * different guild, or not owned by [discordId]; null when the requester
     * may edit it.
     */
    private fun ownedPlaylistOrError(guildId: Long, playlistId: Long, discordId: Long): PlaylistMutation? {
        val playlist = playlistService.getById(playlistId) ?: return notFound()
        if (playlist.guildId != guildId) return notFound()
        if (playlist.ownerDiscordId != discordId) {
            return PlaylistMutation(false, message = "Only the owner can edit this playlist.")
        }
        return null
    }

    private fun notFound() = PlaylistMutation(false, message = "Playlist not found.")

    private fun database.dto.music.MusicPlaylistDto.toDetail(viewerDiscordId: Long) = PlaylistDetail(
        id = id ?: -1L,
        name = name,
        ownerDiscordId = ownerDiscordId,
        canEdit = ownerDiscordId == viewerDiscordId,
        items = items.sortedBy { it.position }.map {
            PlaylistItemView(
                id = it.id ?: -1L,
                title = it.title,
                author = it.author,
                durationMs = it.durationMs,
                sourceName = it.sourceName,
                uri = it.identifier,
            )
        },
    )

    /**
     * Re-load each track in the saved playlist via the gateway. Errors on
     * individual tracks are surfaced as failed entries in the result.
     */
    fun loadPlaylistIntoQueue(guildId: Long, playlistId: Long, discordId: Long): LoadPlaylistOutcome {
        val playlist = playlistService.getById(playlistId)
            ?: return LoadPlaylistOutcome(false, 0, 0, "Playlist not found")
        if (playlist.guildId != guildId) {
            return LoadPlaylistOutcome(false, 0, 0, "Playlist does not belong to this guild")
        }
        var loaded = 0
        var failed = 0
        for (item in playlist.items.sortedBy { it.position }) {
            val result = gateway.load(guildId, item.identifier, discordId)
            if (result.ok) loaded += result.tracksAdded else failed += 1
        }
        return LoadPlaylistOutcome(loaded > 0, loaded, failed, null)
    }

    data class LoadPlaylistOutcome(
        val ok: Boolean,
        val tracksLoaded: Int,
        val tracksFailed: Int,
        val message: String?,
    )

    fun deletePlaylist(playlistId: Long, requestingDiscordId: Long, isGuildAdmin: Boolean): Boolean {
        val playlist = playlistService.getById(playlistId) ?: return false
        if (playlist.ownerDiscordId != requestingDiscordId && !isGuildAdmin) return false
        playlistService.deleteById(playlistId)
        return true
    }

    fun isMember(discordId: Long, guildId: Long): Boolean = membership.isMember(discordId, guildId)

    /**
     * Mirror the slash-command gate (every music command checks
     * `requestingUserDto.musicPermission` before doing anything). Web-side
     * mutators are routed through this so revoking music permission via
     * `/adjust` or the moderation UI also cuts off the web dashboard.
     *
     * Read-only endpoints (`/state`, `/events`, `/playlists` GET) keep the
     * cheaper guild-member-only check — visibility doesn't change anything
     * audible in the voice channel.
     */
    fun canControlMusic(discordId: Long, guildId: Long): Boolean {
        if (!isMember(discordId, guildId)) return false
        val user = userService.getUserById(discordId, guildId) ?: return true
        return user.musicPermission
    }

    private fun TrackInfo.toPlaylistInput() = PlaylistItemInput(
        identifier = uri ?: identifier,
        title = title,
        author = author,
        durationMs = durationMs,
        sourceName = sourceName,
    )
}

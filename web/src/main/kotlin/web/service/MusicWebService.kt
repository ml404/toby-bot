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

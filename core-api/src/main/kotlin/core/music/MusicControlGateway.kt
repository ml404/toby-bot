package core.music

interface MusicControlGateway {

    data class TrackInfo(
        val identifier: String,
        val title: String,
        val author: String,
        val durationMs: Long,
        val uri: String?,
        val artworkUrl: String?,
        val sourceName: String?,
        val isStream: Boolean,
        val requesterDiscordId: Long?,
        // Populated by the web layer at fan-out time (the bot core only knows
        // the requester id; resolving to a Discord display name + avatar needs
        // a JDA handle that the bot doesn't bother carrying through every map
        // / SSE call). Bot-side consumers (e.g. the now-playing embed) resolve
        // these themselves via TrackScheduler.getRequesterId + Guild.getMemberById.
        val requesterDisplayName: String? = null,
        val requesterAvatarUrl: String? = null,
        // ~30s clip URL from the source's own metadata (Spotify, Apple Music,
        // Deezer, Yandex via LavaSrc's ExtendedAudioTrack). Null for sources
        // that don't publish a preview URL (YouTube, SoundCloud, Bandcamp,
        // HTTP, Local). The dashboard renders a Preview button only when
        // this is non-null.
        val previewUrl: String? = null,
    )

    data class VoiceMember(
        val discordId: Long,
        val displayName: String,
        val avatarUrl: String?,
        val isBot: Boolean,
        val isMuted: Boolean,
        val isDeafened: Boolean,
    )

    data class VoiceChannelInfo(
        val id: Long,
        val name: String,
        val members: List<VoiceMember>,
    )

    data class GuildPlayerState(
        val guildId: Long,
        val nowPlaying: TrackInfo?,
        val positionMs: Long,
        val paused: Boolean,
        val volume: Int,
        val looping: Boolean,
        val queue: List<TrackInfo>,
        val voiceChannelId: Long?,
        val voiceChannel: VoiceChannelInfo? = null,
    )

    data class LoadResult(
        val ok: Boolean,
        val tracksAdded: Int,
        val message: String?,
    )

    fun getState(guildId: Long): GuildPlayerState?

    /**
     * Resolve [query] through the lavaplayer source chain WITHOUT queueing
     * anything. Returns the candidate tracks the load would have produced —
     * for a `ytsearch:` query this is the top search results; for a direct
     * URL it's the resolved single track (or the tracks of a playlist URL).
     * Result list is capped at [limit] to keep payloads small.
     */
    fun search(guildId: Long, query: String, limit: Int = 10): List<TrackInfo>

    fun load(guildId: Long, query: String, requesterDiscordId: Long): LoadResult

    fun pause(guildId: Long): Boolean

    fun resume(guildId: Long): Boolean

    fun skip(guildId: Long, count: Int = 1): Boolean

    fun stop(guildId: Long): Boolean

    fun setVolume(guildId: Long, volume: Int): Boolean

    fun seek(guildId: Long, positionMs: Long): Boolean

    fun reorderQueue(guildId: Long, fromIndex: Int, toIndex: Int): Boolean

    fun removeFromQueue(guildId: Long, index: Int): TrackInfo?

    fun setLooping(guildId: Long, looping: Boolean): Boolean
}

package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import core.music.MusicControlGateway.TrackInfo

object TrackInfoMapper {
    fun toTrackInfo(track: AudioTrack, requesterId: Long?): TrackInfo {
        val info = track.info
        val sourceName = runCatching { track.sourceManager?.sourceName }.getOrNull()
        return TrackInfo(
            identifier = info.identifier,
            title = info.title,
            author = info.author,
            durationMs = track.duration,
            uri = info.uri,
            artworkUrl = info.artworkUrl,
            sourceName = sourceName,
            isStream = info.isStream,
            requesterDiscordId = requesterId,
        )
    }
}

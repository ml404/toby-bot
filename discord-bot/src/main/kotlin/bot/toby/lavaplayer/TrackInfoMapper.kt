package bot.toby.lavaplayer

import com.github.topi314.lavasrc.ExtendedAudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import core.music.MusicControlGateway.TrackInfo

object TrackInfoMapper {
    fun toTrackInfo(track: AudioTrack, requesterId: Long?): TrackInfo {
        val info = track.info
        val sourceName = runCatching { track.sourceManager?.sourceName }.getOrNull()
        // LavaSrc-loaded tracks (Spotify, Apple Music, Deezer, Yandex) carry a
        // ~30s preview clip URL from the source's metadata. Non-LavaSrc tracks
        // (YouTube, SoundCloud, Bandcamp, HTTP, Local) return null and the
        // dashboard hides the Preview button entirely.
        val previewUrl = runCatching { (track as? ExtendedAudioTrack)?.previewUrl }.getOrNull()
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
            previewUrl = previewUrl,
        )
    }
}

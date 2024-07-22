package toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.helpers.MusicPlayerHelper.deriveDeleteDelayFromTrack
import toby.helpers.MusicPlayerHelper.nowPlaying
import toby.helpers.MusicPlayerHelper.resetMessages
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

class TrackScheduler(val player: AudioPlayer) : AudioEventAdapter() {
    var queue: BlockingQueue<AudioTrack?> = LinkedBlockingQueue()
    var isLooping: Boolean = false
    var event: SlashCommandInteractionEvent? = null
    var deleteDelay: Int? = null
    private var previousVolume: Int? = null

    fun queue(track: AudioTrack, startPosition: Long, volume: Int) {
        event?.hook
            ?.sendMessage("Adding to queue: `${track.info.title}` by `${track.info.author}` starting at '${startPosition} ms' with volume '$volume'")
            ?.queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
        track.position = startPosition
        track.userData = volume
        synchronized(queue) {
            if (!player.startTrack(track, true)) {
                queue.offer(track)
            }
        }
    }

    fun queueTrackList(playList: AudioPlaylist, volume: Int) {
        event?.hook
            ?.sendMessage("Adding to queue: `${playList.tracks.size} tracks from playlist ${playList.name}`")
            ?.queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
        playList.tracks.forEach { track ->
            track.userData = volume
            if (!player.startTrack(track, true)) {
                queue.offer(track)
            }
        }
    }

    fun nextTrack() {
        synchronized(queue) {
            val track = queue.poll() ?: return
            player.volume = track.userData as Int
            player.startTrack(track, false)
        }
    }

    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        super.onTrackStart(player, track)
        player.volume = track.userData as Int
        event?.let { nowPlaying(it, PlayerManager.instance, deriveDeleteDelayFromTrack(track)) }
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        event?.guild?.idLong?.let { resetMessages(it) }
        if (endReason.mayStartNext) {
            if (isLooping) {
                player.startTrack(track.makeClone(), false)
            } else {
                PlayerManager.instance.isCurrentlyStoppable = true
                setVolumeToPrevious(player)
                queue.peek()?.let {
                    nextTrack()
                    event?.let { nowPlaying(it, PlayerManager.instance, deleteDelay) }
                }
            }
        }
    }

    private fun setVolumeToPrevious(player: AudioPlayer) {
        if (previousVolume != null && player.volume != previousVolume) {
            player.volume = previousVolume as Int
            event?.channel
                ?.sendMessageFormat("Setting volume back to '$previousVolume' \uD83D\uDD0A")
                ?.queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
        }
    }

    override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
        if (track.position == 0L) {
            event?.channel
                ?.sendMessage("Track ${track.info.title} got stuck, skipping.")
                ?.queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
            nextTrack()
        }
    }

    fun stopTrack(isStoppable: Boolean): Boolean {
        if (!isStoppable) return false
        player.stopTrack()
        setVolumeToPrevious(player)
        return true
    }

    fun setPreviousVolume(previousVolume: Int?) {
        this.previousVolume = previousVolume
    }
}

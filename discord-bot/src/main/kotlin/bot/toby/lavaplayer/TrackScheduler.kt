package bot.toby.lavaplayer

import bot.toby.helpers.MusicPlayerHelper.deriveDeleteDelayFromTrack
import bot.toby.helpers.MusicPlayerHelper.nowPlaying
import bot.toby.helpers.MusicPlayerHelper.resetMessages
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.TrackMarker
import com.sedmelluq.discord.lavaplayer.track.TrackMarkerHandler
import common.logging.DiscordLogger
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

class TrackScheduler(val player: AudioPlayer, private val guildId: Long, var deleteDelay: Int = 5) : AudioEventAdapter() {
    var queue: BlockingQueue<AudioTrack> = LinkedBlockingQueue(100)
    var isLooping: Boolean = false
    var event: SlashCommandInteractionEvent? = null
    private var previousVolume: Int? = null
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    fun queue(track: AudioTrack, startPosition: Long, endPosition: Long?, volume: Int) {
        logger.info("Adding ${track.info.title} by ${track.info.author} to the queue for guild $guildId")
        val endNote = endPosition?.let { " (clipped to $it ms)" }.orEmpty()
        event?.hook
            ?.sendMessage("Adding to queue: `${track.info.title}` by `${track.info.author}` starting at '${startPosition} ms'$endNote with volume '$volume'")
            ?.queue(invokeDeleteOnMessageResponse(deleteDelay))
        track.position = startPosition
        track.userData = volume
        if (endPosition != null && endPosition > startPosition) {
            track.setMarker(TrackMarker(endPosition) { state ->
                // Stop the clipped track on reaching the end marker. onTrackEnd then
                // advances the queue and restores the previous volume.
                if (state == TrackMarkerHandler.MarkerState.REACHED) {
                    logger.info("Clip end marker reached at $endPosition ms for ${track.info.title}, stopping track.")
                    track.stop()
                }
            })
        }
        synchronized(queue) {
            if (!player.startTrack(track, true)) {
                queue.offer(track)
            }
        }
    }

    // Back-compat overload for the pre-clip call sites; currently unused but keeps
    // the older signature compiling if anything else still calls it.
    fun queue(track: AudioTrack, startPosition: Long, volume: Int) = queue(track, startPosition, null, volume)

    fun queueTrackList(playList: AudioPlaylist, volume: Int) {
        logger.info { "Adding ${playList.name} to the queue for guild $guildId" }
        event?.hook
            ?.sendMessage("Adding to queue: `${playList.tracks.size} tracks from playlist ${playList.name}`")
            ?.queue(invokeDeleteOnMessageResponse(deleteDelay))
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
        logger.info { "${track.info.title} by ${track.info.author} started for guild $guildId" }
        super.onTrackStart(player, track)
        player.volume = track.userData as Int
        event?.let { nowPlaying(it, PlayerManager.instance, deriveDeleteDelayFromTrack(track)) }
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        event?.guild?.idLong.resetMessagesForGuildId()
        logger.info("${track.info.title} by ${track.info.author} ended")
        if (endReason.mayStartNext) {
            handleNextTrack(player, track)
        }
    }

    private fun handleNextTrack(player: AudioPlayer, track: AudioTrack) {
        if (isLooping) {
            player.startTrack(track.makeClone(), false)
        } else {
            PlayerManager.instance.isCurrentlyStoppable = true
            player.setVolumeToPrevious()
            queue.peek()?.let {
                nextTrack()
                event?.let { nowPlaying(it, PlayerManager.instance, deleteDelay) }
            }
        }
    }

    private fun AudioPlayer.setVolumeToPrevious() {
        previousVolume?.let { previousVol ->
            if (player.volume != previousVol) {
                this.volume = previousVol
                event?.channel
                    ?.sendMessageFormat("Setting volume back to '$previousVol' \uD83D\uDD0A")
                    ?.queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
        }
    }

    override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
        if (track.position == 0L) {
            event?.channel
                ?.sendMessage("Track ${track.info.title} got stuck, skipping.")
                ?.queue(invokeDeleteOnMessageResponse(deleteDelay))
            nextTrack()
        }
    }

    fun stopTrack(isStoppable: Boolean): Boolean {
        if (!isStoppable) return false
        player.stopTrack()
        player.setVolumeToPrevious()
        return true
    }

    fun setPreviousVolume(previousVolume: Int?) {
        this.previousVolume = previousVolume
    }

    private fun Long?.resetMessagesForGuildId() {
        this?.let { resetMessages(it) }
    }
}

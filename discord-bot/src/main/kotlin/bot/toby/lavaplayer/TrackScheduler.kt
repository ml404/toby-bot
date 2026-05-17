package bot.toby.lavaplayer

import bot.toby.helpers.MusicPlayerHelper.nowPlaying
import bot.toby.helpers.MusicPlayerHelper.resetMessages
import bot.toby.util.deriveDeleteDelayFromTrack
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.TrackMarker
import com.sedmelluq.discord.lavaplayer.track.TrackMarkerHandler
import common.logging.DiscordLogger
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.Command.Companion.replyAndDelete
import core.music.events.PauseStateChangedEvent
import core.music.events.QueueChangedEvent
import core.music.events.TrackEndedEvent
import core.music.events.TrackStartedEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

class TrackScheduler(val player: AudioPlayer, val guildId: Long, var deleteDelay: Int = 5) : AudioEventAdapter() {
    var queue: BlockingQueue<AudioTrack> = LinkedBlockingQueue(100)
    var isLooping: Boolean = false
    var event: SlashCommandInteractionEvent? = null
    private var previousVolume: Int? = null
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val trackClipBounds = ConcurrentHashMap<AudioTrack, Pair<Long, Long>>()
    private val trackRequesters = ConcurrentHashMap<AudioTrack, Long>()

    fun getRequesterId(track: AudioTrack): Long? = trackRequesters[track]

    fun queue(track: AudioTrack, startPosition: Long, endPosition: Long?, volume: Int, requesterId: Long? = null) {
        logger.info("Adding ${track.info.title} by ${track.info.author} to the queue for guild $guildId")
        val endNote = endPosition?.let { " (clipped to $it ms)" }.orEmpty()
        event?.hook?.replyAndDelete(
            "Adding to queue: `${track.info.title}` by `${track.info.author}` starting at '${startPosition} ms'$endNote with volume '$volume'",
            deleteDelay,
        )
        track.position = startPosition
        track.userData = volume
        requesterId?.let { trackRequesters[track] = it }
        if (endPosition != null && endPosition > startPosition) {
            trackClipBounds[track] = startPosition to endPosition
            track.setMarker(TrackMarker(endPosition) { state ->
                // Stop the clipped track on reaching the end marker via the player so
                // onTrackEnd reliably fires; that handler tears down the now-playing
                // embed and advances the queue / restores the previous volume.
                if (state == TrackMarkerHandler.MarkerState.REACHED) {
                    logger.info("Clip end marker reached at $endPosition ms for ${track.info.title}, stopping track.")
                    player.stopTrack()
                }
            })
        }
        synchronized(queue) {
            if (!player.startTrack(track, true)) {
                queue.offer(track)
            }
        }
        publishQueueChanged()
    }

    // Back-compat overload for the pre-clip call sites; currently unused but keeps
    // the older signature compiling if anything else still calls it.
    fun queue(track: AudioTrack, startPosition: Long, volume: Int) = queue(track, startPosition, null, volume, null)

    fun queueTrackList(playList: AudioPlaylist, volume: Int, requesterId: Long? = null) {
        logger.info { "Adding ${playList.name} to the queue for guild $guildId" }
        event?.hook?.replyAndDelete(
            "Adding to queue: `${playList.tracks.size} tracks from playlist ${playList.name}`",
            deleteDelay,
        )
        playList.tracks.forEach { track ->
            track.userData = volume
            requesterId?.let { trackRequesters[track] = it }
            if (!player.startTrack(track, true)) {
                queue.offer(track)
            }
        }
        publishQueueChanged()
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
        val (clipStart, clipEnd) = trackClipBounds[track]?.let { it.first to it.second } ?: (null to null)
        event?.let { nowPlaying(it, PlayerManager.instance, deriveDeleteDelayFromTrack(track), clipStart, clipEnd) }
        SchedulerEvents.publish(TrackStartedEvent(guildId, TrackInfoMapper.toTrackInfo(track, trackRequesters[track])))
        publishQueueChanged()
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        trackClipBounds.remove(track)
        trackRequesters.remove(track)
        event?.guild?.idLong.resetMessagesForGuildId()
        logger.info("${track.info.title} by ${track.info.author} ended")
        SchedulerEvents.publish(TrackEndedEvent(guildId, endReason.name))
        if (endReason.mayStartNext) {
            handleNextTrack(player, track)
        }
    }

    override fun onPlayerPause(player: AudioPlayer) {
        super.onPlayerPause(player)
        SchedulerEvents.publish(PauseStateChangedEvent(guildId, true))
    }

    override fun onPlayerResume(player: AudioPlayer) {
        super.onPlayerResume(player)
        SchedulerEvents.publish(PauseStateChangedEvent(guildId, false))
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

    fun moveQueueItem(fromIndex: Int, toIndex: Int): Boolean {
        val moved = synchronized(queue) {
            val snapshot = queue.toMutableList()
            if (fromIndex < 0 || fromIndex >= snapshot.size) return@synchronized false
            if (toIndex < 0 || toIndex >= snapshot.size) return@synchronized false
            if (fromIndex == toIndex) return@synchronized true
            val item = snapshot.removeAt(fromIndex)
            snapshot.add(toIndex, item)
            queue.clear()
            snapshot.forEach { queue.offer(it) }
            true
        }
        if (moved) publishQueueChanged()
        return moved
    }

    fun removeQueueItem(index: Int): AudioTrack? {
        val removed = synchronized(queue) {
            val snapshot = queue.toMutableList()
            if (index < 0 || index >= snapshot.size) return@synchronized null
            val item = snapshot.removeAt(index)
            trackRequesters.remove(item)
            trackClipBounds.remove(item)
            queue.clear()
            snapshot.forEach { queue.offer(it) }
            item
        }
        if (removed != null) publishQueueChanged()
        return removed
    }

    internal fun publishQueueChanged() {
        val snapshot = synchronized(queue) {
            queue.toList()
        }
        val tracks = snapshot.map { TrackInfoMapper.toTrackInfo(it, trackRequesters[it]) }
        SchedulerEvents.publish(QueueChangedEvent(guildId, tracks))
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

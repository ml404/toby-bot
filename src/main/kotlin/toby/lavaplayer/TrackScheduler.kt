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
import java.util.function.Consumer

class TrackScheduler(val player: AudioPlayer) : AudioEventAdapter() {
    var queue: BlockingQueue<AudioTrack?> = LinkedBlockingQueue()
    var isLooping: Boolean = false
    lateinit var event: SlashCommandInteractionEvent
    var deleteDelay: Int? = null
    private var previousVolume: Int? = null

    fun queue(track: AudioTrack, startPosition: Long, volume: Int) {
        event.hook.sendMessage("Adding to queue: `")
            .addContent(track.info.title)
            .addContent("` by `")
            .addContent(track.info.author)
            .addContent("`")
            .addContent(String.format(" starting at '%s ms' with volume '%d'", track.position, volume))
            .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
        track.position = startPosition
        track.userData = volume
        synchronized(queue) {
            if (!player.startTrack(track, true)) {
                queue.offer(track)
            }
        }
    }

    fun queueTrackList(playList: AudioPlaylist, volume: Int) {
        val tracks = playList.tracks
        event.hook.sendMessage("Adding to queue: `")
            .addContent(tracks.size.toString())
            .addContent("` tracks from playlist `")
            .addContent(playList.name)
            .addContent("`")
            .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))

        tracks.forEach(Consumer { track: AudioTrack ->
            track.userData = volume
            val hasNotStarted = !player.startTrack(track, true)
            if (hasNotStarted) {
                queue.offer(track)
            }
        })
    }

    fun nextTrack() {
        synchronized(queue) {
            val track = checkNotNull(queue.poll())
            val volume = track.userData as Int
            player.volume = volume
            player.startTrack(track, false)
        }
    }

    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        super.onTrackStart(player, track)
        player.volume = (track.userData as Int)
        nowPlaying(event, PlayerManager.instance, deriveDeleteDelayFromTrack(track))
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        event.guild?.idLong?.let { resetMessages(it) }
        if (endReason.mayStartNext) {
            if (isLooping) {
                this.player.startTrack(track.makeClone(), false)
                return
            }
            val instance: PlayerManager = PlayerManager.instance
            instance.isCurrentlyStoppable = true
            if (previousVolume != null && player.volume != previousVolume) {
                player.volume = previousVolume as Int
                event.channel.sendMessageFormat(
                    "Setting volume back to '%d' \uD83D\uDD0A",
                    previousVolume
                ).queue(
                    invokeDeleteOnMessageResponse(
                        deleteDelay ?: 0
                    )
                )
            }
            val audioTrack = queue.peek()
            if (audioTrack != null) {
                nextTrack()
                if (event != null) nowPlaying(event, instance, deleteDelay)
            }
        }
    }

    override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
        if (track.position == 0L) {
            event.channel.sendMessage(String.format("Track %s got stuck, skipping.", track.info.title)).queue(
                invokeDeleteOnMessageResponse(
                    deleteDelay ?: 0
                )
            )
            nextTrack()
        }
    }

    fun stopTrack(isStoppable: Boolean): Boolean {
        return if (isStoppable) {
            player.stopTrack()
            true
        } else {
            false
        }
    }


    fun setPreviousVolume(previousVolume: Int?) {
        this.previousVolume = previousVolume
    }
}

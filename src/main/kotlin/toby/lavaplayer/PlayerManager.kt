package toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse

class PlayerManager {
    private val musicManagers: MutableMap<Long, GuildMusicManager> = HashMap()
    private val audioPlayerManager: AudioPlayerManager = DefaultAudioPlayerManager()
    var isCurrentlyStoppable: Boolean = true
    private var previousVolume: Int? = null


    init {
        audioPlayerManager.registerSourceManager(YoutubeAudioSourceManager())
        audioPlayerManager.registerSourceManager(TwitchStreamAudioSourceManager())
        audioPlayerManager.registerSourceManager(HttpAudioSourceManager())
        audioPlayerManager.registerSourceManager(LocalAudioSourceManager())

        AudioSourceManagers.registerRemoteSources(this.audioPlayerManager)
        AudioSourceManagers.registerLocalSource(this.audioPlayerManager)
    }

    fun getMusicManager(guild: Guild): GuildMusicManager {
        return musicManagers.computeIfAbsent(guild.idLong) {
            val guildMusicManager = GuildMusicManager(this.audioPlayerManager)
            guild.audioManager.sendingHandler = guildMusicManager.sendHandler
            guildMusicManager
        }
    }

    @Synchronized
    fun loadAndPlay(
        guild: Guild,
        event: SlashCommandInteractionEvent?,
        trackUrl: String,
        isSkippable: Boolean,
        deleteDelay: Int,
        startPosition: Long,
        volume: Int
    ) {
        val musicManager = this.getMusicManager(guild)
        this.isCurrentlyStoppable = isSkippable
        audioPlayerManager.loadItemOrdered(
            musicManager,
            trackUrl,
            getResultHandler(event, musicManager, trackUrl, startPosition, volume, deleteDelay)
        )
    }

    private fun getResultHandler(
        event: SlashCommandInteractionEvent?,
        musicManager: GuildMusicManager,
        trackUrl: String,
        startPosition: Long,
        volume: Int,
        deleteDelay: Int
    ): AudioLoadResultHandler {
        return object : AudioLoadResultHandler {
            private val scheduler: TrackScheduler = musicManager.scheduler

            override fun trackLoaded(track: AudioTrack) {
                event?.let { scheduler.event = it }
                scheduler.deleteDelay = deleteDelay
                scheduler.queue(track, startPosition, volume)
                scheduler.setPreviousVolume(previousVolume)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                event?.let { scheduler.event = it }
                scheduler.deleteDelay = deleteDelay
                scheduler.queueTrackList(playlist, volume)
            }

            override fun noMatches() {
                event?.hook?.sendMessageFormat("Nothing found for the link '%s'", trackUrl)?.queue(
                    invokeDeleteOnMessageResponse(deleteDelay)
                )
            }

            override fun loadFailed(exception: FriendlyException) {
                event?.hook?.sendMessageFormat("Could not play: %s", exception.message)?.queue(
                    invokeDeleteOnMessageResponse(deleteDelay)
                )
            }
        }
    }

    fun setPreviousVolume(previousVolume: Int?) {
        this.previousVolume = previousVolume
    }

    companion object {
        val instance: PlayerManager by lazy {
            PlayerManager()
        }
    }
}

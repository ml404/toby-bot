package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import dev.lavalink.youtube.YoutubeAudioSourceManager
import dev.lavalink.youtube.YoutubeSourceOptions
import dev.lavalink.youtube.clients.Android
import dev.lavalink.youtube.clients.Music
import dev.lavalink.youtube.clients.Tv
import dev.lavalink.youtube.clients.TvHtml5Simply
import dev.lavalink.youtube.clients.Web
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

private const val CIPHER_API_URL = "https://cipher.kikkia.dev/api"

private const val GOOGLE_REFRESH_TOKEN = "GOOGLE_REFRESH_TOKEN"

class PlayerManager(private val audioPlayerManager: AudioPlayerManager) {
    private val musicManagers: MutableMap<Long, GuildMusicManager> = HashMap()
    var isCurrentlyStoppable: Boolean = true
    private var previousVolume: Int? = null

    constructor() : this(DefaultAudioPlayerManager())

    init {
        val youtubeSourceOptions = YoutubeSourceOptions().setRemoteCipher(CIPHER_API_URL, "", "")
        val youtubeAudioSourceManager = YoutubeAudioSourceManager(youtubeSourceOptions, Tv(), TvHtml5Simply(), Web(), Music(), Android())
        val refreshToken = System.getenv(GOOGLE_REFRESH_TOKEN)?.takeIf { it.isNotBlank() }
        youtubeAudioSourceManager.useOauth2(refreshToken, refreshToken != null)

        audioPlayerManager.registerSourceManager(youtubeAudioSourceManager)
        audioPlayerManager.registerSourceManager(TwitchStreamAudioSourceManager())
        audioPlayerManager.registerSourceManager(HttpAudioSourceManager())
        audioPlayerManager.registerSourceManager(LocalAudioSourceManager())
        AudioSourceManagers.registerLocalSource(this.audioPlayerManager)
    }

    fun getMusicManager(guild: Guild): GuildMusicManager {
        return musicManagers.computeIfAbsent(guild.idLong) {
            val guildMusicManager = GuildMusicManager(this.audioPlayerManager, guild.idLong)
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
        volume: Int,
        endPosition: Long?
    ) {
        val musicManager = this.getMusicManager(guild)
        this.isCurrentlyStoppable = isSkippable
        audioPlayerManager.loadItemOrdered(
            musicManager,
            trackUrl,
            getResultHandler(event, musicManager, trackUrl, startPosition, endPosition, volume, deleteDelay)
        )
    }

    // Back-compat overload: previous 7-arg signature delegates to the new one with
    // no end marker. Keeps existing call sites and MockK stubs binary-compatible.
    @Synchronized
    fun loadAndPlay(
        guild: Guild,
        event: SlashCommandInteractionEvent?,
        trackUrl: String,
        isSkippable: Boolean,
        deleteDelay: Int,
        startPosition: Long,
        volume: Int
    ) = loadAndPlay(guild, event, trackUrl, isSkippable, deleteDelay, startPosition, volume, null)

    private fun getResultHandler(
        event: SlashCommandInteractionEvent?,
        musicManager: GuildMusicManager,
        trackUrl: String,
        startPosition: Long,
        endPosition: Long?,
        volume: Int,
        deleteDelay: Int
    ): AudioLoadResultHandler {
        return object : AudioLoadResultHandler {
            private val scheduler: TrackScheduler = musicManager.scheduler

            override fun trackLoaded(track: AudioTrack) {
                scheduler.event = event
                scheduler.deleteDelay = deleteDelay
                scheduler.queue(track, startPosition, endPosition, volume)
                scheduler.setPreviousVolume(previousVolume)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                scheduler.event = event
                scheduler.deleteDelay = deleteDelay
                scheduler.queueTrackList(playlist, volume)
            }

            override fun noMatches() {
                event?.hook?.sendMessageFormat("Nothing found for the link '%s'", trackUrl)?.queue(invokeDeleteOnMessageResponse(deleteDelay)
                )
            }

            override fun loadFailed(exception: FriendlyException) {
                event?.hook?.sendMessageFormat("Could not play: %s", exception.message)?.queue(invokeDeleteOnMessageResponse(deleteDelay)
                )
            }
        }
    }

    fun destroyMusicManager(guildId: Long) {
        musicManagers.remove(guildId)?.let { mgr ->
            mgr.audioPlayer.destroy()
            mgr.scheduler.queue.clear()
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

package bot.toby.lavaplayer

import com.github.topi314.lavasrc.applemusic.AppleMusicSourceManager
import com.github.topi314.lavasrc.deezer.DeezerAudioSourceManager
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver
import com.github.topi314.lavasrc.spotify.SpotifySourceManager
import com.github.topi314.lavasrc.yandexmusic.YandexMusicSourceManager
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.nico.NicoAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import common.logging.DiscordLogger
import core.command.Command.Companion.replyAndDelete
import dev.lavalink.youtube.YoutubeAudioSourceManager
import dev.lavalink.youtube.YoutubeSourceOptions
import dev.lavalink.youtube.clients.Android
import dev.lavalink.youtube.clients.Music
import dev.lavalink.youtube.clients.Tv
import dev.lavalink.youtube.clients.TvHtml5Simply
import dev.lavalink.youtube.clients.Web
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import java.util.function.Function

private const val CIPHER_API_URL = "https://cipher.kikkia.dev/api"

private const val GOOGLE_REFRESH_TOKEN = "GOOGLE_REFRESH_TOKEN"
private const val SPOTIFY_CLIENT_ID = "SPOTIFY_CLIENT_ID"
private const val SPOTIFY_CLIENT_SECRET = "SPOTIFY_CLIENT_SECRET"
private const val SPOTIFY_COUNTRY = "SPOTIFY_COUNTRY"
private const val APPLE_MUSIC_MEDIA_API_TOKEN = "APPLE_MUSIC_MEDIA_API_TOKEN"
private const val APPLE_MUSIC_COUNTRY = "APPLE_MUSIC_COUNTRY"
private const val DEEZER_MASTER_KEY = "DEEZER_MASTER_KEY"
private const val DEEZER_ARL = "DEEZER_ARL"
private const val YANDEX_ACCESS_TOKEN = "YANDEX_ACCESS_TOKEN"

private val logger: DiscordLogger = DiscordLogger.createLogger(PlayerManager::class.java)

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
        if (refreshToken != null) {
            logger.info { "YouTube OAuth2 enabled via $GOOGLE_REFRESH_TOKEN." }
        } else {
            logger.warn {
                "$GOOGLE_REFRESH_TOKEN not set — YouTube requests will be anonymous and more likely to be IP-blocked. " +
                        "See README for how to obtain a refresh token."
            }
        }

        audioPlayerManager.registerSourceManager(youtubeAudioSourceManager)
        audioPlayerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault())
        audioPlayerManager.registerSourceManager(BandcampAudioSourceManager())
        audioPlayerManager.registerSourceManager(VimeoAudioSourceManager())
        audioPlayerManager.registerSourceManager(NicoAudioSourceManager())
        audioPlayerManager.registerSourceManager(TwitchStreamAudioSourceManager())
        audioPlayerManager.registerSourceManager(HttpAudioSourceManager())
        audioPlayerManager.registerSourceManager(LocalAudioSourceManager())

        registerLavaSrcManagers()
    }

    private fun registerLavaSrcManagers() {
        val playerManagerSupplier = Function<Void, AudioPlayerManager> { audioPlayerManager }

        registerSpotify(playerManagerSupplier)
        registerAppleMusic(playerManagerSupplier)
        registerDeezer()
        registerYandex()
    }

    private fun registerSpotify(supplier: Function<Void, AudioPlayerManager>) {
        val id = envOrNull(SPOTIFY_CLIENT_ID)
        val secret = envOrNull(SPOTIFY_CLIENT_SECRET)
        if (id == null || secret == null) {
            warnDisabled("Spotify", SPOTIFY_CLIENT_ID, SPOTIFY_CLIENT_SECRET)
            return
        }
        val country = envOrNull(SPOTIFY_COUNTRY) ?: "US"
        audioPlayerManager.registerSourceManager(
            SpotifySourceManager(id, secret, country, supplier, defaultMirroringResolver()),
        )
        logger.info { "Spotify source manager registered (country=$country)." }
    }

    private fun registerAppleMusic(supplier: Function<Void, AudioPlayerManager>) {
        val token = envOrNull(APPLE_MUSIC_MEDIA_API_TOKEN) ?: run {
            warnDisabled("Apple Music", APPLE_MUSIC_MEDIA_API_TOKEN)
            return
        }
        val country = envOrNull(APPLE_MUSIC_COUNTRY) ?: "us"
        audioPlayerManager.registerSourceManager(
            AppleMusicSourceManager(token, country, supplier, defaultMirroringResolver()),
        )
        logger.info { "Apple Music source manager registered (country=$country)." }
    }

    private fun registerDeezer() {
        val key = envOrNull(DEEZER_MASTER_KEY) ?: run {
            warnDisabled("Deezer", DEEZER_MASTER_KEY)
            return
        }
        val arl = envOrNull(DEEZER_ARL)
        audioPlayerManager.registerSourceManager(DeezerAudioSourceManager(key, arl))
        logger.info { "Deezer source manager registered (arl=${if (arl != null) "set" else "absent"})." }
    }

    private fun registerYandex() {
        val token = envOrNull(YANDEX_ACCESS_TOKEN) ?: run {
            warnDisabled("Yandex Music", YANDEX_ACCESS_TOKEN)
            return
        }
        audioPlayerManager.registerSourceManager(YandexMusicSourceManager(token))
        logger.info { "Yandex Music source manager registered." }
    }

    private fun envOrNull(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun warnDisabled(sourceName: String, vararg requiredEnv: String) {
        logger.warn { "${requiredEnv.joinToString(" / ")} not set — $sourceName disabled." }
    }

    // Shared search-provider chain used by sources that can't stream directly
    // (Spotify, Apple Music) — look up by ISRC first, fall back to a free-text
    // YouTube query.
    private fun defaultMirroringResolver() =
        DefaultMirroringAudioTrackResolver(arrayOf("ytsearch:\"%ISRC%\"", "ytsearch:%QUERY%"))

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
            private val requesterId: Long? = event?.member?.idLong

            override fun trackLoaded(track: AudioTrack) {
                scheduler.event = event
                scheduler.deleteDelay = deleteDelay
                scheduler.queue(track, startPosition, endPosition, volume, requesterId)
                scheduler.setPreviousVolume(previousVolume)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                scheduler.event = event
                scheduler.deleteDelay = deleteDelay
                scheduler.queueTrackList(playlist, volume, requesterId)
            }

            override fun noMatches() {
                event?.hook?.replyAndDelete("Nothing found for the link '$trackUrl'", deleteDelay)
            }

            override fun loadFailed(exception: FriendlyException) {
                logger.error {
                    "Track load failed for url=$trackUrl severity=${exception.severity}\n" +
                            exception.stackTraceToString()
                }
                event?.hook?.replyAndDelete("Could not play: ${exception.message}", deleteDelay)
            }
        }
    }

    /**
     * Web/gateway entry point: load a track or playlist via a custom result handler,
     * bypassing the slash-command-specific reply hooks in [getResultHandler].
     */
    fun loadForExternal(
        guild: Guild,
        trackUrl: String,
        handler: AudioLoadResultHandler,
    ) {
        val musicManager = getMusicManager(guild)
        audioPlayerManager.loadItemOrdered(musicManager, trackUrl, handler)
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

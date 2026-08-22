package bot.toby.lavaplayer

import bot.toby.intro.IntroFailedEvent
import bot.toby.intro.IntroOwnership
import bot.toby.intro.IntroPlayedEvent
import bot.toby.intro.SourceOutageNoticedEvent
import com.github.topi314.lavasrc.applemusic.AppleMusicSourceManager
import com.github.topi314.lavasrc.deezer.DeezerAudioSourceManager
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver
import com.github.topi314.lavasrc.spotify.SpotifySourceManager
import com.github.topi314.lavasrc.yandexmusic.YandexMusicSourceManager
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
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
import common.intro.IntroHealth
import common.media.SourceOutage
import common.media.SourceOutageTracker
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
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

private const val LAVAPLAYER_ENABLE_SOUNDCLOUD = "LAVAPLAYER_ENABLE_SOUNDCLOUD"
private const val LAVAPLAYER_ENABLE_BANDCAMP = "LAVAPLAYER_ENABLE_BANDCAMP"
private const val LAVAPLAYER_ENABLE_VIMEO = "LAVAPLAYER_ENABLE_VIMEO"
private const val LAVAPLAYER_ENABLE_NICO = "LAVAPLAYER_ENABLE_NICO"
private const val LAVAPLAYER_ENABLE_TWITCH = "LAVAPLAYER_ENABLE_TWITCH"

private val logger: DiscordLogger = DiscordLogger.createLogger(PlayerManager::class.java)

class PlayerManager(
    private val audioPlayerManager: AudioPlayerManager,
    // Backs the transient-load retry backoff. Daemon so it never blocks JVM
    // shutdown; injectable so tests can run retries synchronously.
    private val retryExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "track-load-retry").apply { isDaemon = true }
    },
    // Injectable so tests can drive its windows without waiting them out.
    private val outageTracker: SourceOutageTracker = SourceOutageTracker(),
) {
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
        registerIfEnabled(LAVAPLAYER_ENABLE_SOUNDCLOUD, "SoundCloud") { SoundCloudAudioSourceManager.createDefault() }
        registerIfEnabled(LAVAPLAYER_ENABLE_BANDCAMP, "Bandcamp") { BandcampAudioSourceManager() }
        registerIfEnabled(LAVAPLAYER_ENABLE_VIMEO, "Vimeo") { VimeoAudioSourceManager() }
        registerIfEnabled(LAVAPLAYER_ENABLE_NICO, "Nico") { NicoAudioSourceManager() }
        registerIfEnabled(LAVAPLAYER_ENABLE_TWITCH, "Twitch") { TwitchStreamAudioSourceManager() }
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

    // Each Lavaplayer source manager eagerly allocates an Apache HTTP client +
    // connection pool at construction. On a 512 MB dyno that adds up, so the
    // non-core sources are opt-in via env flag. Set the flag to "true" to
    // re-enable.
    private inline fun registerIfEnabled(envFlag: String, sourceName: String, factory: () -> AudioSourceManager) {
        if (envOrNull(envFlag)?.equals("true", ignoreCase = true) == true) {
            audioPlayerManager.registerSourceManager(factory())
            logger.info { "$sourceName source manager registered ($envFlag=true)." }
        } else {
            logger.info { "$sourceName source manager skipped ($envFlag not set to 'true')." }
        }
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
            getResultHandler(event, musicManager, trackUrl, startPosition, endPosition, volume, deleteDelay, isIntro = false)
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

    /**
     * Load an intro track and preempt whatever is currently playing. The
     * preempted track is restored automatically once the intro ends.
     */
    /**
     * @param introId the stored intro row this playback is for. Threaded down
     *        so the outcome — played, or failed and why — can be recorded
     *        against it. Without it a broken intro is silent in both senses:
     *        nothing plays, and nothing anywhere records that nothing played.
     */
    @Synchronized
    fun loadAndPlayIntro(
        guild: Guild,
        event: SlashCommandInteractionEvent?,
        trackUrl: String,
        deleteDelay: Int,
        startPosition: Long,
        volume: Int,
        endPosition: Long?,
        introId: String? = null,
    ) {
        val musicManager = this.getMusicManager(guild)
        this.isCurrentlyStoppable = true
        audioPlayerManager.loadItemOrdered(
            musicManager,
            trackUrl,
            getResultHandler(
                event, musicManager, trackUrl, startPosition, endPosition, volume, deleteDelay,
                isIntro = true, introId = introId,
            )
        )
    }

    private fun getResultHandler(
        event: SlashCommandInteractionEvent?,
        musicManager: GuildMusicManager,
        trackUrl: String,
        startPosition: Long,
        endPosition: Long?,
        volume: Int,
        deleteDelay: Int,
        isIntro: Boolean = false,
        attempt: Int = 1,
        introId: String? = null,
    ): AudioLoadResultHandler {
        return object : AudioLoadResultHandler {
            private val scheduler: TrackScheduler = musicManager.scheduler
            private val requesterId: Long? = event?.member?.idLong

            override fun trackLoaded(track: AudioTrack) {
                // Deliberately no success recorded here. A load only proves the
                // source will talk to us about the track, not that it will hand
                // over the audio — and treating the two as the same thing is
                // what let a stream that died on every play keep reporting
                // itself healthy. Both success signals now come back from
                // TrackScheduler once audio has actually run.
                scheduler.event = event
                scheduler.deleteDelay = deleteDelay
                if (isIntro) {
                    scheduler.queueIntro(track, startPosition, endPosition, volume, requesterId, introId)
                } else {
                    scheduler.queue(track, startPosition, endPosition, volume, requesterId)
                }
                scheduler.setPreviousVolume(previousVolume)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                scheduler.event = event
                scheduler.deleteDelay = deleteDelay
                scheduler.queueTrackList(playlist, volume, requesterId)
            }

            override fun noMatches() {
                // A blocked lookup usually comes back as "no matches" rather
                // than an error, which is why a rate-limited bot spent the
                // outage telling people their perfectly good links didn't
                // exist.
                val outage = noteFailure(trackUrl, definite = false)
                if (outage) {
                    event?.hook?.replyAndDelete(OUTAGE_MESSAGE, deleteDelay)
                } else {
                    event?.hook?.replyAndDelete("Nothing found for the link '$trackUrl'", deleteDelay)
                }
                // On the intro path `event` is always null, so before this the
                // branch was a no-op and a deleted or private video simply
                // produced silence forever.
                introId?.let {
                    logger.warn { "Intro $it resolved nothing for url=$trackUrl" }
                    reportIntroFailure(it, "Nothing found at this link", outage)
                }
            }

            override fun loadFailed(exception: FriendlyException) {
                // COMMON = track-specific (e.g. video unavailable): retrying won't help.
                // SUSPICIOUS / FAULT are usually transient (rate-limit, IP block,
                // momentary source glitch) and frequently succeed on a second try —
                // exactly the cases users work around today by re-running /play.
                val transient = exception.severity == FriendlyException.Severity.SUSPICIOUS ||
                    exception.severity == FriendlyException.Severity.FAULT
                if (transient && attempt < MAX_LOAD_ATTEMPTS) {
                    val delayMs = LOAD_RETRY_BASE_MS * attempt
                    logger.warn {
                        "Track load failed for url=$trackUrl severity=${exception.severity}; " +
                            "retry $attempt/${MAX_LOAD_ATTEMPTS - 1} in ${delayMs}ms"
                    }
                    retryExecutor.schedule(
                        {
                            audioPlayerManager.loadItemOrdered(
                                musicManager,
                                trackUrl,
                                getResultHandler(
                                    event, musicManager, trackUrl, startPosition, endPosition,
                                    volume, deleteDelay, isIntro, attempt + 1, introId,
                                ),
                            )
                        },
                        delayMs,
                        TimeUnit.MILLISECONDS,
                    )
                    return
                }
                logger.error {
                    "Track load failed for url=$trackUrl severity=${exception.severity} after $attempt attempt(s)\n" +
                            exception.stackTraceToString()
                }
                // A message that names a block is enough on its own; anything
                // else only counts towards the correlation.
                val outage = noteFailure(trackUrl, definite = SourceOutage.looksLikeBlock(exception.message))
                if (outage) {
                    event?.hook?.replyAndDelete(OUTAGE_MESSAGE, deleteDelay)
                } else {
                    event?.hook?.replyAndDelete("Could not play: ${exception.message}", deleteDelay)
                }
                // Retries are exhausted by this point, so this is a real
                // failure of the source rather than a blip worth hiding.
                introId?.let {
                    reportIntroFailure(it, IntroHealth.normaliseReason(exception.message), outage)
                }
            }
        }
    }

    /**
     * Audio actually reached the listener.
     *
     * A successful *load* turned out to be no proof of this at all: during the
     * incident that prompted this, every load succeeded and every stream then
     * died, so the outage window was being wiped clean by the very requests
     * that were failing.
     *
     * @param introId set when the track was somebody's intro, which is the
     *   only thing that makes a play worth recording against a row.
     * @param uninterrupted whether the track reached its own end. Only that
     *   ends a declared outage: a track cut short was very likely streaming
     *   down a connection opened before the trouble started, so it says
     *   nothing about whether a *fresh* request would work. Intro preemption
     *   cuts a track short every time somebody joins voice, so accepting an
     *   interruption as proof would keep the busiest servers from ever
     *   declaring an outage. A play still counts against the intro's health
     *   either way — it was audible, which is all that row is asking.
     */
    fun reportPlaybackSuccess(introId: String?, uninterrupted: Boolean) {
        if (uninterrupted) {
            outageTracker.recordSuccess()
            // Audio played, so the episode is over as far as anyone can tell.
            // Arming the notice again here means a second outage is announced
            // rather than swallowed as a repeat of the first.
            outageAnnouncedGuilds.clear()
        }
        introId?.let { SchedulerEvents.publish(IntroPlayedEvent(it)) }
    }

    /**
     * A track died part-way through streaming.
     *
     * The load path has had this for a while; the streaming path had nothing
     * at all, so a source that resolved and then refused to hand over audio
     * was invisible to intro health, to the outage correlation and to the
     * channel. It is treated exactly like a load failure because the listener
     * cannot tell the two apart: either way they heard silence.
     *
     * @param sourceKey what failed, used to tell "one dead video" apart from
     *   "the host is refusing us" — so it must identify the track, not the guild.
     * @return whether another attempt is worth making — false during an
     *   outage, where a retry is a second request to a host already saying no.
     */
    fun reportPlaybackFailure(introId: String?, sourceKey: String, reason: String?): Boolean {
        val outage = noteFailure(sourceKey, definite = SourceOutage.looksLikeBlock(reason))
        introId?.let { reportIntroFailure(it, IntroHealth.normaliseReason(reason), outage) }
        return !outage
    }

    /**
     * Whether the audio source currently looks like it is refusing us.
     *
     * Bot-wide rather than per-guild: it describes YouTube's opinion of this
     * IP, not anything about a server. Read by `/introstats` so an admin
     * looking at a pile of "not playing" intros can tell a real problem from
     * an episode that will clear on its own.
     */
    fun isSourceRefusingRequests(): Boolean = outageTracker.isOutage()

    /** Guilds already told about the episode in progress. See [announceOutageOnce]. */
    private val outageAnnouncedGuilds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * Records a failed load and answers whether the host — rather than the
     * track — now looks like the problem. Logs the onset once, with the bit an
     * operator can act on.
     */
    private fun noteFailure(trackUrl: String, definite: Boolean): Boolean {
        val justDeclared = outageTracker.recordFailure(trackUrl, definite)
        if (justDeclared) {
            logger.error {
                "Audio source appears to be refusing us: ${SourceOutage.OUTAGE_AFTER_DISTINCT_FAILURES}+ distinct " +
                    "sources failed inside ${SourceOutage.WINDOW_MS / 1000}s (latest: $trackUrl). " +
                    "Intro health is paused until a load succeeds so nobody is told their working intro is broken. " +
                    "If this persists, check $GOOGLE_REFRESH_TOKEN — anonymous YouTube requests are far more likely " +
                    "to be IP-blocked."
            }
        }
        return outageTracker.isOutage()
    }

    /**
     * An intro failed to load. During an outage this deliberately publishes
     * nothing.
     *
     * The failure counter exists to find intros whose *source* has died, and
     * it drives a DM telling the owner to go and replace the link. Letting a
     * rate-limit episode feed it would mark every intro on every server broken
     * within two joins, DM everybody advice that doesn't apply, and have
     * [common.intro.IntroSelection] start skipping tracks that were never
     * anything but fine. Losing a couple of genuine failures to a false
     * negative is the cheaper mistake by a wide margin.
     */
    private fun reportIntroFailure(introId: String, reason: String, outage: Boolean) {
        if (outage) {
            logger.warn { "Not counting intro $introId's failure against its health: the source is refusing us." }
            announceOutageOnce(introId)
            return
        }
        SchedulerEvents.publish(IntroFailedEvent(introId, reason))
    }

    /**
     * Tell a server, once, that the silence is ours and not theirs.
     *
     * Not counting these failures is right — a rate-limit episode must not
     * mark everybody's working links dead — but it meant the one time every
     * intro on the server goes quiet was the one time nothing said why. Every
     * member-facing surface went on describing their intro as fine.
     *
     * Once per guild per episode, because during an outage this runs on every
     * single voice join. The latch clears when a track finally plays, which is
     * the same signal that ends the outage itself.
     */
    private fun announceOutageOnce(introId: String) {
        val guildId = IntroOwnership.guildIdOf(introId) ?: return
        if (!outageAnnouncedGuilds.add(guildId)) return
        logger.info { "Telling guild $guildId that the audio source is refusing us." }
        SchedulerEvents.publish(SourceOutageNoticedEvent(guildId))
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
            mgr.scheduler.clearQueue()
        }
    }

    fun setPreviousVolume(previousVolume: Int?) {
        this.previousVolume = previousVolume
    }

    companion object {
        /**
         * Shown instead of "nothing found" / "could not play" while the source
         * is refusing us, because both of those blame the listener's link for
         * something that has nothing to do with it. No mention of tokens or
         * IPs: that's the operator's problem and it's in the log.
         */
        const val OUTAGE_MESSAGE = "Can't reach the music source right now — it's refusing requests from TobyBot, " +
            "which isn't a problem with your link. This usually clears on its own; try again in a few minutes."

        /** Total load attempts before giving up (1 initial + retries). */
        const val MAX_LOAD_ATTEMPTS = 3

        /** Base backoff between transient-load retries; multiplied by the attempt number. */
        const val LOAD_RETRY_BASE_MS = 1_000L

        val instance: PlayerManager by lazy {
            PlayerManager()
        }
    }
}

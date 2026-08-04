package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import common.intro.IntroFade
import common.logging.DiscordLogger

/**
 * What the audio pipeline needs to know about a track to shape it.
 *
 * Narrow on purpose: [TrackScheduler] is the only production implementation,
 * but a factory that took the scheduler itself would drag an `AudioPlayer` and
 * a queue into every test of the filter chain.
 */
interface IntroTrackContext {

    /**
     * The track the player is on, for when the pipeline doesn't say which one
     * it is building for — which, in lavaplayer 2.2.6, is always.
     */
    fun currentTrack(): AudioTrack?

    /** The stored intro row [track] is playing, or null when it isn't an intro. */
    fun introIdFor(track: AudioTrack): String?

    /** How long [track] will play for as an intro, or null when that isn't known. */
    fun introPlaybackMsFor(track: AudioTrack): Long?

    /** True when [track] is music being restarted after an intro interrupted it. */
    fun isResumingTrack(track: AudioTrack): Boolean
}

/**
 * Builds the PCM chain for intro-related playback, and only for that.
 *
 * The factory is set once per guild player, so it sees every track the guild
 * plays — hour-long queued music included. Neither of the filters below has
 * anything to say about ordinary playback, and running them anyway would be
 * work on the audio thread for no one's benefit, so a track that is neither an
 * intro nor a resume gets an empty chain and the pipeline stays exactly as it
 * was before any of this existed.
 *
 * Chain order matters. [IntroLoudnessMeter] runs first so it measures the
 * track's own level; putting the fade ahead of it would fold the ramp into the
 * measurement and make every intro read as quieter than it is — which the
 * loudness correction would then dutifully compensate for on the next play.
 *
 * @param onMeasured receives `(introId, rms)` once an intro's chain closes.
 */
class IntroFilterFactory(
    private val context: IntroTrackContext,
    private val onMeasured: (String, Double) -> Unit,
) : PcmFilterFactory {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    /**
     * @param track which track the chain is for — **always null** in
     *        lavaplayer 2.2.6. `AudioProcessingContext` carries no track at
     *        all, and `UserProvidedAudioFilters` (its only call site) passes a
     *        literal null. Declaring this non-null, as the first version of
     *        this class did, put a Kotlin intrinsic null-check in the method
     *        prologue that threw on every single track — and because the
     *        pipeline is built whenever a filter factory is set, that was
     *        every track the bot played, surfacing as "Something went wrong
     *        when decoding the track".
     */
    override fun buildChain(
        track: AudioTrack?,
        format: AudioDataFormat,
        output: UniversalPcmAudioFilter,
    ): List<AudioFilter> = runCatching {
        // Nothing this class does is worth interrupting playback for. It sits
        // on the decode path, so anything it throws surfaces as a track that
        // won't play — which is exactly how it failed the first time.
        chainFor(track, format, output)
    }.getOrElse {
        logger.error("Failed to build the intro filter chain; continuing without it: ${it.message}")
        emptyList()
    }

    private fun chainFor(
        track: AudioTrack?,
        format: AudioDataFormat,
        output: UniversalPcmAudioFilter,
    ): List<AudioFilter> {
        // The pipeline is constructed for whatever the executor is running, so
        // the player's current track is the one being built for.
        val subject = track ?: context.currentTrack() ?: return emptyList()

        val introId = context.introIdFor(subject)
        if (introId != null) return introChain(subject, format, output, introId)
        if (context.isResumingTrack(subject)) return resumeChain(format, output)
        return emptyList()
    }

    /** Measure, then ramp in and out of the clip. */
    private fun introChain(
        track: AudioTrack,
        format: AudioDataFormat,
        output: UniversalPcmAudioFilter,
        introId: String,
    ): List<AudioFilter> {
        // Guarded separately from the outer catch: without a length there is
        // no end to fade *out* towards, but the fade in and the measurement
        // are both still worth having, so this degrades rather than dropping
        // the whole chain.
        val playbackMs = runCatching { context.introPlaybackMsFor(track) }.getOrNull()
        val fade = IntroFadeFilter(
            downstream = output,
            sampleRate = format.sampleRate,
            playbackMs = playbackMs,
            ramps = IntroFade.rampsFor(playbackMs),
        )
        val meter = IntroLoudnessMeter(fade) { rms ->
            runCatching { onMeasured(introId, rms) }.onFailure {
                // This runs on lavaplayer's audio thread. A throw here would
                // surface as a broken pipeline mid-playback, which is a
                // spectacularly bad trade for a statistic.
                logger.warn { "Failed to record measured loudness for intro $introId: ${it.message}" }
            }
        }
        // Signal order, head first: lavaplayer feeds the first element and the
        // last one feeds `output`.
        return listOf(meter, fade)
    }

    /**
     * Music coming back after an intro. Only a fade in: where it ends is the
     * track's own business, and it may well be preempted again before it gets
     * there.
     */
    private fun resumeChain(format: AudioDataFormat, output: UniversalPcmAudioFilter): List<AudioFilter> = listOf(
        IntroFadeFilter(
            downstream = output,
            sampleRate = format.sampleRate,
            playbackMs = null,
            ramps = IntroFade.rampsFor(null, fadeInMs = IntroFade.RESUME_FADE_IN_MS, fadeOutMs = 0),
        ),
    )
}

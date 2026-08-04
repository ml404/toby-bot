package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import common.logging.DiscordLogger

/**
 * Installs [IntroLoudnessMeter] on intro tracks, and only on intro tracks.
 *
 * The factory is set once per guild player, so it sees every track the guild
 * plays — hour-long queued music included. Measuring those would be pointless
 * work on the audio thread for a number nothing reads, so [introIdOf] gates
 * it: no intro id, no filter, and the chain stays empty exactly as it is
 * today for regular playback.
 *
 * @param introIdOf resolves the intro row id for a track, or null if the
 *        track isn't an intro. Backed by [TrackScheduler]'s intro bookkeeping.
 * @param onMeasured receives `(introId, rms)` once the track's chain closes.
 */
class IntroLoudnessFilterFactory(
    private val introIdOf: (AudioTrack) -> String?,
    private val onMeasured: (String, Double) -> Unit,
) : PcmFilterFactory {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun buildChain(
        track: AudioTrack,
        format: AudioDataFormat,
        output: UniversalPcmAudioFilter,
    ): List<AudioFilter> {
        val introId = runCatching { introIdOf(track) }.getOrNull() ?: return emptyList()
        // The returned list runs in signal order and its last element feeds
        // `output`; with a single filter the meter is both head and tail.
        return listOf(
            IntroLoudnessMeter(output) { rms ->
                runCatching { onMeasured(introId, rms) }.onFailure {
                    // This runs on lavaplayer's audio thread. A throw here
                    // would surface as a broken pipeline mid-playback, which
                    // is a spectacularly bad trade for a statistic.
                    logger.warn { "Failed to record measured loudness for intro $introId: ${it.message}" }
                }
            },
        )
    }
}

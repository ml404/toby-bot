package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import common.intro.IntroFade

/**
 * Applies [IntroFade]'s gain ramps to the samples passing through it.
 *
 * Sits in the user filter chain, which runs ahead of lavaplayer's own
 * `VolumePostProcessor` — so this composes with the player volume rather than
 * fighting it. A user's chosen intro volume still means what it meant; the
 * ramp only shapes the first and last few hundred milliseconds of it.
 *
 * Samples are scaled in place, which is what lavaplayer's own float filters do
 * (`Equalizer` included) and avoids allocating a buffer per 20ms of audio on
 * the audio thread.
 *
 * @param playbackMs how long this track will actually play for — the clip
 *        length for a clipped intro, the remaining track otherwise. Null means
 *        unknown, in which case only the fade in applies.
 */
class IntroFadeFilter(
    private val downstream: FloatPcmAudioFilter,
    private val sampleRate: Int,
    private val playbackMs: Long?,
    private val ramps: IntroFade.Ramps,
) : FloatPcmAudioFilter {

    /** Samples per channel seen since the start of playback (or the last seek). */
    private var samplesSeen = 0L

    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        if (length > 0) {
            applyRamp(input, offset, length)
            samplesSeen += length
        }
        downstream.process(input, offset, length)
    }

    private fun applyRamp(input: Array<FloatArray>, offset: Int, length: Int) {
        if (ramps.isNoOp || sampleRate <= 0) return

        // Two gain evaluations per buffer, interpolated across it, rather than
        // one per sample: a buffer is ~20ms, so holding a single gain for the
        // whole of it would turn a smooth ramp back into a staircase.
        val startGain = gainAfter(samplesSeen)
        val endGain = gainAfter(samplesSeen + length)
        if (startGain == 1f && endGain == 1f) return

        val step = (endGain - startGain) / length
        for (channel in input) {
            // Defensive: lavaplayer sizes these to the negotiated channel
            // count, but overrunning one here would be an
            // ArrayIndexOutOfBounds thrown from the audio thread, which takes
            // the whole track down.
            val end = minOf(offset + length, channel.size)
            var gain = startGain
            var i = offset
            while (i < end) {
                channel[i] *= gain
                gain += step
                i++
            }
        }
    }

    private fun gainAfter(samples: Long): Float =
        IntroFade.gainAt(samples * 1_000 / sampleRate, playbackMs, ramps)

    /**
     * A clip's start offset is applied as a seek, so elapsed time has to be
     * measured from there — otherwise a clip starting at 1:00 would be judged
     * a minute into its fade before its first sample arrived, and the fade in
     * would never happen.
     */
    override fun seekPerformed(requestedTime: Long, providedTime: Long) {
        samplesSeen = 0
    }

    override fun flush() {
        // Nothing buffered here — samples are scaled and forwarded as they arrive.
    }

    override fun close() {
        // Nothing to release.
    }
}

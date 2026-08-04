package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import common.intro.IntroLoudness

/**
 * A pass-through PCM filter that measures how loud an intro actually is.
 *
 * It sits in lavaplayer's user filter chain, ahead of `FinalPcmAudioFilter`
 * and therefore ahead of `VolumePostProcessor` — so the samples it sees are
 * the track's own level, not the level the listener hears. That's the whole
 * point: measuring post-volume would just tell us the number the user already
 * chose.
 *
 * Every sample is forwarded to [downstream] unmodified. This filter never
 * changes what anyone hears; the correction it enables is applied later, as
 * an ordinary player volume on the *next* play.
 */
class IntroLoudnessMeter(
    private val downstream: FloatPcmAudioFilter,
    private val onMeasured: (Double) -> Unit,
) : FloatPcmAudioFilter {

    private var sumOfSquares = 0.0
    private var sampleCount = 0L
    private var reported = false

    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        accumulate(input, offset, length)
        downstream.process(input, offset, length)
    }

    private fun accumulate(input: Array<FloatArray>, offset: Int, length: Int) {
        if (length <= 0) return
        for (channel in input) {
            // Defensive: lavaplayer sizes these to the negotiated channel
            // count, but a short buffer here would be an
            // ArrayIndexOutOfBounds thrown from inside the audio thread —
            // which would take the track down, not just the measurement.
            val end = minOf(offset + length, channel.size)
            var i = offset
            while (i < end) {
                val sample = channel[i].toDouble()
                sumOfSquares += sample * sample
                i++
            }
            sampleCount += (end - offset).coerceAtLeast(0)
        }
    }

    /**
     * A seek invalidates everything sampled so far — the clip's start offset
     * is applied as a seek, so without this the measurement would include
     * whatever the decoder emitted before jumping to the clip.
     */
    override fun seekPerformed(requestedTime: Long, providedTime: Long) {
        sumOfSquares = 0.0
        sampleCount = 0L
    }

    override fun flush() {
        // Nothing buffered here — samples are forwarded as they arrive.
    }

    override fun close() {
        // close() can fire more than once as the pipeline tears down; the
        // measurement is only meaningful the first time.
        if (reported) return
        reported = true
        IntroLoudness.rmsOf(sumOfSquares, sampleCount)?.let(onMeasured)
    }
}

package common.intro

import kotlin.math.sqrt

/**
 * Levelling intros against each other so the per-intro volume slider stops
 * being a trial-and-error control.
 *
 * Two people set an intro at the default volume: one picked a mastered-loud
 * pop track, the other a quiet live recording. The first is painful and the
 * second is inaudible, and the only fix on offer is "join a channel, listen,
 * run `/editintro`, guess a new number, repeat". The slider was never really
 * expressing a preference — it was compensating for the source.
 *
 * So the bot measures each intro's actual level the first time it plays (see
 * `IntroLoudnessMeter`) and applies a correcting gain on subsequent plays.
 * The user's number stays a *relative* preference — 50% is still half as loud
 * as 100% — but equal numbers on different tracks now sound equally loud.
 *
 * RMS rather than EBU R128 loudness: R128 needs K-weighting and gated
 * windowing over the whole programme, which is a lot of machinery for clips
 * capped at fifteen seconds. Plain RMS over the audio actually played gets
 * the two tracks above within a few dB of each other, which is the entire
 * complaint.
 */
object IntroLoudness {

    /**
     * Target RMS in the -1..1 float PCM lavaplayer hands the filter chain.
     * 0.09 is about -21 dBFS: comfortably below clipping with peaks intact,
     * and close to where the existing default volumes land for a typical
     * YouTube music upload, so guilds that never touch this barely move.
     */
    const val TARGET_RMS = 0.09

    /**
     * Correction bounds. Normalisation should fix "one of these is three
     * times louder than the other", not rescue a recording that's 30 dB down
     * — pushing that up just amplifies its noise floor. Clamping also means a
     * bad measurement (a clip that happens to start in silence) can't produce
     * a jarring result.
     */
    const val MIN_GAIN = 0.5
    const val MAX_GAIN = 2.0

    /**
     * Below this the measurement is treated as unusable rather than as "very
     * quiet" — a clip whose measured window was pure silence would otherwise
     * demand the maximum boost on the strength of no signal at all.
     */
    const val MIN_MEASURABLE_RMS = 0.001

    /** RMS from accumulated sum-of-squares, or null when nothing was sampled. */
    fun rmsOf(sumOfSquares: Double, sampleCount: Long): Double? {
        if (sampleCount <= 0L) return null
        val mean = sumOfSquares / sampleCount
        if (mean.isNaN() || mean < 0.0) return null
        return sqrt(mean)
    }

    /**
     * Multiplier to bring [measuredRms] to [TARGET_RMS], clamped to
     * [MIN_GAIN]..[MAX_GAIN]. Returns 1.0 (no change) when there's no usable
     * measurement — an unmeasured intro must sound exactly as it does today.
     */
    fun gainFor(measuredRms: Double?): Double {
        if (measuredRms == null || measuredRms.isNaN() || measuredRms < MIN_MEASURABLE_RMS) return 1.0
        return (TARGET_RMS / measuredRms).coerceIn(MIN_GAIN, MAX_GAIN)
    }

    /**
     * The volume to actually play [nominalVolume] at.
     *
     * Clamped to 0..200 to match the range `/setconfig` and the web form
     * accept for volumes, so a boosted loud intro can't hand lavaplayer a
     * value the rest of the bot would reject.
     */
    fun normalisedVolume(nominalVolume: Int, measuredRms: Double?): Int =
        (nominalVolume * gainFor(measuredRms)).toInt().coerceIn(0, MAX_VOLUME)

    /**
     * How the measurement reads to a human, for `/listintros` and the web row.
     * Phrased about the source, not the correction, because that's the part
     * the user can act on by picking a different track.
     */
    fun describe(measuredRms: Double?): String? {
        if (measuredRms == null || measuredRms.isNaN() || measuredRms < MIN_MEASURABLE_RMS) return null
        return when {
            measuredRms >= TARGET_RMS * 1.6 -> "loud source"
            measuredRms <= TARGET_RMS / 1.6 -> "quiet source"
            else -> "level"
        }
    }

    private const val MAX_VOLUME = 200
}

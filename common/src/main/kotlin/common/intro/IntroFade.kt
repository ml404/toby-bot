package common.intro

import kotlin.math.PI
import kotlin.math.cos

/**
 * Gain ramps for the start and end of an intro.
 *
 * Three moments in the intro flow were instantaneous, and all three were
 * audible:
 *
 *  - the intro arriving at full volume over the music it just preempted;
 *  - a *clipped* intro being stopped dead by its end marker, which cuts the
 *    waveform at whatever sample it happens to be on and pops;
 *  - the preempted track snapping back to full volume when the intro ends.
 *
 * A couple of hundred milliseconds of ramp on each removes all three. The
 * maths lives here, apart from lavaplayer, so the curve and the edge cases
 * can be tested without an audio pipeline —
 * [bot.toby.lavaplayer.IntroFadeFilter] is only the loop that applies it.
 */
object IntroFade {

    /** Ramp up at the start of an intro. */
    const val FADE_IN_MS = 250L

    /** Ramp down into the clip end. Longer than the fade in: a fade out that
     *  is too quick still reads as a cut, while a slow start reads as sluggish. */
    const val FADE_OUT_MS = 400L

    /** Ramp up for music resuming after an intro interrupted it. */
    const val RESUME_FADE_IN_MS = 400L

    /**
     * Below this, an intro is a stinger rather than a clip and the abruptness
     * is the point — a 400ms ramp on an 800ms airhorn is most of the airhorn.
     */
    const val MIN_FADEABLE_MS = 1_200L

    /**
     * Most of the playback that may be ramp rather than audio. Keeps a short
     * clip recognisable: the ramps are scaled down to fit rather than being
     * dropped, so a 2 second clip still gets a (proportionally smaller) fade.
     */
    const val MAX_FADE_FRACTION = 0.4

    /** How long to ramp at each end. Zero on either side means "don't". */
    data class Ramps(val fadeInMs: Long, val fadeOutMs: Long) {
        val isNoOp: Boolean get() = fadeInMs <= 0 && fadeOutMs <= 0
    }

    val NONE = Ramps(0, 0)

    /**
     * The ramps to actually use for a piece of playback.
     *
     * @param playbackMs how long the audio will play for, or null when that
     *        isn't known. Without it there is no way to know where the end is,
     *        so only the fade in survives — which is exactly the case for
     *        music resuming after an intro.
     */
    fun rampsFor(
        playbackMs: Long?,
        fadeInMs: Long = FADE_IN_MS,
        fadeOutMs: Long = FADE_OUT_MS,
    ): Ramps {
        val wantIn = fadeInMs.coerceAtLeast(0)
        val wantOut = fadeOutMs.coerceAtLeast(0)
        if (playbackMs == null || playbackMs <= 0) return Ramps(wantIn, 0)
        if (playbackMs < MIN_FADEABLE_MS) return NONE

        val budget = (playbackMs * MAX_FADE_FRACTION).toLong()
        val total = wantIn + wantOut
        if (total <= budget || total == 0L) return Ramps(wantIn, wantOut)

        // Scale both ends by the same factor so the shorter one doesn't
        // disappear entirely; integer division can round a small ramp to zero,
        // which is the right answer at that size.
        return Ramps(wantIn * budget / total, wantOut * budget / total)
    }

    /**
     * Gain in `0.0..1.0` at [elapsedMs] into the playback.
     *
     * The curve is a raised cosine rather than a straight line. Both sound
     * like a fade, but a linear ramp has a corner at each end — the very
     * discontinuity in slope that the ramp is here to remove — while this one
     * arrives at both 0 and 1 with zero slope.
     */
    fun gainAt(elapsedMs: Long, playbackMs: Long?, ramps: Ramps): Float {
        if (ramps.isNoOp) return 1f
        val elapsed = elapsedMs.coerceAtLeast(0)

        val fadingIn = if (ramps.fadeInMs > 0 && elapsed < ramps.fadeInMs) {
            raisedCosine(elapsed.toDouble() / ramps.fadeInMs)
        } else {
            1f
        }

        val fadingOut = if (playbackMs != null && ramps.fadeOutMs > 0) {
            val remaining = playbackMs - elapsed
            when {
                remaining <= 0 -> 0f
                remaining < ramps.fadeOutMs -> raisedCosine(remaining.toDouble() / ramps.fadeOutMs)
                else -> 1f
            }
        } else {
            1f
        }

        // [rampsFor] keeps the two from overlapping, but a caller supplying its
        // own ramps might not; taking the quieter of the two is the sane answer
        // either way.
        return minOf(fadingIn, fadingOut)
    }

    /** `0 -> 0`, `1 -> 1`, flat at both ends. */
    private fun raisedCosine(progress: Double): Float {
        val t = progress.coerceIn(0.0, 1.0)
        return (0.5 - 0.5 * cos(PI * t)).toFloat().coerceIn(0f, 1f)
    }
}

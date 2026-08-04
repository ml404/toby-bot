package common.intro

/**
 * Turns "the thing playing right now" into a clip window.
 *
 * Nobody reaches for a command *while* the good bit is playing — they hear it,
 * decide, and then start typing. So the default window looks **backwards** from
 * the playhead: the fifteen seconds you just heard, not the fifteen seconds
 * about to happen. Anticipating a drop is the rarer case, and the `start`/`end`
 * options are there for it.
 */
object IntroCapture {

    /**
     * Under this far into a track there is nothing behind the playhead worth
     * capturing, so the window runs forwards from the beginning instead — if
     * you fire the command two seconds in, you mean the opening.
     */
    const val MIN_LOOKBACK_MS = 3_000L

    /** A clip window in milliseconds, ready for `MusicDto.startMs`/`endMs`. */
    data class Window(val startMs: Int, val endMs: Int)

    /**
     * @param positionMs where the playhead is.
     * @param durationMs the source length, or null when unknown. Lavaplayer
     *        reports [Long.MAX_VALUE] for live streams, which is treated as
     *        unknown.
     * @param lengthMs how much to capture; defaults to the whole intro budget.
     * @return the window, or null when there is no usable audio to point at.
     */
    fun windowFor(
        positionMs: Long,
        durationMs: Long?,
        lengthMs: Long = IntroClip.MAX_CLIP_DURATION_MS.toLong(),
    ): Window? {
        if (lengthMs <= 0) return null
        // A source that reports no length at all has nothing to point at.
        // That is a different answer from null, which means "didn't say" —
        // and from lavaplayer's [Long.MAX_VALUE], which means "endless".
        if (durationMs != null && durationMs <= 0) return null
        val known = durationMs?.takeIf { it != Long.MAX_VALUE }
        val position = positionMs.coerceAtLeast(0).let { pos -> known?.let { minOf(pos, it) } ?: pos }

        val (start, end) = if (position < MIN_LOOKBACK_MS) {
            0L to minOf(lengthMs, known ?: lengthMs)
        } else {
            (position - lengthMs).coerceAtLeast(0) to position
        }

        if (end <= start) return null
        return Window(start.toInt(), end.toInt())
    }
}

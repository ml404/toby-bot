package common.intro

/**
 * Single source of truth for intro clip rules — the 15 second cap, the
 * `mm:ss` timestamp grammar, and the validation messages.
 *
 * Three surfaces need to agree on all three: the web form
 * (`IntroWebService` server-side, `intros.js` client-side), the Discord
 * slash commands (`/setintro`, `/editintro`), and anything that renders a
 * clip back to the user. Before this object existed the rules lived only
 * in `IntroWebService`, which meant Discord had no clip support at all and
 * simply rejected any source longer than the cap.
 *
 * Pure Kotlin with no framework dependencies so both the `web` and
 * `discord-bot` modules can depend on it.
 */
object IntroClip {

    /** Longest stretch of audio an intro may play for. */
    const val MAX_DURATION_SECONDS = 15

    const val MAX_CLIP_DURATION_MS: Int = MAX_DURATION_SECONDS * 1000

    /** Label used wherever an unclipped intro is described. */
    const val FULL_TRACK_LABEL = "full track"

    /** Outcome of parsing a user-supplied timestamp. */
    sealed interface Parsed {
        /** [ms] is null when the field was blank — i.e. "no bound set". */
        data class Ok(val ms: Int?) : Parsed
        data class Invalid(val message: String) : Parsed
    }

    /**
     * Parses `mm:ss`, `mm:ss.s`, or raw (possibly decimal) seconds into
     * milliseconds. Blank/null input parses to `Ok(null)` so callers can
     * treat "field left empty" as "clear this bound".
     *
     * Mirrors `parseTimeInput` in `intros.js`; the two are exercised by
     * matching test suites so the client and server can't drift.
     */
    fun parseTimestamp(raw: String?, fieldLabel: String = "Timestamp"): Parsed {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return Parsed.Ok(null)

        val invalid = Parsed.Invalid("$fieldLabel must look like `mm:ss` or a number of seconds.")

        if (!s.contains(':')) {
            val seconds = s.toDoubleOrNull() ?: return invalid
            if (!seconds.isFinite() || seconds < 0) return invalid
            return Parsed.Ok(Math.round(seconds * 1000).toInt())
        }

        val parts = s.split(':')
        if (parts.size != 2) return invalid
        val minutes = parts[0].toDoubleOrNull() ?: return invalid
        val seconds = parts[1].toDoubleOrNull() ?: return invalid
        if (!minutes.isFinite() || !seconds.isFinite()) return invalid
        if (minutes < 0 || seconds < 0 || seconds >= 60) return invalid
        return Parsed.Ok(Math.round((minutes * 60 + seconds) * 1000).toInt())
    }

    /** Renders milliseconds as `m:ss`, keeping one decimal for sub-second precision. */
    fun format(ms: Int?): String {
        if (ms == null || ms < 0) return ""
        val totalSeconds = ms / 1000.0
        val minutes = (totalSeconds / 60).toInt()
        val seconds = totalSeconds - minutes * 60
        val secondsLabel = if (ms % 1000 == 0) {
            "%02d".format(seconds.toInt())
        } else {
            "%04.1f".format(seconds)
        }
        return "$minutes:$secondsLabel"
    }

    /**
     * Human-readable description of a clip range, e.g. `0:03 – 0:12`,
     * `from 0:03`, `up to 0:12`, or [FULL_TRACK_LABEL] when unclipped.
     */
    fun describe(startMs: Int?, endMs: Int?): String = when {
        startMs == null && endMs == null -> FULL_TRACK_LABEL
        startMs != null && endMs != null -> "${format(startMs)} – ${format(endMs)}"
        startMs != null -> "from ${format(startMs)}"
        else -> "up to ${format(endMs)}"
    }

    /**
     * Validates a clip range against the source duration.
     *
     * - Both null: no clipping. Only reject when the source duration is
     *   known and exceeds the cap.
     * - start/end present: start must be < end, and the span must be
     *   within [MAX_CLIP_DURATION_MS].
     * - When [sourceDurationMs] is known, end must be <= it.
     *
     * @return an error message, or null when the clip is valid.
     */
    fun validate(startMs: Int?, endMs: Int?, sourceDurationMs: Int?): String? {
        if (startMs == null && endMs == null) {
            if (sourceDurationMs != null && sourceDurationMs > MAX_CLIP_DURATION_MS) {
                val seconds = sourceDurationMs / 1000
                return "Video is too long (${seconds}s). Max allowed is ${MAX_DURATION_SECONDS}s — " +
                    "set a start/end clip to use a longer source."
            }
            return null
        }
        val start = startMs ?: 0
        if (start < 0) return "Start time cannot be negative."
        if (endMs != null) {
            if (endMs <= start) return "End time must be greater than start time."
            if (sourceDurationMs != null && endMs > sourceDurationMs) {
                return "End time exceeds the source duration."
            }
            if (endMs - start > MAX_CLIP_DURATION_MS) {
                return "Clip is too long (${(endMs - start) / 1000}s). Max allowed is ${MAX_DURATION_SECONDS}s."
            }
        } else if (sourceDurationMs != null) {
            // Only start was provided; the clip runs from start to end of source.
            if (sourceDurationMs - start > MAX_CLIP_DURATION_MS) {
                return "Clip is too long (${(sourceDurationMs - start) / 1000}s). Max allowed is ${MAX_DURATION_SECONDS}s."
            }
        }
        return null
    }
}

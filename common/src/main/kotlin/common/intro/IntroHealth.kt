package common.intro

/**
 * When a stored intro should be treated as broken.
 *
 * Intros play through the voice-join path, which has no interaction to reply
 * to, so until now a track that failed to load produced silence and a server
 * log line — nothing the owner could see. A dead YouTube link (video deleted,
 * region-locked, age-gated, or the uploader's channel pulled) therefore stayed
 * in the rotation forever, looking healthy in `/listintros` and on the web.
 *
 * The counter this thresholds is *consecutive* failures: a successful load
 * resets it to zero. Lavaplayer already retries transient severities twice
 * before reporting a failure at all, so reaching [UNHEALTHY_AFTER_FAILURES]
 * means the source has failed outright on separate joins, minutes or days
 * apart. That's deliberately conservative — the cost of a false positive is
 * telling someone their intro is broken when it isn't.
 */
object IntroHealth {

    /**
     * Consecutive failed loads before an intro is considered broken: shown as
     * such in Discord and on the web, and skipped by [IntroSelection] when the
     * user has a working alternative.
     */
    const val UNHEALTHY_AFTER_FAILURES = 2

    /** Truncation bound for the stored failure reason (matches the column). */
    const val MAX_REASON_LENGTH = 500

    fun isUnhealthy(failureCount: Int): Boolean = failureCount >= UNHEALTHY_AFTER_FAILURES

    /**
     * Lavaplayer's `FriendlyException` messages are written for a human
     * ("This video is not available in your country"), so they're worth
     * showing verbatim — just bounded, and never blank, since an empty reason
     * reads as "we don't know why" when we do.
     */
    fun normaliseReason(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return "Source could not be loaded"
        return if (trimmed.length <= MAX_REASON_LENGTH) trimmed
        else trimmed.take(MAX_REASON_LENGTH - 1).trimEnd() + "…"
    }
}

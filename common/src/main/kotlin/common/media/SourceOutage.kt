package common.media

/**
 * Telling "this track is bad" apart from "the host is refusing us".
 *
 * Every failure path in the bot treats a load failure as a fact about the
 * *track*: `/play` reports "Nothing found for the link", and a failed intro
 * has its failure count bumped until its owner is told it's broken and that
 * replacing the link will fix it. All of that is true when a video has been
 * deleted, and all of it is a lie when YouTube has rate-limited or IP-blocked
 * the bot — which is exactly when it fires for *everybody at once*.
 *
 * A single failure can't be classified with any confidence: a blocked lookup
 * often comes back as "no matches", indistinguishable from a dead link. What
 * gives it away is correlation. Several *different* sources failing inside a
 * couple of minutes is not several dead links.
 */
object SourceOutage {

    /**
     * Distinct sources that must fail inside [WINDOW_MS] before the host, not
     * the tracks, is treated as the problem.
     *
     * Three rather than two: two unrelated dead links in the same couple of
     * minutes is a thing that happens on a busy server, and the cost of a
     * false positive is a real broken intro going unreported for a while.
     */
    const val OUTAGE_AFTER_DISTINCT_FAILURES = 3

    /** How long failures stay relevant to each other. */
    const val WINDOW_MS = 120_000L

    /** How long an outage stays declared without further evidence. */
    const val OUTAGE_HOLD_MS = 300_000L

    /**
     * Phrases that identify a refusal outright, without waiting for
     * corroboration.
     *
     * Deliberately narrow. "Sign in to confirm you're not a bot" is YouTube
     * refusing *us*; "Sign in to confirm your age" is the track being gated
     * and genuinely unplayable — near-identical strings meaning opposite
     * things, so nothing here matches on "sign in" alone.
     */
    private val BLOCK_MARKERS = listOf(
        "not a bot",
        "too many requests",
        "http error 429",
        "status code 429",
        "rate limit",
        "rate-limited",
        "temporarily blocked",
        "blocked from making requests",
    )

    /** True when [reason] names the host refusing us rather than a bad track. */
    fun looksLikeBlock(reason: String?): Boolean {
        // Curly apostrophes are common in these messages, so compare against a
        // straightened copy rather than listing both forms of every marker.
        val text = reason?.lowercase()?.replace('’', '\'') ?: return false
        return BLOCK_MARKERS.any { text.contains(it) }
    }
}

/**
 * Watches load failures for the shape of a host-side outage.
 *
 * Thread-safe: failures arrive on lavaplayer's load threads, and the answer is
 * read from those and from the Discord dispatch threads.
 *
 * @param clock injectable so the windows can be tested without waiting them out.
 */
class SourceOutageTracker(
    private val threshold: Int = SourceOutage.OUTAGE_AFTER_DISTINCT_FAILURES,
    private val windowMs: Long = SourceOutage.WINDOW_MS,
    private val holdMs: Long = SourceOutage.OUTAGE_HOLD_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val recentFailures = LinkedHashMap<String, Long>()
    private var declaredAt: Long? = null

    /**
     * @param sourceKey what failed. Keyed by source so the *same* dead link
     *        failing on repeat can never look like an outage on its own.
     * @param definite true when the failure named a block outright, which
     *        needs no corroboration.
     * @return true when this failure is the one that declared the outage, so
     *         the caller can log the onset once rather than on every failure.
     */
    fun recordFailure(sourceKey: String, definite: Boolean = false): Boolean = synchronized(lock) {
        val now = clock()
        val alreadyDeclared = isOutageAt(now)

        recentFailures.entries.removeIf { now - it.value > windowMs }
        recentFailures[sourceKey] = now

        if (definite || recentFailures.size >= threshold) {
            declaredAt = now
            return !alreadyDeclared
        }
        false
    }

    /**
     * A load succeeded, so we are demonstrably not blocked.
     *
     * This is what ends an outage: waiting out [holdMs] would keep reporting
     * one long after playback started working, and the first success is proof
     * in a way that elapsed time never is.
     */
    fun recordSuccess() = synchronized(lock) {
        recentFailures.clear()
        declaredAt = null
    }

    /** Whether the host currently looks like it is refusing us. */
    fun isOutage(): Boolean = synchronized(lock) { isOutageAt(clock()) }

    private fun isOutageAt(now: Long): Boolean = declaredAt?.let { now - it <= holdMs } ?: false
}

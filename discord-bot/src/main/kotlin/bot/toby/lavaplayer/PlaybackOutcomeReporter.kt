package bot.toby.lavaplayer

/**
 * Where [TrackScheduler] sends the outcome of a track it actually played.
 *
 * The scheduler reports facts — audio ran, or the stream died — and the policy
 * about what those mean (whether the host looks blocked, whether an intro's
 * owner should be told) stays with [PlayerManager], which owns the trackers.
 *
 * It exists as a seam because [PlayerManager.instance] is a lazy singleton that
 * builds a real audio player and its HTTP clients on first touch. The scheduler
 * has to report on the voice-join path, where there is no interaction to hang
 * the work off, so the call cannot be guarded behind a null check the way
 * `nowPlaying` is — and a scheduler unit test must not stand up a live player
 * just by ending a track.
 */
interface PlaybackOutcomeReporter {

    /**
     * Audio reached the listener.
     *
     * @param introId set only when the track was somebody's intro.
     * @param uninterrupted true when the track ran to its own end rather than
     *   being cut off. Only an uninterrupted play is taken as proof the source
     *   is serving us again — see [PlayerManager.reportPlaybackSuccess].
     */
    fun playbackSucceeded(introId: String?, uninterrupted: Boolean)

    /**
     * The stream died part-way through.
     *
     * @param sourceKey identifies the track that failed, so a single dead video
     *   can be told apart from the host refusing everything.
     * @return whether another attempt is worth making. False while the source
     *   is refusing us: a retry then is a second request to a host that has
     *   already said no, which is how a rate limit becomes a block.
     */
    fun playbackFailed(introId: String?, sourceKey: String, reason: String?): Boolean

    companion object {
        /**
         * The live wiring. Resolved inside the method bodies rather than at
         * construction, so holding this costs nothing until an outcome is
         * actually reported.
         */
        val DEFAULT: PlaybackOutcomeReporter = object : PlaybackOutcomeReporter {
            override fun playbackSucceeded(introId: String?, uninterrupted: Boolean) =
                PlayerManager.instance.reportPlaybackSuccess(introId, uninterrupted)

            override fun playbackFailed(introId: String?, sourceKey: String, reason: String?): Boolean =
                PlayerManager.instance.reportPlaybackFailure(introId, sourceKey, reason)
        }
    }
}

package bot.toby.intro

/**
 * Facts about an intro's playback that originate below Spring.
 *
 * `PlayerManager` / `GuildMusicManager` / `TrackScheduler` are constructed by
 * the `PlayerManager` singleton rather than the container, so they reach the
 * bean that persists these through [bot.toby.lavaplayer.SchedulerEvents] —
 * the same static bridge the track events already use. Publishing rather than
 * calling a service keeps the audio path free of a Spring dependency, and a
 * missing publisher (unit tests) degrades to a no-op instead of an NPE.
 */
sealed interface IntroPlaybackEvent {
    val introId: String
}

/**
 * An intro's audio was sampled during playback. [rms] is its own level, in
 * the -1..1 float PCM domain, before any volume was applied.
 */
data class IntroLoudnessMeasuredEvent(
    override val introId: String,
    val rms: Double,
) : IntroPlaybackEvent

/**
 * An intro's source loaded and started. This is the closest thing to "it
 * played" that the load path can honestly report — a source that starts and
 * then dies mid-stream is a different failure, and one nobody has complained
 * about.
 */
data class IntroPlayedEvent(
    override val introId: String,
) : IntroPlaybackEvent

/**
 * An intro's source could not be loaded, after lavaplayer exhausted its
 * retries. [reason] is the human-readable message shown back to the owner.
 *
 * No guild/user on the event: the handler loads the row to bump its failure
 * count anyway, and that row already knows whose it is.
 */
data class IntroLoadFailedEvent(
    override val introId: String,
    val reason: String,
) : IntroPlaybackEvent

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
 * An intro actually played: audio reached the listener and the source did not
 * die underneath it.
 *
 * Published when the track *ends*, not when it is queued. Queue time was as
 * close as the load path could honestly get, and the distance turned out to
 * matter: a source that resolves and then fails mid-stream produced silence
 * and was still recorded as a play. Because a play also clears the failure
 * counter, every attempt erased the evidence of the last one, so an intro
 * failing this way could never reach the threshold that DMs its owner.
 */
data class IntroPlayedEvent(
    override val introId: String,
) : IntroPlaybackEvent

/**
 * An intro's source failed — either refusing to load after lavaplayer
 * exhausted its retries, or dying part-way through streaming. [reason] is the
 * human-readable message shown back to the owner.
 *
 * One event for both because the owner's position is identical either way:
 * their intro made no sound and the link is what they can act on. It was
 * named for the load half alone while the streaming half went unhandled.
 *
 * No guild/user on the event: the handler loads the row to bump its failure
 * count anyway, and that row already knows whose it is.
 */
data class IntroFailedEvent(
    override val introId: String,
    val reason: String,
) : IntroPlaybackEvent

/**
 * The audio source is refusing us, and a guild has just felt it.
 *
 * Separate from [IntroPlaybackEvent] because it is about the host rather than
 * any one intro: during an outage every intro on every server is silent, and
 * the failures are deliberately not counted against anybody's row — which left
 * the one moment when *everyone's* intro was broken as the one moment nothing
 * explained it.
 *
 * Published at most once per guild per episode. [PlayerManager] holds the
 * latch, because it owns the tracker that knows when the episode ends.
 */
data class SourceOutageNoticedEvent(val guildId: Long)

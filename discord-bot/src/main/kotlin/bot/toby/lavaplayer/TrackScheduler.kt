package bot.toby.lavaplayer

import bot.toby.helpers.MusicPlayerHelper.nowPlaying
import bot.toby.helpers.MusicPlayerHelper.resetMessages
import bot.toby.util.deriveDeleteDelayFromTrack
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.TrackMarker
import com.sedmelluq.discord.lavaplayer.track.TrackMarkerHandler
import common.logging.DiscordLogger
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.Command.Companion.replyAndDelete
import core.music.events.PauseStateChangedEvent
import core.music.events.QueueChangedEvent
import core.music.events.TrackEndedEvent
import core.music.events.TrackStartedEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

class TrackScheduler(val player: AudioPlayer, val guildId: Long, var deleteDelay: Int = 5) :
    AudioEventAdapter(), IntroTrackContext {
    var queue: BlockingQueue<AudioTrack> = LinkedBlockingQueue(100)
    var isLooping: Boolean = false
    var event: SlashCommandInteractionEvent? = null
    private var previousVolume: Int? = null
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val trackClipBounds = ConcurrentHashMap<AudioTrack, Pair<Long, Long>>()
    private val trackRequesters = ConcurrentHashMap<AudioTrack, Long>()
    private val introTracks: MutableSet<AudioTrack> = ConcurrentHashMap.newKeySet()

    /**
     * Which stored intro row each in-flight intro track came from.
     *
     * Kept alongside [introTracks] rather than folded into it because the id
     * has to outlive the track by exactly one step: the loudness filter
     * reports its measurement as the pipeline closes, and it needs to know
     * which row to write the number to.
     */
    private val introTrackIds = ConcurrentHashMap<AudioTrack, String>()

    /**
     * How long each in-flight intro will actually play for, worked out when it
     * was queued rather than when its filter chain is built — by then the clip
     * start has already been applied to `position` and the two are
     * indistinguishable.
     */
    private val introPlaybackMs = ConcurrentHashMap<AudioTrack, Long>()

    /** Tracks that an intro interrupted and that are being put back. */
    private val resumingTracks: MutableSet<AudioTrack> = ConcurrentHashMap.newKeySet()
    private var resumeAfterIntro: AudioTrack? = null

    fun getRequesterId(track: AudioTrack): Long? = trackRequesters[track]

    internal fun hasResumeAfterIntro(): Boolean = resumeAfterIntro != null

    internal fun isIntroTrack(track: AudioTrack): Boolean = introTracks.contains(track)

    /** The track this guild is playing right now, or null when it is idle. */
    override fun currentTrack(): AudioTrack? = player.playingTrack

    /** The intro row id behind [track], or null when it isn't an intro. */
    override fun introIdFor(track: AudioTrack): String? = introTrackIds[track]

    override fun introPlaybackMsFor(track: AudioTrack): Long? = introPlaybackMs[track]

    override fun isResumingTrack(track: AudioTrack): Boolean = resumingTracks.contains(track)

    fun queue(track: AudioTrack, startPosition: Long, endPosition: Long?, volume: Int, requesterId: Long? = null) {
        logger.info("Adding ${track.info.title} by ${track.info.author} to the queue for guild $guildId")
        val endNote = endPosition?.let { " (clipped to $it ms)" }.orEmpty()
        event?.hook?.replyAndDelete(
            "Adding to queue: `${track.info.title}` by `${track.info.author}` starting at '${startPosition} ms'$endNote with volume '$volume'",
            deleteDelay,
        )
        prepareTrack(track, startPosition, endPosition, volume, requesterId)
        synchronized(queue) {
            if (!player.startTrack(track, true)) {
                queue.offer(track)
            }
        }
        publishQueueChanged()
    }

    /**
     * Play an intro track immediately, preempting whatever is currently playing.
     * The preempted track is cloned (with its live position) and stored so it
     * resumes after the intro finishes. If nothing is playing, falls back to the
     * regular queue path. If an intro is already in flight (resume slot already
     * occupied), this intro is queued normally rather than overwriting the slot.
     */
    fun queueIntro(
        introTrack: AudioTrack,
        startPosition: Long,
        endPosition: Long?,
        volume: Int,
        requesterId: Long? = null,
        introId: String? = null,
    ) {
        logger.info("Preparing intro ${introTrack.info.title} for guild $guildId")
        prepareTrack(introTrack, startPosition, endPosition, volume, requesterId)
        introId?.let { introTrackIds[introTrack] = it }
        playbackLengthOf(introTrack, startPosition, endPosition)?.let { introPlaybackMs[introTrack] = it }
        val currentlyPlaying = player.playingTrack
        if (currentlyPlaying == null || resumeAfterIntro != null) {
            // No track to preempt, or a resume slot is already occupied by an
            // earlier intro — fall back to the standard queue-or-start path so
            // we don't clobber the original music with a second intro.
            synchronized(queue) {
                if (!player.startTrack(introTrack, true)) {
                    queue.offer(introTrack)
                }
            }
            introTracks.add(introTrack)
            publishQueueChanged()
            return
        }
        // Snapshot the currently-playing track into a resume slot before the
        // intro replaces it.
        val clone = currentlyPlaying.makeClone()
        clone.position = currentlyPlaying.position
        clone.userData = currentlyPlaying.userData
        trackClipBounds[currentlyPlaying]?.let { trackClipBounds[clone] = it }
        trackRequesters[currentlyPlaying]?.let { trackRequesters[clone] = it }
        resumeAfterIntro = clone
        resumingTracks.add(clone)
        introTracks.add(introTrack)
        player.startTrack(introTrack, false)
        publishQueueChanged()
    }

    /**
     * How long an intro will play for: its clip length, or whatever is left of
     * the track from its start offset.
     *
     * Null when the source can't say — a live stream reports
     * [Long.MAX_VALUE] — in which case the fade has no end to aim at and only
     * ramps in.
     */
    private fun playbackLengthOf(track: AudioTrack, startPosition: Long, endPosition: Long?): Long? {
        val end = endPosition ?: track.duration.takeIf { it > 0 && it != Long.MAX_VALUE } ?: return null
        return (end - startPosition).takeIf { it > 0 }
    }

    private fun prepareTrack(
        track: AudioTrack,
        startPosition: Long,
        endPosition: Long?,
        volume: Int,
        requesterId: Long?,
    ) {
        track.position = startPosition
        track.userData = volume
        requesterId?.let { trackRequesters[track] = it }
        if (endPosition != null && endPosition > startPosition) {
            trackClipBounds[track] = startPosition to endPosition
            track.setMarker(TrackMarker(endPosition) { state ->
                // Stop the clipped track on reaching the end marker via the player so
                // onTrackEnd reliably fires; that handler tears down the now-playing
                // embed and advances the queue / restores the previous volume.
                if (state == TrackMarkerHandler.MarkerState.REACHED) {
                    logger.info("Clip end marker reached at $endPosition ms for ${track.info.title}, stopping track.")
                    player.stopTrack()
                }
            })
        }
    }

    // Back-compat overload for the pre-clip call sites; currently unused but keeps
    // the older signature compiling if anything else still calls it.
    fun queue(track: AudioTrack, startPosition: Long, volume: Int) = queue(track, startPosition, null, volume, null)

    fun queueTrackList(playList: AudioPlaylist, volume: Int, requesterId: Long? = null) {
        logger.info { "Adding ${playList.name} to the queue for guild $guildId" }
        event?.hook?.replyAndDelete(
            "Adding to queue: `${playList.tracks.size} tracks from playlist ${playList.name}`",
            deleteDelay,
        )
        playList.tracks.forEach { track ->
            track.userData = volume
            requesterId?.let { trackRequesters[track] = it }
            if (!player.startTrack(track, true)) {
                queue.offer(track)
            }
        }
        publishQueueChanged()
    }

    fun nextTrack() {
        synchronized(queue) {
            val track = queue.poll() ?: return
            player.volume = track.userData as Int
            player.startTrack(track, false)
        }
    }

    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        logger.info { "${track.info.title} by ${track.info.author} started for guild $guildId" }
        super.onTrackStart(player, track)
        player.volume = track.userData as Int
        val (clipStart, clipEnd) = trackClipBounds[track]?.let { it.first to it.second } ?: (null to null)
        event?.let { nowPlaying(it, PlayerManager.instance, deriveDeleteDelayFromTrack(track), clipStart, clipEnd) }
        SchedulerEvents.publish(TrackStartedEvent(guildId, TrackInfoMapper.toTrackInfo(track, trackRequesters[track])))
        publishQueueChanged()
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        trackClipBounds.remove(track)
        trackRequesters.remove(track)
        // Safe to drop here even though the loudness filter reports later: the
        // factory resolved the id when the chain was built and holds it in its
        // callback closure.
        introTrackIds.remove(track)
        introPlaybackMs.remove(track)
        resumingTracks.remove(track)
        val wasIntro = introTracks.remove(track)
        logger.info("${track.info.title} by ${track.info.author} ended")
        SchedulerEvents.publish(TrackEndedEvent(guildId, endReason.name))
        // An intro just finished and we have a preempted track waiting: restart
        // it (do NOT advance the regular queue so user-queued tracks added during
        // the intro stay queued behind the resumed one). We bypass mayStartNext
        // because clip-end markers stop the intro with STOPPED (mayStartNext is
        // false), but we still want to resume the music that was playing before.
        // REPLACED and CLEANUP are excluded: REPLACED means something else
        // already took over the player; CLEANUP means the player is being
        // destroyed (e.g. the bot is leaving the guild).
        val shouldResumeAfterIntro = wasIntro
                && resumeAfterIntro != null
                && endReason != AudioTrackEndReason.REPLACED
                && endReason != AudioTrackEndReason.CLEANUP
        if (shouldResumeAfterIntro) {
            // Keep the now-playing message: the resumed track's onTrackStart will
            // edit it back in place rather than spawning a fresh one.
            val resume = resumeAfterIntro!!
            resumeAfterIntro = null
            player.setVolumeToPrevious()
            logger.info("Resuming preempted track ${resume.info.title} at ${resume.position} ms")
            player.startTrack(resume, false)
            return
        }
        if (wasIntro) releaseStaleResumeSlot(endReason)
        if (endReason.mayStartNext) {
            handleNextTrack(player, track)
        } else if (endReason != AudioTrackEndReason.REPLACED) {
            // Playback has actually stopped and nothing is taking over (REPLACED
            // means another track already started and its onTrackStart will
            // refresh the embed in place). Tear down the now-playing message.
            event?.guild?.idLong.resetMessagesForGuildId()
        }
    }

    override fun onPlayerPause(player: AudioPlayer) {
        super.onPlayerPause(player)
        SchedulerEvents.publish(PauseStateChangedEvent(guildId, true))
    }

    override fun onPlayerResume(player: AudioPlayer) {
        super.onPlayerResume(player)
        SchedulerEvents.publish(PauseStateChangedEvent(guildId, false))
    }

    private fun handleNextTrack(player: AudioPlayer, track: AudioTrack) {
        if (isLooping) {
            // The clone's onTrackStart edits the existing now-playing message in
            // place, so don't tear it down here.
            player.startTrack(track.makeClone(), false)
            return
        }
        PlayerManager.instance.isCurrentlyStoppable = true
        player.setVolumeToPrevious()
        if (queue.peek() != null) {
            // Advance to the next track. nextTrack() -> player.startTrack fires
            // onTrackStart, which renders the (clip-aware) now-playing and edits
            // the existing message in place — same path the loop branch relies
            // on — so we don't render it a second time here.
            nextTrack()
        } else {
            // Queue exhausted — nothing else will play, so clean up the
            // now-playing message and its scheduled updates.
            event?.guild?.idLong.resetMessagesForGuildId()
        }
    }

    /**
     * An intro ended without handing the player back to the track it preempted.
     *
     * Two ways that happens: something else took the player over (REPLACED —
     * a skip landing mid-intro, or the stuck-track recovery below), or the
     * player is being torn down (CLEANUP). Either way the resume slot is now
     * stale, and leaving it set is the expensive part: [queueIntro] treats a
     * non-null slot as "an intro is already in flight" and falls back to
     * queueing, so one stranded slot quietly disables intro preemption for the
     * rest of this player's life.
     *
     * On a takeover the preempted track goes back to the front of the queue
     * rather than being dropped — the listener skipped the intro, not the music
     * they were already playing. On CLEANUP there's nothing left to play it.
     */
    private fun releaseStaleResumeSlot(endReason: AudioTrackEndReason) {
        val stranded = resumeAfterIntro ?: return
        resumeAfterIntro = null
        if (endReason != AudioTrackEndReason.REPLACED) {
            logger.info("Dropping preempted track ${stranded.info.title}: player is going away ($endReason)")
            resumingTracks.remove(stranded)
            return
        }
        // Keeps its resume mark: going back through the queue rather than
        // straight onto the player doesn't change that it was interrupted, so
        // it should still fade in when it gets there.
        logger.info("Intro was taken over; requeueing preempted track ${stranded.info.title} at the front")
        synchronized(queue) {
            val rest = queue.toMutableList()
            queue.clear()
            queue.offer(stranded)
            rest.forEach { queue.offer(it) }
        }
        publishQueueChanged()
    }

    private fun AudioPlayer.setVolumeToPrevious() {
        previousVolume?.let { previousVol ->
            if (player.volume != previousVol) {
                this.volume = previousVol
                event?.channel
                    ?.sendMessageFormat("Setting volume back to '$previousVol' \uD83D\uDD0A")
                    ?.queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
        }
    }

    /**
     * Lavaplayer reports a track as stuck once it has gone [thresholdMs]
     * (10 seconds by default) without producing a frame. That is a dead track,
     * not a hiccup, so it is always recovered from — the old `position == 0L`
     * guard meant a stall that started after playback began was never skipped
     * and simply hung the queue with nothing to unstick it.
     */
    override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
        logger.warn(
            "'${track.info.title}' produced no audio for ${thresholdMs}ms at position " +
                "${track.position}ms on guild $guildId; recovering."
        )
        event?.channel
            ?.sendMessage("Track ${track.info.title} got stuck, skipping.")
            ?.queue(invokeDeleteOnMessageResponse(deleteDelay))

        // Read before stopping: stopping is what consumes the resume slot.
        val resumesPreemptedTrack = introTracks.contains(track) && resumeAfterIntro != null

        // Stop through the player rather than jumping straight to nextTrack().
        // onTrackEnd is the only place that restores a preempted track, drops
        // the intro bookkeeping and tears down the now-playing message; going
        // around it was how a single stuck intro lost the music it interrupted
        // and left the resume slot set forever.
        player.stopTrack()

        // STOPPED has mayStartNext = false, so onTrackEnd deliberately doesn't
        // advance the queue. Correct when the preempted track has just been
        // put back on the player; wrong for everything else.
        if (!resumesPreemptedTrack) nextTrack()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int): Boolean {
        val moved = synchronized(queue) {
            val snapshot = queue.toMutableList()
            if (fromIndex < 0 || fromIndex >= snapshot.size) return@synchronized false
            if (toIndex < 0 || toIndex >= snapshot.size) return@synchronized false
            if (fromIndex == toIndex) return@synchronized true
            val item = snapshot.removeAt(fromIndex)
            snapshot.add(toIndex, item)
            queue.clear()
            snapshot.forEach { queue.offer(it) }
            true
        }
        if (moved) publishQueueChanged()
        return moved
    }

    fun removeQueueItem(index: Int): AudioTrack? {
        val removed = synchronized(queue) {
            val snapshot = queue.toMutableList()
            if (index < 0 || index >= snapshot.size) return@synchronized null
            val item = snapshot.removeAt(index)
            trackRequesters.remove(item)
            trackClipBounds.remove(item)
            resumingTracks.remove(item)
            queue.clear()
            snapshot.forEach { queue.offer(it) }
            item
        }
        if (removed != null) publishQueueChanged()
        return removed
    }

    internal fun publishQueueChanged() {
        val snapshot = synchronized(queue) {
            queue.toList()
        }
        val tracks = snapshot.map { TrackInfoMapper.toTrackInfo(it, trackRequesters[it]) }
        SchedulerEvents.publish(QueueChangedEvent(guildId, tracks))
    }

    fun stopTrack(isStoppable: Boolean): Boolean {
        if (!isStoppable) return false
        // A user-initiated stop should not auto-resume a preempted track when
        // the in-flight intro ends.
        resumeAfterIntro = null
        introTracks.clear()
        introTrackIds.clear()
        introPlaybackMs.clear()
        resumingTracks.clear()
        player.stopTrack()
        player.setVolumeToPrevious()
        return true
    }

    fun setPreviousVolume(previousVolume: Int?) {
        this.previousVolume = previousVolume
    }

    private fun Long?.resetMessagesForGuildId() {
        this?.let { resetMessages(it) }
    }
}

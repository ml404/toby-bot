package bot.toby.lavaplayer

import bot.toby.lavaplayer.TrackInfoMapper.toTrackInfo
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import common.logging.DiscordLogger
import core.music.MusicControlGateway
import core.music.MusicControlGateway.GuildPlayerState
import core.music.MusicControlGateway.LoadResult
import core.music.MusicControlGateway.TrackInfo
import core.music.MusicControlGateway.VoiceChannelInfo
import core.music.MusicControlGateway.VoiceMember
import core.music.events.LoopStateChangedEvent
import core.music.events.VolumeChangedEvent
import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Component
class MusicControlGatewayImpl @Autowired(required = false) constructor(
    private val jdaSupplier: JdaSupplier? = null,
) : MusicControlGateway {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    private val playerManager: PlayerManager get() = PlayerManager.instance

    private fun jda(): JDA? = jdaSupplier?.get()

    override fun getState(guildId: Long): GuildPlayerState? {
        val jda = jda() ?: return null
        val guild = jda.getGuildById(guildId) ?: return null
        val musicManager = playerManager.getMusicManager(guild)
        val audioPlayer = musicManager.audioPlayer
        val scheduler = musicManager.scheduler
        val playing = audioPlayer.playingTrack
        val queueSnapshot = synchronized(scheduler.queue) { scheduler.queue.toList() }
        val voiceChannel = guild.audioManager.connectedChannel
        val voiceInfo = voiceChannel?.let { channel ->
            VoiceChannelInfo(
                id = channel.idLong,
                name = channel.name,
                members = channel.members.map { member ->
                    val voiceState = member.voiceState
                    VoiceMember(
                        discordId = member.idLong,
                        displayName = member.effectiveName,
                        avatarUrl = member.effectiveAvatarUrl,
                        isBot = member.user.isBot,
                        isMuted = voiceState?.isMuted == true || voiceState?.isSelfMuted == true,
                        isDeafened = voiceState?.isDeafened == true || voiceState?.isSelfDeafened == true,
                    )
                },
            )
        }
        return GuildPlayerState(
            guildId = guildId,
            nowPlaying = playing?.let { toTrackInfo(it, scheduler.getRequesterId(it)) },
            positionMs = playing?.position ?: 0L,
            paused = audioPlayer.isPaused,
            volume = audioPlayer.volume,
            looping = scheduler.isLooping,
            queue = queueSnapshot.map { toTrackInfo(it, scheduler.getRequesterId(it)) },
            voiceChannelId = voiceChannel?.idLong,
            voiceChannel = voiceInfo,
        )
    }

    override fun search(guildId: Long, query: String, limit: Int): List<TrackInfo> {
        val jda = jda() ?: return emptyList()
        val guild = jda.getGuildById(guildId) ?: return emptyList()
        val resolved = SearchPrefixResolver.resolve(query)
        val resultRef = AtomicReference<List<TrackInfo>>(emptyList())
        val latch = java.util.concurrent.CountDownLatch(1)

        playerManager.loadForExternal(
            guild,
            resolved,
            object : AudioLoadResultHandler {
                override fun trackLoaded(track: AudioTrack) {
                    resultRef.set(listOf(toTrackInfo(track, null)))
                    latch.countDown()
                }

                override fun playlistLoaded(playlist: AudioPlaylist) {
                    resultRef.set(playlist.tracks.take(limit).map { toTrackInfo(it, null) })
                    latch.countDown()
                }

                override fun noMatches() {
                    latch.countDown()
                }

                override fun loadFailed(exception: FriendlyException) {
                    logger.warn { "Search failed for query=$resolved: ${exception.message}" }
                    latch.countDown()
                }
            },
        )

        latch.await(8, TimeUnit.SECONDS)
        return resultRef.get()
    }

    override fun load(guildId: Long, query: String, requesterDiscordId: Long): LoadResult {
        val jda = jda() ?: return LoadResult(false, 0, "Bot not connected to Discord")
        val guild = jda.getGuildById(guildId) ?: return LoadResult(false, 0, "Guild not found")
        if (guild.audioManager.connectedChannel == null) {
            return LoadResult(false, 0, "Bot must be in a voice channel — join one with /join first")
        }
        val musicManager = playerManager.getMusicManager(guild)
        val resolvedQuery = SearchPrefixResolver.resolve(query)
        val resultRef = AtomicReference<LoadResult?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)

        playerManager.loadForExternal(
            guild,
            resolvedQuery,
            object : AudioLoadResultHandler {
                override fun trackLoaded(track: AudioTrack) {
                    musicManager.scheduler.queue(
                        track,
                        startPosition = 0L,
                        endPosition = null,
                        volume = musicManager.audioPlayer.volume,
                        requesterId = requesterDiscordId,
                    )
                    resultRef.set(LoadResult(true, 1, null))
                    latch.countDown()
                }

                override fun playlistLoaded(playlist: AudioPlaylist) {
                    val volume = musicManager.audioPlayer.volume
                    playlist.tracks.forEach { track ->
                        musicManager.scheduler.queue(track, 0L, null, volume, requesterDiscordId)
                    }
                    resultRef.set(LoadResult(true, playlist.tracks.size, null))
                    latch.countDown()
                }

                override fun noMatches() {
                    resultRef.set(LoadResult(false, 0, "No matches for '$resolvedQuery'"))
                    latch.countDown()
                }

                override fun loadFailed(exception: FriendlyException) {
                    logger.error { "Track load failed for query=$resolvedQuery: ${exception.message}" }
                    resultRef.set(LoadResult(false, 0, exception.message ?: "Load failed"))
                    latch.countDown()
                }
            },
        )

        val completed = latch.await(8, TimeUnit.SECONDS)
        return resultRef.get() ?: if (completed) {
            LoadResult(false, 0, "Load completed without result")
        } else {
            LoadResult(false, 0, "Load timed out after 8s")
        }
    }

    override fun pause(guildId: Long): Boolean = mutate(guildId) { mm ->
        if (mm.audioPlayer.isPaused) return@mutate false
        mm.audioPlayer.isPaused = true
        true
    }

    override fun resume(guildId: Long): Boolean = mutate(guildId) { mm ->
        if (!mm.audioPlayer.isPaused) return@mutate false
        mm.audioPlayer.isPaused = false
        true
    }

    override fun skip(guildId: Long, count: Int): Boolean = mutate(guildId) { mm ->
        if (count <= 0) return@mutate false
        repeat(count) { mm.scheduler.nextTrack() }
        true
    }

    override fun stop(guildId: Long): Boolean = mutate(guildId) { mm ->
        mm.scheduler.stopTrack(true)
        mm.scheduler.queue.clear()
        mm.scheduler.isLooping = false
        mm.audioPlayer.isPaused = false
        mm.scheduler.publishQueueChanged()
        true
    }

    override fun setVolume(guildId: Long, volume: Int): Boolean {
        val clamped = volume.coerceIn(0, 150)
        return mutate(guildId) { mm ->
            mm.audioPlayer.volume = clamped
            SchedulerEvents.publish(VolumeChangedEvent(guildId, clamped))
            true
        }
    }

    override fun seek(guildId: Long, positionMs: Long): Boolean = mutate(guildId) { mm ->
        if (positionMs < 0) return@mutate false
        val track = mm.audioPlayer.playingTrack ?: return@mutate false
        if (positionMs > track.duration) return@mutate false
        track.position = positionMs
        true
    }

    override fun reorderQueue(guildId: Long, fromIndex: Int, toIndex: Int): Boolean = mutate(guildId) { mm ->
        mm.scheduler.moveQueueItem(fromIndex, toIndex)
    }

    override fun removeFromQueue(guildId: Long, index: Int): TrackInfo? {
        val jda = jda() ?: return null
        val guild = jda.getGuildById(guildId) ?: return null
        val mm = playerManager.getMusicManager(guild)
        val removed = mm.scheduler.removeQueueItem(index) ?: return null
        return toTrackInfo(removed, requesterId = null)
    }

    override fun setLooping(guildId: Long, looping: Boolean): Boolean = mutate(guildId) { mm ->
        if (mm.scheduler.isLooping == looping) return@mutate true
        mm.scheduler.isLooping = looping
        SchedulerEvents.publish(LoopStateChangedEvent(guildId, looping))
        true
    }

    private inline fun mutate(guildId: Long, crossinline action: (GuildMusicManager) -> Boolean): Boolean {
        val jda = jda() ?: return false
        val guild = jda.getGuildById(guildId) ?: return false
        return action(playerManager.getMusicManager(guild))
    }
}

/**
 * Allows tests to inject a no-JDA shim. In production the bot wires this via
 * its JDA bootstrap.
 */
fun interface JdaSupplier {
    fun get(): JDA?
}

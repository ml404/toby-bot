package toby.managers

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import mu.KotlinLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import toby.helpers.MusicPlayerHelper.formatTime
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val logger = KotlinLogging.logger {}

class NowPlayingManager {

    private val guildLastNowPlayingMessage = ConcurrentHashMap<Long, Message>()
    private val lock = ReentrantReadWriteLock()
    private var scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    fun setNowPlayingMessage(guildId: Long, message: Message) {
        lock.write {
            logger.info { "Setting now playing message ${message.idLong} for guild $guildId" }
            guildLastNowPlayingMessage[guildId] = message
            logger.info { "Now playing message set for guild $guildId" }
        }
    }

    fun getLastNowPlayingMessage(guildId: Long): Message? {
        return lock.read {
            guildLastNowPlayingMessage[guildId]?.also {
                logger.info { "Retrieved now playing message ${it.idLong} for guild $guildId" }
            } ?: run {
                logger.info { "No now playing message found for guild $guildId" }
                null
            }
        }
    }

    fun resetNowPlayingMessage(guildId: Long) {
        lock.write {
            val message = guildLastNowPlayingMessage[guildId]
            if (message != null) {
                logger.info { "Attempting to reset now playing message ${message.idLong} for guild $guildId" }
                try {
                    message.delete().submit()
                        .whenComplete { _, error ->
                            if (error != null) {
                                logger.error(error) { "Failed to delete now playing message ${message.idLong} for guild $guildId" }
                            } else {
                                guildLastNowPlayingMessage.remove(guildId)
                                scheduler.shutdownNow()
                                logger.info { "Deleted now playing message ${message.idLong} for guild $guildId" }
                            }
                        }
                } catch (e: Exception) {
                    logger.error(e) { "Exception occurred while deleting now playing message ${message.idLong} for guild $guildId" }
                }
            } else {
                guildLastNowPlayingMessage.remove(guildId) // Ensure entry is removed if it was already absent
                scheduler.shutdownNow()
                logger.info { "No now playing message to reset for guild $guildId" }
            }
        }
    }

    fun scheduleNowPlayingUpdate(guildId: Long, track: AudioTrack, audioPlayer: AudioPlayer, delay: Long, period: Long) {
        scheduler = Executors.newScheduledThreadPool(1)
        scheduler.scheduleAtFixedRate({
            updateNowPlayingMessage(guildId, track, audioPlayer)
        }, delay, period, TimeUnit.SECONDS)
    }

    // Update now playing message periodically
    private fun updateNowPlayingMessage(guildId: Long, track: AudioTrack, audioPlayer: AudioPlayer) {
        lock.read {
            val message = guildLastNowPlayingMessage[guildId]
            message?.let {
                val embed = buildNowPlayingMessageData(track, audioPlayer.volume, audioPlayer.isPaused)
                it.editMessageEmbeds(embed).queue()
                logger.info { "Updated now playing message ${it.idLong} for guild $guildId" }
            }
        }
    }

    fun buildNowPlayingMessageData(track: AudioTrack, audioPlayer: AudioPlayer): MessageEmbed = buildNowPlayingMessageData(track, audioPlayer.volume, audioPlayer.isPaused)

    private fun buildNowPlayingMessageData(track: AudioTrack, volume: Int, isPaused: Boolean): MessageEmbed {
        val info = track.info
        val descriptionBuilder = StringBuilder()

        descriptionBuilder.append("**Title**: `${info.title}`\n").append("**Author**: `${info.author}`\n")

        if (!info.isStream) {
            val songPosition = formatTime(track.position)
            val songDuration = formatTime(track.duration)
            descriptionBuilder.append("**Progress**: `$songPosition / $songDuration`\n")
        } else {
            descriptionBuilder.append("**Stream**: `Live`\n")
        }

        val embed = EmbedBuilder()
            .setTitle("Now Playing")
            .setDescription(descriptionBuilder.toString())
            .addField("Volume", "$volume", true)
            .setColor(Color.GREEN)
            .setFooter("Link: ${info.uri}", null)
            .addField("Paused?", if (isPaused) "yes" else "no", false)
            .build()

        return embed
    }

    fun shutdownExecutor() = scheduler.shutdownNow()


    fun clear() {
        lock.write {
            guildLastNowPlayingMessage.clear()
            scheduler.shutdownNow()
        }
    }
}

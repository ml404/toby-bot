package bot.toby.managers

import bot.toby.helpers.MusicPlayerHelper.formatTime
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import common.logging.DiscordLogger
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class NowPlayingManager(
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(10),
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val guildLastNowPlayingMessage = ConcurrentHashMap<Long, Message>()
    private val scheduledTasks = ConcurrentHashMap<Long, ScheduledFuture<*>>()
    private val lock = ReentrantReadWriteLock()

    fun setNowPlayingMessage(guildId: Long, message: Message) {
        lock.write {
            logger.info { "Setting now playing message ${message.idLong} for guild $guildId" }
            guildLastNowPlayingMessage[guildId] = message
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
            logger.info { "Resetting now playing message and cancelling scheduler for guild $guildId" }
            val message = guildLastNowPlayingMessage.remove(guildId)
            scheduledTasks.remove(guildId)?.cancel(true)

            if (message != null) {
                logger.info { "Attempting to delete now playing message ${message.idLong} for guild $guildId" }
                message.delete().submit().whenComplete { _, error ->
                    if (error != null) {
                        logger.error{ "Failed to delete now playing message ${message.idLong} for guild $guildId" }
                    } else {
                        logger.info { "Deleted now playing message ${message.idLong} for guild $guildId" }
                    }
                }
            } else {
                logger.info { "No now playing message to reset" }
            }
        }
    }

    fun scheduleNowPlayingUpdate(
        guildId: Long,
        track: AudioTrack,
        audioPlayer: AudioPlayer,
        delay: Long,
        period: Long,
        clipStart: Long? = null,
        clipEnd: Long? = null
    ) {
        cancelScheduledTask(guildId)
        val scheduledTask = scheduler.scheduleAtFixedRate({
            try {
                updateNowPlayingMessage(guildId, track, audioPlayer, clipStart, clipEnd)
            } catch (e: Exception) {
                logger.error{ "Error occurred while updating now playing message" }
            }
        }, delay, period, TimeUnit.SECONDS)

        scheduledTasks[guildId] = scheduledTask
    }

    private fun updateNowPlayingMessage(
        guildId: Long,
        track: AudioTrack,
        audioPlayer: AudioPlayer,
        clipStart: Long?,
        clipEnd: Long?
    ) {
        lock.read {
            val message = guildLastNowPlayingMessage[guildId]
            message?.let {
                val embed = buildNowPlayingMessageData(track, audioPlayer.volume, audioPlayer.isPaused, clipStart, clipEnd)
                message.editMessageEmbeds(embed).queue()
                logger.info { "Updated now playing message ${message.idLong}" }
            }
        }
    }

    fun buildNowPlayingMessageData(
        track: AudioTrack,
        audioPlayer: AudioPlayer,
        clipStart: Long? = null,
        clipEnd: Long? = null
    ): MessageEmbed {
        return buildNowPlayingMessageData(track, audioPlayer.volume, audioPlayer.isPaused, clipStart, clipEnd)
    }

    private fun buildNowPlayingMessageData(
        track: AudioTrack,
        volume: Int,
        isPaused: Boolean,
        clipStart: Long? = null,
        clipEnd: Long? = null
    ): MessageEmbed {
        val info = track.info
        val descriptionBuilder = StringBuilder()

        descriptionBuilder.append("**Title**: `${info.title}`\n").append("**Author**: `${info.author}`\n")

        if (!info.isStream) {
            val effectiveStart = clipStart ?: 0L
            val effectiveEnd = clipEnd ?: track.duration
            val songPosition = formatTime((track.position - effectiveStart).coerceAtLeast(0L))
            val songDuration = formatTime((effectiveEnd - effectiveStart).coerceAtLeast(0L))
            descriptionBuilder.append("**Progress**: `$songPosition / $songDuration`\n")
        } else {
            descriptionBuilder.append("**Stream**: `Live`\n")
        }

        return EmbedBuilder()
            .setTitle("Now Playing")
            .setDescription(descriptionBuilder.toString())
            .addField("Volume", "$volume", true)
            .setColor(Color.GREEN)
            .setFooter("Link: ${info.uri}", null)
            .addField("Paused?", if (isPaused) "yes" else "no", false)
            .build()
    }

    fun cancelScheduledTask(guildId: Long) {
        lock.write {
            scheduledTasks.remove(guildId)?.cancel(true)
        }
    }

    fun clear() {
        lock.write {
            guildLastNowPlayingMessage.clear()
            scheduledTasks.values.forEach { it.cancel(true) }
            scheduledTasks.clear()
        }
    }
}

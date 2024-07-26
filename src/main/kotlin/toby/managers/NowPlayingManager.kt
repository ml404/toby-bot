package toby.managers

import mu.KotlinLogging
import net.dv8tion.jda.api.entities.Message
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val logger = KotlinLogging.logger {}

class NowPlayingManager {

    private val guildLastNowPlayingMessage = ConcurrentHashMap<Long, Message>()
    private val lock = ReentrantReadWriteLock()

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
                                logger.info { "Deleted now playing message ${message.idLong} for guild $guildId" }
                            }
                        }
                } catch (e: Exception) {
                    logger.error(e) { "Exception occurred while deleting now playing message ${message.idLong} for guild $guildId" }
                }
            } else {
                guildLastNowPlayingMessage.remove(guildId) // Ensure entry is removed if it was already absent
                logger.info { "No now playing message to reset for guild $guildId" }
            }
        }
    }


    fun clear() {
        lock.write {
            guildLastNowPlayingMessage.clear()
        }
    }
}

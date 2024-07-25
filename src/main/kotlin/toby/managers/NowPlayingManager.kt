package toby.managers

import mu.KotlinLogging
import net.dv8tion.jda.api.entities.Message
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val logger = KotlinLogging.logger {}

class NowPlayingManager {

    private val guildLastNowPlayingMessage = mutableMapOf<Long, Message>()
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
            guildLastNowPlayingMessage[guildId]?.also { logger.info { "Retrieved now playing message ${it.idLong} for guild $guildId" }
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
                message.delete().queue()
                guildLastNowPlayingMessage.remove(guildId)
                logger.info { "Deleted now playing message ${message.idLong} for guild $guildId" }
            }
        }
    }

    fun clear() {
        lock.write {
            logger.info { "Clearing all now playing messages" }
            guildLastNowPlayingMessage.clear()
            logger.info { "All now playing messages cleared" }
        }
    }
}

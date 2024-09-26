package toby.logging

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.slf4j.MDC

class DiscordLogger(clazz: Class<*>) {
    // Dynamically fetches the logger for the calling class
    private val logger: Logger = LogManager.getLogger(clazz)

    fun setGuildAndUserContext(guild: Guild?, member: Member?) {
        guild?.let {
            MDC.put("guildName", it.name)
            MDC.put("guildId", it.id)
        }
        member?.let {
            MDC.put("userName", it.effectiveName)
            MDC.put("userId", it.id)
        }
    }

    fun clearContext() {
        MDC.clear()
    }

    // Logging methods with contextual info
    fun info(message: String) {
        logger.info(message)
    }

    fun warn(message: String) {
        logger.warn(message)
    }

    fun error(message: String) {
        logger.error(message)
    }

    // Optional: Lambda-based logging
    fun info(message: () -> String) {
        logger.info(message())
    }

    // Optional: Lambda-based logging
    fun warn(message: () -> String) {
        logger.warn(message())
    }

    // Optional: Lambda-based logging
    fun error(message: () -> String) {
        logger.error (message())
    }

    companion object {
        // Create a new logger instance dynamically for each class
        fun createLogger(clazz: Class<*>): DiscordLogger {
            return DiscordLogger(clazz)
        }
    }
}

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
        // Clear existing MDC context
        MDC.clear()

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

    // Construct a dynamic log message based on the context
    private fun buildLogMessage(message: String): String {
        val guildName = MDC.get("guildName") ?: ""
        val guildId = MDC.get("guildId") ?: ""
        val userName = MDC.get("userName") ?: ""
        val userId = MDC.get("userId") ?: ""

        val guildInfo = if (guildName.isNotBlank()) "[Guild: '$guildName' (ID: $guildId)]" else ""
        val userInfo = if (userName.isNotBlank()) "[User: '$userName' (ID: $userId)]" else ""

        return "$guildInfo$userInfo - $message"
    }

    // Logging methods with contextual info
    fun info(message: String) {
        logger.info(buildLogMessage(message)) // Log the constructed message
    }

    fun warn(message: String) {
        logger.warn(buildLogMessage(message)) // Log the constructed message
    }

    fun error(message: String) {
        logger.error(buildLogMessage(message)) // Log the constructed message
    }

    // Optional: Lambda-based logging
    fun info(message: () -> String) {
        logger.info(buildLogMessage(message())) // Log the constructed message
    }

    fun warn(message: () -> String) {
        logger.warn(buildLogMessage(message())) // Log the constructed message
    }

    fun error(message: () -> String) {
        logger.error(buildLogMessage(message())) // Log the constructed message
    }

    companion object {
        // Create a new logger instance dynamically for each class
        fun createLogger(clazz: Class<*>): DiscordLogger {
            return DiscordLogger(clazz)
        }
    }
}

package toby.logging

import mu.KotlinLogging
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.slf4j.MDC

class DiscordLogger {

    // Dynamically fetches the logger for the calling class
    private val logger = KotlinLogging.logger {}

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
        logger.info { message }
    }

    fun warn(message: String) {
        logger.warn { message }
    }

    fun error(message: String) {
        logger.error { message }
    }

    // Optional: Lambda-based logging
    fun info(message: () -> String) {
        logger.info { message() }
    }

    // Optional: Lambda-based logging
    fun warn(message: () -> String) {
        logger.warn { message() }
    }

    // Optional: Lambda-based logging
    fun error(message: () -> String) {
        logger.error { message() }
    }

    companion object {
        // Create a new logger instance dynamically for each class
        fun createLogger(): DiscordLogger {
            return DiscordLogger()
        }
    }
}

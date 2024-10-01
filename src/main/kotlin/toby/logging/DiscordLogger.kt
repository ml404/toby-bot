package toby.logging

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.slf4j.MDC

class DiscordLogger(clazz: Class<*>) {
    // Dynamically fetches the logger for the calling class
    private val logger: Logger = LogManager.getLogger(clazz)

    fun setGuildAndMemberContext(guild: Guild?, member: Member?) {
        // Clear existing MDC context
        MDC.clear()

        setGuildContext(guild)
        setMemberContext(member)
    }

    fun setGuildAndUserContext(guild: Guild?, user: User?) {
        // Clear existing MDC context
        MDC.clear()

        setGuildContext(guild)
        setUserContext(user)
    }

    fun setMemberContext(member: Member?) {
        member?.let {
            MDC.put("userName", it.effectiveName)
            MDC.put("userId", it.id)
        }
    }

    fun setUserContext(user: User?) {
        user?.let {
            MDC.put("userName", it.effectiveName)
            MDC.put("userId", it.id)
        }
    }

    fun setGuildContext(guild: Guild?) {
        guild?.let {
            MDC.put("guildName", it.name)
            MDC.put("guildId", it.id)
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

        // Build Guild Info
        val guildInfo = when {
            guildName.isNotBlank() && guildId.isNotBlank() -> "[Guild: '$guildName' (ID: $guildId)]"
            guildName.isNotBlank() -> "[Guild: '$guildName']"
            guildId.isNotBlank() -> "[Guild: (ID: $guildId)]"
            else -> ""
        }

        // Build User Info
        val userInfo = when {
            userName.isNotBlank() && userId.isNotBlank() -> "[User: '$userName' (ID: $userId)]"
            userName.isNotBlank() -> "[User: '$userName']"
            userId.isNotBlank() -> "[User: (ID: $userId)]"
            else -> ""
        }

        // Combine and clean up the final log message
        return listOf(guildInfo, userInfo)
            .filter { it.isNotBlank() } // Remove empty strings
            .joinToString(" ") // Join with a space
            .let { if (it.isNotBlank()) "$it - $message" else message } // Include the original message
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

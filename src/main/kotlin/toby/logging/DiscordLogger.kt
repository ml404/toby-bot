package toby.logging

import mu.KLogger
import mu.KotlinLogging
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import java.util.concurrent.ConcurrentHashMap

interface CustomLogger {
    fun info(message: String)
    fun info(message: () -> String) // Lambda method
    fun warn(message: String)
    fun warn(message: () -> String) // Lambda method
    fun error(message: String)
    fun error(message: () -> String) // Lambda method
    fun setUserContext(member: Member?): DiscordLogger
}

class DiscordLogger(
    private val logger: KLogger = KotlinLogging.logger {},
    private val guildContext: String = "",
    private var userContext: String = ""
) : CustomLogger {

    override fun info(message: String) {
        logger.info { "[INFO] $guildContext$userContext $message" }
    }

    override fun info(message: () -> String) {
        logger.info { "[INFO] $guildContext$userContext ${message()}" }
    }

    override fun warn(message: String) {
        logger.warn { "[WARN] $guildContext$userContext $message" }
    }

    override fun warn(message: () -> String) {
        logger.warn { "[WARN] $guildContext$userContext ${message()}" }
    }

    override fun error(message: String) {
        logger.error { "[ERROR] $guildContext$userContext $message" }
    }

    override fun error(message: () -> String) {
        logger.error { "[ERROR] $guildContext$userContext ${message()}" }
    }

    // Replaces user context with the new member's context
    override fun setUserContext(member: Member?): DiscordLogger {
        this.userContext = member?.let { "[User: '${it.effectiveName}' (ID: '${it.idLong}')] | " } ?: ""
        return this
    }

    companion object {
        // Map to store and share logger instances per guild
        private val guildLoggers: ConcurrentHashMap<Long, DiscordLogger> = ConcurrentHashMap()

        // Get or create logger for the guild
        fun getLoggerForGuild(guild: Guild): DiscordLogger {
            return guildLoggers.computeIfAbsent(guild.idLong) {
                DiscordLogger().apply {
                    logger.info { "[INFO] Initialised logger for guild '${guild.name}' (ID: '${guild.idLong}')" }
                }.setGuildContext(guild)
            }
        }

        fun getLoggerForGuildId(guildId: Long): DiscordLogger {
            return guildLoggers.getOrDefault(guildId, DiscordLogger())
        }

        // Optionally create a logger with both guild and user context
        fun createLoggerForGuildAndUser(guild: Guild, member: Member?): DiscordLogger {
            return getLoggerForGuild(guild).setUserContext(member)
        }

        // Set the guild context for the logger
        private fun DiscordLogger.setGuildContext(guild: Guild): DiscordLogger {
            return DiscordLogger(this.logger, "[Guild: '${guild.name}' (ID: '${guild.idLong}')] | ", this.userContext)
        }
    }
}

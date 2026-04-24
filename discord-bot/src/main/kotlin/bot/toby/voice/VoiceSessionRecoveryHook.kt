package bot.toby.voice

import common.logging.DiscordLogger
import database.service.VoiceSessionService
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class VoiceSessionRecoveryHook @Autowired constructor(
    private val voiceSessionService: VoiceSessionService,
    private val voiceCreditAwardService: VoiceCreditAwardService
) : InitializingBean {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun afterPropertiesSet() {
        closeStaleOpenSessions()
    }

    fun closeStaleOpenSessions() {
        val now = Instant.now()
        val open = runCatching { voiceSessionService.findAllOpenSessions() }
            .onFailure { logger.error("Could not query open voice sessions on startup: ${it.message}") }
            .getOrDefault(emptyList())

        if (open.isEmpty()) {
            logger.info { "No stale voice sessions to recover." }
            return
        }

        logger.info { "Recovering ${open.size} stale voice session(s) left open by a prior shutdown." }
        open.forEach { session ->
            runCatching { voiceCreditAwardService.closeRecoveredSession(session, now) }
                .onFailure {
                    logger.error("Failed to close stale voice session id=${session.id}: ${it.message}")
                }
        }
    }
}

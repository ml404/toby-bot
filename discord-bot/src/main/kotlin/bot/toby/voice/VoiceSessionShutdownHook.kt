package bot.toby.voice

import common.logging.DiscordLogger
import database.service.VoiceSessionService
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Closes any open voice sessions at the moment of a clean shutdown (Spring
 * context destroy — deploys, rolling restarts, SIGTERM under a sensible
 * orchestrator).
 *
 * Complements [VoiceSessionRecoveryHook], which handles the *crash* case
 * after the fact. Without this hook, every deploy leaves open sessions on
 * disk that the next boot closes using bot-wake-time — which inflates
 * counted seconds for any user that actually left voice during the outage.
 * With this hook, clean deploys close sessions at their true leave moment
 * (i.e. right now) and the recovery path on the next boot has nothing to do.
 */
@Component
class VoiceSessionShutdownHook @Autowired constructor(
    private val voiceSessionService: VoiceSessionService,
    private val voiceCreditAwardService: VoiceCreditAwardService
) : DisposableBean {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun destroy() {
        closeOpenSessions()
    }

    fun closeOpenSessions() {
        val now = Instant.now()
        val open = runCatching { voiceSessionService.findAllOpenSessions() }
            .onFailure { logger.error("Could not query open voice sessions on shutdown: ${it.message}") }
            .getOrDefault(emptyList())

        if (open.isEmpty()) {
            logger.info { "No live voice sessions to flush on shutdown." }
            return
        }

        logger.info { "Flushing ${open.size} live voice session(s) before shutdown." }
        open.forEach { session ->
            runCatching { voiceCreditAwardService.closeSessionAtShutdown(session, now) }
                .onFailure {
                    logger.error("Failed to close voice session id=${session.id} on shutdown: ${it.message}")
                }
        }
    }
}

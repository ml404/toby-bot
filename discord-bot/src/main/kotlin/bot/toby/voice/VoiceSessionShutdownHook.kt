package bot.toby.voice

import common.logging.DiscordLogger
import database.service.VoiceSessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Closes any open voice sessions at the moment of a clean shutdown — deploys,
 * rolling restarts, SIGTERM under a sensible orchestrator.
 *
 * Listens for [ContextClosedEvent] rather than implementing [DisposableBean]
 * because destroy() runs *during* bean teardown — by then the JPA EntityManager,
 * the AOP @Transactional interceptor, or the connection pool may already be
 * partially destroyed, and the merge/flush calls go silently nowhere (or throw,
 * which the per-session runCatching swallows). ContextClosedEvent fires at the
 * very *start* of context shutdown, while the entire container is still alive,
 * so the persistence write actually lands.
 *
 * Complements [VoiceSessionRecoveryHook], which handles the *crash* case after
 * the fact. Without this hook, every deploy leaves open sessions on disk that
 * the next boot closes using bot-wake-time — which inflates counted seconds
 * for any user who actually left voice during the outage. With this hook,
 * clean deploys close sessions at their true leave moment (i.e. right now)
 * and the recovery path on the next boot has nothing to do.
 */
@Component
class VoiceSessionShutdownHook @Autowired constructor(
    private val voiceSessionService: VoiceSessionService,
    private val voiceCreditAwardService: VoiceCreditAwardService
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @EventListener(ContextClosedEvent::class)
    fun onContextClosed() {
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
        var flushed = 0
        open.forEach { session ->
            runCatching { voiceCreditAwardService.closeSessionAtShutdown(session, now) }
                .onSuccess {
                    flushed++
                    logger.info {
                        "Flushed voice session id=${session.id} for user=${session.discordId} " +
                                "guild=${session.guildId} on shutdown."
                    }
                }
                .onFailure {
                    logger.error("Failed to close voice session id=${session.id} on shutdown: ${it.message}")
                }
        }
        logger.info { "Voice session flush complete: $flushed/${open.size} succeeded." }
    }
}

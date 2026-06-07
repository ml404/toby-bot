package bot.toby.handler

import bot.toby.notify.AntiAutoclickNotifier
import bot.toby.voice.VoiceCompanyTracker
import common.logging.DiscordLogger
import database.blackjack.BlackjackTableRegistry
import database.poker.CasinoHoldemTableRegistry
import database.poker.PokerTableRegistry
import database.service.activity.InstallEventService
import database.service.casino.CasinoBotSuspicionService
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.stereotype.Service
import web.service.MusicSseService

/**
 * Central per-guild cache eviction on guild leave. Each registry/cache
 * that keys state by guildId exposes an `evictGuild` method; this
 * listener fans the leave event out to all of them so memory doesn't
 * drift upward over the bot's lifetime as guilds come and go.
 *
 * [VoiceEventHandler.onGuildLeave] already clears the music-side caches
 * (PlayerManager, NowPlayingManager, last-connected channel) so those
 * are intentionally not duplicated here.
 */
@Service
class GuildLeaveCleanupHandler(
    private val pokerTableRegistry: PokerTableRegistry,
    private val blackjackTableRegistry: BlackjackTableRegistry,
    private val casinoHoldemTableRegistry: CasinoHoldemTableRegistry,
    private val musicSseService: MusicSseService,
    private val antiAutoclickNotifier: AntiAutoclickNotifier,
    private val casinoBotSuspicionService: CasinoBotSuspicionService,
    private val voiceCompanyTracker: VoiceCompanyTracker,
    private val installEventService: InstallEventService,
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onGuildLeave(event: GuildLeaveEvent) {
        val guildId = event.guild.idLong
        logger.info { "Guild $guildId left — evicting per-guild caches" }
        // Record the LEAVE for the operator churn dashboard before the
        // cache eviction fan-out (order is irrelevant, but keeping it first
        // means the metric is captured even if an evict call later throws).
        runCatching { installEventService.recordLeave(guildId) }
            .onFailure { logger.error { "Failed to record LEAVE for guild $guildId: ${it.message}" } }
        runCatching { pokerTableRegistry.evictGuild(guildId) }
        runCatching { blackjackTableRegistry.evictGuild(guildId) }
        runCatching { casinoHoldemTableRegistry.evictGuild(guildId) }
        runCatching { musicSseService.evictGuild(guildId) }
        runCatching { antiAutoclickNotifier.evictGuild(guildId) }
        runCatching { casinoBotSuspicionService.evictGuild(guildId) }
        runCatching { voiceCompanyTracker.evictGuild(guildId) }
    }
}

package web.service

import core.managers.CommandManager
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import web.catalog.GameCatalog
import java.util.concurrent.atomic.AtomicReference

/**
 * Live numbers for the homepage. Counts:
 *  - **servers**: how many guilds the bot is currently in (JDA cache size)
 *  - **commands**: total slash-command count across every category
 *  - **games / minigames**: derived from [GameCatalog] so the stats strip,
 *    hero text, and casino feature card all stay in lockstep when a game
 *    is added or removed.
 *
 * Memoised for 60 seconds in-process so a homepage refresh storm doesn't
 * touch the JDA cache or sum command lists per request. We deliberately
 * avoid `@Cacheable` here — the wider project doesn't use Spring Cache
 * abstractions in the web module, and a tiny atomic-reference memoize
 * keeps the dependency surface small.
 */
@Service
class HomeStatsService(
    private val jda: JDA,
    private val commandManager: CommandManager,
) {

    data class HomeStats(
        val serverCount: Int,
        val commandCount: Int,
        val gameCount: Int,
        val minigameCount: Int,
        val minigameNames: String,
    )

    private data class Cached(val stats: HomeStats, val expiresAtNanos: Long)

    private val cached = AtomicReference<Cached?>(null)

    fun get(): HomeStats {
        val now = System.nanoTime()
        val current = cached.get()
        if (current != null && now < current.expiresAtNanos) return current.stats
        val fresh = compute()
        cached.set(Cached(fresh, now + TTL_NANOS))
        return fresh
    }

    private fun compute(): HomeStats = HomeStats(
        serverCount = jda.guildCache.size().toInt(),
        commandCount = commandManager.run {
            musicCommands.size +
                dndCommands.size +
                moderationCommands.size +
                economyCommands.size +
                miscCommands.size +
                fetchCommands.size
        },
        gameCount = GameCatalog.total,
        minigameCount = GameCatalog.minigameCount,
        minigameNames = GameCatalog.minigameNames,
    )

    companion object {
        private val TTL_NANOS = 60_000_000_000L
    }
}

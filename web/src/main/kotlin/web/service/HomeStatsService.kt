package web.service

import core.managers.CommandManager
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference

/**
 * Live numbers for the homepage stats strip. Three counts:
 *  - **servers**: how many guilds the bot is currently in (JDA cache size)
 *  - **commands**: total slash-command count across every category
 *  - **games**: hand-rolled count of standalone game UIs (9 minigames +
 *    Poker + Blackjack + Casino Hold'em). Hardcoded because the games
 *    list rarely changes and bumping a constant here is the cheapest
 *    signal of "we shipped a new game" for a future PR.
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
        gameCount = GAME_COUNT,
    )

    companion object {
        // 9 minigames (slots, coinflip, dice, highlow, scratch, keno,
        // roulette, baccarat, casino hold'em) + poker + blackjack +
        // lottery = 12. Keep in sync with the navbar Play menu.
        private const val GAME_COUNT = 12
        private val TTL_NANOS = 60_000_000_000L
    }
}

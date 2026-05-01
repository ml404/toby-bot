package web.util

import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Component

/**
 * Single source of truth for "is this Discord user currently a member of
 * this guild?". Previously each per-area web service
 * (EconomyWebService, LeaderboardWebService, ProfileWebService,
 * TitlesWebService) shipped its own copy of the same two-line
 * `jda.getGuildById(g)?.getMemberById(d) != null` check; tweaking the
 * membership rule (e.g. bot exclusion, caching, bans list) had to
 * happen in four places.
 *
 * The per-service `isMember` methods now delegate here so external
 * callers (controllers passing `service::isMember` to [WebGuildAccess])
 * keep working, but the actual JDA call lives in one place.
 */
@Component
class GuildMembership(private val jda: JDA) {

    fun isMember(discordId: Long, guildId: Long): Boolean {
        val guild = jda.getGuildById(guildId) ?: return false
        return guild.getMemberById(discordId) != null
    }
}

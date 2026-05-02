package web.service

import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service

/**
 * Centralised "who is this Discord id, displayed for humans" lookup.
 * Several web services (leaderboard, intros, blackjack/poker projections)
 * all want the same triple — `(name, avatarUrl)` from a `(guildId, discordId)`
 * — and were duplicating the JDA dance. This helper consolidates so
 * tweaks (e.g. swap to `globalName`, add caching, fall back to a default
 * avatar URL) land in one place.
 *
 * Permission-style member access (e.g. checking `Member.isOwner` or
 * `canInteract`) intentionally stays inline at the call site — those
 * callers want the raw `Member`, not a display projection.
 */
@Service
class MemberLookupHelper(private val jda: JDA) {

    /** Fallback display name used when the member isn't resolvable in the guild. */
    fun fallbackName(discordId: Long): String = "Player ${discordId.toString().takeLast(4)}"

    /**
     * Resolve one member's display info. Returns `null` if the bot isn't
     * in the guild OR the member has left it; callers should fall back
     * to [fallbackName] in that case.
     */
    fun resolve(guildId: Long, discordId: Long): MemberDisplay? {
        val guild = jda.getGuildById(guildId) ?: return null
        val member = guild.getMemberById(discordId) ?: return null
        return MemberDisplay(
            name = member.effectiveName,
            avatarUrl = member.effectiveAvatarUrl,
        )
    }

    /**
     * Batch version — fetches the [Guild] once and resolves each id
     * against it. Ids whose member can't be found are simply absent
     * from the returned map (so callers can default to [fallbackName]).
     * Empty input returns an empty map without touching JDA.
     */
    fun resolveAll(guildId: Long, ids: Collection<Long>): Map<Long, MemberDisplay> {
        if (ids.isEmpty()) return emptyMap()
        val guild = jda.getGuildById(guildId) ?: return emptyMap()
        val out = HashMap<Long, MemberDisplay>(ids.size)
        for (id in ids) {
            val member = guild.getMemberById(id) ?: continue
            out[id] = MemberDisplay(name = member.effectiveName, avatarUrl = member.effectiveAvatarUrl)
        }
        return out
    }

    data class MemberDisplay(val name: String, val avatarUrl: String?)
}

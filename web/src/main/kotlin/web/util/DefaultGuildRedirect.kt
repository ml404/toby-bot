package web.util

/**
 * Shared "skip the picker if we can" decision used by every
 * `/{thing}/guilds` controller. Returns the guild id the picker should
 * redirect to, or null when the picker has to render.
 *
 * Resolution order:
 *   1. `pick=true` query bypass → null (render the picker)
 *   2. No mutual guilds → null (empty-state hero renders)
 *   3. Cookie set AND the user still shares that guild → cookie value
 *   4. Single mutual guild → that guild's id
 *   5. Otherwise null (multi-guild without anchor → picker)
 *
 * Kept as a pure function so every controller's redirect rules are
 * trivially testable in isolation — controller tests just verify the
 * service call wiring around it.
 */
object DefaultGuildRedirect {

    fun pick(
        guildIds: List<Long>,
        cookieGuildId: Long?,
        pick: Boolean,
    ): Long? {
        if (pick) return null
        if (guildIds.isEmpty()) return null
        if (cookieGuildId != null && cookieGuildId in guildIds) return cookieGuildId
        return guildIds.singleOrNull()
    }
}

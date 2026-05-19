package web.controller.support

import web.util.DefaultGuildRedirect

/**
 * Collapses the six-line guild-picker redirect block that every
 * `/{thing}/guilds` controller hand-rolls:
 *
 * ```
 * DefaultGuildRedirect.pick(
 *     guildIds = guilds.mapNotNull { it.id.toLongOrNull() },
 *     cookieGuildId = defaultGuildId,
 *     pick = pick,
 * )?.let { return "redirect:/thing/$it" }
 * ```
 *
 * into a single call. Returns the `redirect:` string when the picker
 * should bypass itself, or `null` when the controller should render
 * the picker page.
 *
 * Callers read [web.util.DefaultGuildCookie] themselves and pass the
 * value through; they typically also need it for a `defaultGuildId`
 * model attribute on the picker page.
 *
 * The redirect path stays in the caller — each controller's URL
 * layout (`/casino/$id/lottery`, `/blackjack/$id/solo`) is different
 * enough that forcing a single template here would just move
 * duplication around.
 */
object GuildPickerSupport {

    /**
     * @param guildIds the long ids of guilds the user can view (the
     *   caller does its own access filtering — this helper doesn't
     *   know what "can view" means for the calling controller).
     * @param cookieGuildId the cookie-resolved default guild, or null.
     * @param pick `true` when the caller wants to force the picker to
     *   render even if a deep-link is available (e.g. `?pick=true`).
     * @param redirectFor produces the redirect target path for a
     *   resolved guild id. Returns just the path, not the `redirect:`
     *   prefix — this helper adds that.
     * @return `"redirect:$path"` when [DefaultGuildRedirect.pick] chose
     *   a guild, otherwise `null`.
     */
    inline fun resolveRedirect(
        guildIds: List<Long>,
        cookieGuildId: Long?,
        pick: Boolean,
        redirectFor: (Long) -> String,
    ): String? {
        val target = DefaultGuildRedirect.pick(guildIds, cookieGuildId, pick) ?: return null
        return "redirect:${redirectFor(target)}"
    }
}

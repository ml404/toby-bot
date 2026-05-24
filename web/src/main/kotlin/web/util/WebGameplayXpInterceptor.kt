package web.util

import common.leveling.XpAmounts
import database.service.leveling.XpAwardService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

/**
 * Mirrors [bot.toby.handler.SlashCommandEventListener] on the web side:
 * after a successful POST to a gameplay/economy endpoint, award the same
 * [XpAmounts.COMMAND_XP] grant the Discord listener pays per slash
 * invocation. Wiring it as a [HandlerInterceptor] keeps the ~17
 * gameplay/economy controllers free of per-method XP-award boilerplate
 * and gives us one chokepoint to audit, the same way [WebGuildAccess]
 * collapses the per-method membership check.
 *
 * Conditions for paying out (every guard short-circuits to no-op):
 *  - `POST` only — `GET` is page render / polling, not engagement.
 *  - Response status is 2xx — `WebGuildAccess` already 401/403's
 *    non-members, and casino common failures map to 4xx, so a failing
 *    "spin while broke" doesn't pay.
 *  - The matched route has a `{guildId}` path variable that parses to a
 *    `Long` — drops the few global gameplay surfaces (e.g. lottery guild
 *    selector) that don't bind to a single guild.
 *  - The authenticated principal exposes a Discord id via
 *    [discordIdOrNull] — anonymous webhook traffic or test requests with
 *    no security context never reach the award call.
 *
 * Daily cap, level-up event publishing, and unknown-user no-op all live
 * inside [XpAwardService.award], so we don't need to re-implement any of
 * that here.
 */
@Component
class WebGameplayXpInterceptor(
    private val xpAwardService: XpAwardService,
) : HandlerInterceptor {

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        if (!request.method.equals("POST", ignoreCase = true)) return
        if (response.status !in 200..299) return

        val guildId = request.guildIdFromPath() ?: return
        val discordId = request.authenticatedDiscordId() ?: return

        xpAwardService.award(
            discordId = discordId,
            guildId = guildId,
            amount = XpAmounts.COMMAND_XP,
            reason = "web:${request.requestURI}",
            channelId = null,
        )
    }

    private fun HttpServletRequest.guildIdFromPath(): Long? {
        @Suppress("UNCHECKED_CAST")
        val vars = getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
            as? Map<String, String> ?: return null
        return vars["guildId"]?.toLongOrNull()
    }

    private fun HttpServletRequest.authenticatedDiscordId(): Long? {
        val principal = SecurityContextHolder.getContext()?.authentication?.principal
        val oAuthUser = principal as? OAuth2User ?: return null
        return oAuthUser.discordIdOrNull()
    }
}

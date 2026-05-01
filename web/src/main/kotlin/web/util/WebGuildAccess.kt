package web.util

import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.EconomyWebService

/**
 * Two-line access guards for the "is this OAuth2 user signed in AND a
 * member of this guild" check that opens nearly every per-guild
 * controller endpoint.
 *
 * Page-rendering controllers (`/duel/{guildId}`, `/casino/{guildId}/...`,
 * `/poker/{guildId}/...`) all redirect anonymous or non-member callers
 * back to the appropriate lobby with an `error` flash. JSON endpoints
 * (`/.../state`, `/.../action` etc.) all return 401/403 with a body
 * shape the caller chooses.
 *
 * Wrapping the boilerplate here means:
 *   - "Member of guild" is enforced in exactly one place — easier to
 *     audit, easier to change (e.g. add a ban list, throttle, etc).
 *   - Each endpoint shrinks to one call + the actual logic, so the
 *     interesting code is no longer buried under five lines of guard
 *     scaffolding.
 *
 * Two flavours of membership check are supported:
 *   - The common case (`isMember`) — controller passes
 *     [EconomyWebService] and the helper calls
 *     `economyWebService.isMember(...)`.
 *   - A custom check (e.g. `canModerate`, a stricter ACL) — controller
 *     passes a `(discordId, guildId) -> Boolean` lambda plus the
 *     access-denied message that should appear in the redirect flash.
 */
object WebGuildAccess {

    @PublishedApi
    internal const val NOT_A_MEMBER = "You are not a member of that server."

    /**
     * Page wrapper: validates auth + guild membership; on either failure
     * redirects to [lobbyPath] with a flash error; otherwise calls
     * [block] with the resolved discord id and returns whatever it
     * returns (template name or another redirect).
     */
    inline fun requireMemberForPage(
        user: OAuth2User?,
        guildId: Long,
        economyWebService: EconomyWebService,
        ra: RedirectAttributes,
        lobbyPath: String,
        block: (discordId: Long) -> String,
    ): String = requireForPage(
        user = user,
        guildId = guildId,
        ra = ra,
        lobbyPath = lobbyPath,
        check = economyWebService::isMember,
        deniedMessage = NOT_A_MEMBER,
        block = block,
    )

    /**
     * Page wrapper that takes any `(discordId, guildId) -> Boolean`
     * check. Use when the access predicate isn't plain "is a guild
     * member" — e.g. ModerationController's `canModerate`.
     */
    inline fun requireForPage(
        user: OAuth2User?,
        guildId: Long,
        ra: RedirectAttributes,
        lobbyPath: String,
        check: (discordId: Long, guildId: Long) -> Boolean,
        deniedMessage: String = NOT_A_MEMBER,
        block: (discordId: Long) -> String,
    ): String {
        val discordId = user?.discordIdOrNull()
            ?: return "redirect:$lobbyPath"
        if (!check(discordId, guildId)) {
            ra.addFlashAttribute("error", deniedMessage)
            return "redirect:$lobbyPath"
        }
        return block(discordId)
    }

    /**
     * JSON wrapper with typed error bodies. Callers supply [errorBuilder]
     * so the 401/403 responses match their endpoint's response shape
     * (e.g. `TableActionResponse(false, error = ...)`).
     */
    inline fun <T : Any> requireMemberForJson(
        user: OAuth2User?,
        guildId: Long,
        economyWebService: EconomyWebService,
        errorBuilder: (httpStatus: Int) -> ResponseEntity<T>,
        block: (discordId: Long) -> ResponseEntity<T>,
    ): ResponseEntity<T> = requireForJson(
        user = user,
        guildId = guildId,
        check = economyWebService::isMember,
        errorBuilder = errorBuilder,
        block = block,
    )

    /**
     * JSON wrapper variant taking any membership predicate.
     */
    inline fun <T : Any> requireForJson(
        user: OAuth2User?,
        guildId: Long,
        check: (discordId: Long, guildId: Long) -> Boolean,
        errorBuilder: (httpStatus: Int) -> ResponseEntity<T>,
        block: (discordId: Long) -> ResponseEntity<T>,
    ): ResponseEntity<T> {
        val discordId = user?.discordIdOrNull() ?: return errorBuilder(401)
        if (!check(discordId, guildId)) return errorBuilder(403)
        return block(discordId)
    }

    /**
     * JSON wrapper variant for endpoints that return bodyless 401/403
     * (e.g. `/.../state` polling endpoints whose status code is the
     * whole signal).
     */
    inline fun <T : Any> requireMemberForJsonNoBody(
        user: OAuth2User?,
        guildId: Long,
        economyWebService: EconomyWebService,
        block: (discordId: Long) -> ResponseEntity<T>,
    ): ResponseEntity<T> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        if (!economyWebService.isMember(discordId, guildId)) return ResponseEntity.status(403).build()
        return block(discordId)
    }

    /**
     * Auth-only wrapper for page handlers that don't need a guild
     * membership check — e.g. CampaignController where membership is
     * implicit in the campaign service, or IntroWebController where the
     * superuser branch needs to bypass the standard member guard.
     * Anonymous callers redirect to [lobbyPath]; signed-in callers
     * invoke [block] with their resolved Discord id.
     */
    inline fun requireSignedInForPage(
        user: OAuth2User?,
        lobbyPath: String,
        block: (discordId: Long) -> String,
    ): String {
        val discordId = user?.discordIdOrNull() ?: return "redirect:$lobbyPath"
        return block(discordId)
    }

    /**
     * Auth-only wrapper for JSON endpoints that don't need a guild
     * membership check — the service does its own permission audit on
     * the actor (e.g. ModerationController, IntroWebController).
     * Collapses the repeated `discordIdOrNull() ?: return 401(...)`
     * pattern into one call.
     *
     * Callers supply a [notSignedInBody] factory so the 401 body matches
     * the endpoint's response shape. Returns 401 with that body when
     * the principal isn't a real Discord user, otherwise invokes
     * [block] with the resolved id.
     */
    inline fun <T : Any> requireSignedInForJson(
        user: OAuth2User?,
        notSignedInBody: () -> T,
        block: (discordId: Long) -> ResponseEntity<T>,
    ): ResponseEntity<T> {
        val discordId = user?.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(notSignedInBody())
        return block(discordId)
    }
}

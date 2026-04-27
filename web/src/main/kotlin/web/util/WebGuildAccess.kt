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
 */
object WebGuildAccess {

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
        block: (discordId: Long) -> String
    ): String {
        val discordId = user?.discordIdOrNull()
            ?: return "redirect:$lobbyPath"
        if (!economyWebService.isMember(discordId, guildId)) {
            ra.addFlashAttribute("error", "You are not a member of that server.")
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
        block: (discordId: Long) -> ResponseEntity<T>
    ): ResponseEntity<T> {
        val discordId = user?.discordIdOrNull() ?: return errorBuilder(401)
        if (!economyWebService.isMember(discordId, guildId)) return errorBuilder(403)
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
        block: (discordId: Long) -> ResponseEntity<T>
    ): ResponseEntity<T> {
        val discordId = user?.discordIdOrNull() ?: return ResponseEntity.status(401).build()
        if (!economyWebService.isMember(discordId, guildId)) return ResponseEntity.status(403).build()
        return block(discordId)
    }
}

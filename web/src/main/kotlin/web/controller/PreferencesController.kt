package web.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import web.service.IntroWebService
import web.util.DefaultGuildCookie
import web.util.DefaultGuildRedirect
import web.util.displayName

/**
 * Toggles the [DefaultGuildCookie] from the star button rendered on
 * the casino / leaderboard / intro picker cards. Validates the user
 * actually shares the target guild before pinning it so a crafted
 * POST can't anchor someone to a guild they aren't in (cookie would
 * otherwise be silently ignored on the next picker visit, but the
 * cleaner contract is to refuse the write).
 */
@Controller
@RequestMapping("/preferences")
class PreferencesController(
    private val introWebService: IntroWebService,
) {

    /**
     * Tiny management page rendered from the navbar pill. Lists the
     * user's mutual guilds with a star button on each — same toggle
     * the picker pages use, just collected on one page so changing the
     * anchor doesn't require navigating to a specific feature first.
     */
    @GetMapping
    fun page(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestParam(required = false, defaultValue = "false") pick: Boolean,
        request: HttpServletRequest,
        model: Model,
    ): String {
        val guilds = if (user == null) emptyList()
            else introWebService.getMutualGuilds(client.accessToken.tokenValue)
        val defaultGuildId = DefaultGuildCookie.read(request)

        DefaultGuildRedirect.pick(
            guildIds = guilds.mapNotNull { it.id.toLongOrNull() },
            cookieGuildId = defaultGuildId,
            pick = pick,
        )?.let { return "redirect:/preferences/notifications/$it" }

        model.addAttribute("guilds", guilds)
        model.addAttribute("defaultGuildId", defaultGuildId)
        model.addAttribute("username", user.displayName())
        return "preferences"
    }

    @PostMapping("/default-guild")
    fun setDefaultGuild(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestParam guildId: Long,
        @RequestParam(required = false) redirect: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        val target = DefaultGuildCookie.sanitizeRedirect(redirect)
        if (user == null) return "redirect:$target"

        val isMutual = introWebService
            .getMutualGuilds(client.accessToken.tokenValue)
            .any { it.id.toLongOrNull() == guildId }
        if (isMutual) {
            DefaultGuildCookie.write(request, response, guildId)
        }
        return "redirect:$target"
    }

    @PostMapping("/default-guild/clear")
    fun clearDefaultGuild(
        @RequestParam(required = false) redirect: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        val target = DefaultGuildCookie.sanitizeRedirect(redirect)
        DefaultGuildCookie.clear(request, response)
        return "redirect:$target"
    }
}

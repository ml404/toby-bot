package web.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import web.service.EconomyWebService
import web.util.DefaultGuildCookie
import web.util.DefaultGuildRedirect
import web.util.discordIdOrNull
import web.util.displayName

/**
 * Casino landing pages. The minigames themselves (currently just `/slots`)
 * live in their own controllers; this is just the guild picker that the
 * Casino navbar dropdown lands on.
 *
 * Reuses [EconomyWebService.getGuildsWhereUserCanView] for the mutual-
 * guild filter — same membership check, same view-card shape, no need
 * for a parallel service method.
 */
@Controller
@RequestMapping("/casino")
class CasinoController(
    private val economyWebService: EconomyWebService
) {

    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false) game: String?,
        @RequestParam(required = false, defaultValue = "false") pick: Boolean,
        request: HttpServletRequest,
        model: Model
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            economyWebService.getGuildsWhereUserCanView(client.accessToken.tokenValue, discordId)
        } else emptyList()

        val intendedGame = game?.lowercase()?.takeIf { it in CASINO_GAME_SLUGS }
        val defaultGuildId = DefaultGuildCookie.read(request)

        // Auto-redirect only when the click carried a specific game; without
        // intent the picker doubles as a useful index (all games per server)
        // and isn't redundant in the single-guild case.
        if (intendedGame != null) {
            val targetId = DefaultGuildRedirect.pick(
                guildIds = guilds.mapNotNull { it.id.toLongOrNull() },
                cookieGuildId = defaultGuildId,
                pick = pick,
            )
            if (targetId != null) {
                return "redirect:${gamePath(targetId, intendedGame)}"
            }
        }

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("intendedGame", intendedGame)
        model.addAttribute("defaultGuildId", defaultGuildId)
        return "casino-guilds"
    }

    companion object {
        // Mirrors the game buttons rendered in casino-guilds.html (and the
        // activity landing, activity-casino.html). Lottery
        // is a casino-routed entry on the same picker even though it has
        // its own navbar item; baccarat / casino-hold'em sit under "Table
        // games" but route through /casino/{id}/...
        internal val CASINO_GAME_SLUGS = setOf(
            "coinflip", "dice", "highlow", "keno", "roulette", "scratch", "slots",
            "plinko", "horse-racing", "wheel",
            "baccarat", "casinoholdem",
            "lottery",
        )

        internal fun gamePath(guildId: Long, gameSlug: String): String =
            "/casino/$guildId/$gameSlug"
    }
}

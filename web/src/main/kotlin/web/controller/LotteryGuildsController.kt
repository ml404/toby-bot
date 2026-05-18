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
 * Dedicated lottery guild picker, mirroring the Poker / Blackjack /
 * Casino Hold'em pattern. The casino navbar dropdown lands here so a
 * user can pick which server they want to play in, then the per-guild
 * page at `/casino/{guildId}/lottery` ([LotteryController]) takes over.
 *
 * Lives in its own controller class because [LotteryController]'s
 * class-level `@RequestMapping("/casino/{guildId}/lottery")` is path-
 * variable-anchored and a sibling `/lottery/guilds` `GetMapping` would
 * fight with it. Reuses [EconomyWebService.getGuildsWhereUserCanView]
 * for the same mutual-guild filter every other casino-game picker uses.
 */
@Controller
@RequestMapping("/lottery")
class LotteryGuildsController(
    private val economyWebService: EconomyWebService,
) {

    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false, defaultValue = "false") pick: Boolean,
        request: HttpServletRequest,
        model: Model,
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            economyWebService.getGuildsWhereUserCanView(client.accessToken.tokenValue, discordId)
        } else emptyList()

        val defaultGuildId = DefaultGuildCookie.read(request)
        DefaultGuildRedirect.pick(
            guildIds = guilds.mapNotNull { it.id.toLongOrNull() },
            cookieGuildId = defaultGuildId,
            pick = pick,
        )?.let { return "redirect:/casino/$it/lottery" }

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("defaultGuildId", defaultGuildId)
        return "lottery-guilds"
    }
}

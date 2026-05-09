package web.controller

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import web.service.EconomyWebService
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
        model: Model,
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            economyWebService.getGuildsWhereUserCanView(client.accessToken.tokenValue, discordId)
        } else emptyList()

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        return "lottery-guilds"
    }
}

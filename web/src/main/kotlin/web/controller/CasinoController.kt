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
        model: Model
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            economyWebService.getGuildsWhereUserCanView(client.accessToken.tokenValue, discordId)
        } else emptyList()

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        return "casino-guilds"
    }
}

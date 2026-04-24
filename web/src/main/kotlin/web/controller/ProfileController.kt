package web.controller

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.ProfileWebService
import web.util.discordIdOrNull
import web.util.displayName

@Controller
@RequestMapping("/profile")
class ProfileController(
    private val profileWebService: ProfileWebService
) {

    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            profileWebService.getMemberGuilds(client.accessToken.tokenValue, discordId)
        } else emptyList()

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        return "profile-guilds"
    }

    @GetMapping("/{guildId}")
    fun profilePage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/profile/guilds"

        if (!profileWebService.isMember(discordId, guildId)) {
            ra.addFlashAttribute("error", "You are not a member of that server.")
            return "redirect:/profile/guilds"
        }

        val profile = profileWebService.getProfile(discordId, guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return "redirect:/profile/guilds"
        }

        model.addAttribute("profile", profile)
        model.addAttribute("username", user.displayName())
        return "profile"
    }
}

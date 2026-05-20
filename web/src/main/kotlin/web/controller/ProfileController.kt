package web.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.ProfileWebService
import web.util.DefaultGuildCookie
import web.util.DefaultGuildRedirect
import web.util.WebGuildAccess
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
        @RequestParam(required = false, defaultValue = "false") pick: Boolean,
        request: HttpServletRequest,
        model: Model
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            profileWebService.getMemberGuilds(client.accessToken.tokenValue, discordId)
        } else emptyList()

        val defaultGuildId = DefaultGuildCookie.read(request)
        DefaultGuildRedirect.pick(
            guildIds = guilds.mapNotNull { it.id.toLongOrNull() },
            cookieGuildId = defaultGuildId,
            pick = pick,
        )?.let { return "redirect:/profile/$it" }

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("defaultGuildId", defaultGuildId)
        return "profile-guilds"
    }

    @GetMapping("/{guildId}")
    fun profilePage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireForPage(
        user, guildId, ra, lobbyPath = "/profile/guilds",
        check = profileWebService::isMember,
    ) { discordId ->
        val profile = profileWebService.getProfile(discordId, guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireForPage "redirect:/profile/guilds"
        }

        model.addAttribute("profile", profile)
        model.addAttribute("username", user.displayName())
        model.addAttribute("viewerIsOwner", true)
        model.addAttribute("backLink", "/profile/guilds?pick=true")
        model.addAttribute("backLabel", "All servers")
        "profile"
    }

    // Read-only view of another guild member's profile. Anyone in the guild
    // can view anyone else's prestige stats (level/streak/achievements/owned
    // titles) — the same data that's already projected onto the leaderboard.
    // Owner-only UI (claim button, permissions, title-shop CTA) is gated on
    // `viewerIsOwner` in the template.
    @GetMapping("/{guildId}/{targetDiscordId}")
    fun publicProfile(
        @PathVariable guildId: Long,
        @PathVariable targetDiscordId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireForPage(
        user, guildId, ra, lobbyPath = "/profile/guilds",
        check = profileWebService::isMember,
    ) { viewerDiscordId ->
        // Self-clicks (e.g. from clicking your own row on the leaderboard)
        // bounce to the owner self-view so the claim/permissions flow is
        // preserved without duplicating logic here.
        if (viewerDiscordId == targetDiscordId) {
            return@requireForPage "redirect:/profile/$guildId"
        }
        val profile = profileWebService.getProfile(targetDiscordId, guildId) ?: run {
            ra.addFlashAttribute("error", "That member isn't in this server.")
            return@requireForPage "redirect:/leaderboard/$guildId"
        }

        model.addAttribute("profile", profile)
        model.addAttribute("username", user.displayName())
        model.addAttribute("viewerIsOwner", false)
        model.addAttribute("backLink", "/leaderboard/$guildId")
        model.addAttribute("backLabel", "Leaderboard")
        "profile"
    }
}

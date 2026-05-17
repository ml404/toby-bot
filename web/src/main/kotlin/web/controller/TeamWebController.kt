package web.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.TeamApiResult
import web.service.TeamWebService
import web.util.WebGuildAccess
import web.util.discordIdString
import web.util.displayName

@Controller
@RequestMapping("/teams")
class TeamWebController(
    private val teamWebService: TeamWebService,
    @param:Value($$"${spring.security.oauth2.client.registration.discord.client-id}")
    private val discordClientId: String,
) {
    private val inviteUrl: String
        get() = web.util.DiscordInvite.urlFor(discordClientId)

    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
    ): String {
        val guilds = teamWebService.getMutualGuilds(client.accessToken.tokenValue)
        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("discordId", user.discordIdString())
        model.addAttribute("inviteUrl", inviteUrl)
        return "team-guilds"
    }

    @GetMapping("/{guildId}")
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireForPage(
        user = user,
        guildId = guildId,
        ra = ra,
        lobbyPath = "/teams/guilds",
        check = teamWebService::isMember,
    ) { _ ->
        val guildName = teamWebService.getGuildName(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireForPage "redirect:/teams/guilds"
        }
        model.addAttribute("guildId", guildId)
        model.addAttribute("guildName", guildName)
        model.addAttribute("presets", teamWebService.listPresets(guildId))
        model.addAttribute("recentSplits", teamWebService.listRecentSessions(guildId))
        model.addAttribute("members", teamWebService.getGuildMembers(guildId))
        model.addAttribute("maxNameLength", TeamWebService.PRESET_NAME_MAX)
        model.addAttribute("username", user.displayName())
        "teams"
    }

    @PostMapping("/{guildId}/presets")
    fun createPreset(
        @PathVariable guildId: Long,
        @RequestParam name: String,
        @RequestParam(name = "memberIds", required = false) memberIds: List<String>?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireForPage(
        user = user,
        guildId = guildId,
        ra = ra,
        lobbyPath = "/teams/guilds",
        check = teamWebService::isMember,
    ) { discordId ->
        val error = teamWebService.upsertPreset(guildId, name, memberIds.orEmpty(), discordId)
        if (error != null) {
            ra.addFlashAttribute("error", error)
        } else {
            ra.addFlashAttribute("success", "Saved preset '${name.trim()}'.")
        }
        "redirect:/teams/$guildId"
    }

    @PostMapping("/{guildId}/presets/{id}/delete")
    @ResponseBody
    fun deletePreset(
        @PathVariable guildId: Long,
        @PathVariable id: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<TeamApiResult> = WebGuildAccess.requireForJson(
        user = user,
        guildId = guildId,
        check = teamWebService::isMember,
        errorBuilder = { status -> ResponseEntity.status(status).body(TeamApiResult(false, "Not authorized.")) },
    ) { _ ->
        val error = teamWebService.deletePreset(id, guildId)
        if (error != null) ResponseEntity.badRequest().body(TeamApiResult(false, error))
        else ResponseEntity.ok(TeamApiResult(true, null))
    }
}

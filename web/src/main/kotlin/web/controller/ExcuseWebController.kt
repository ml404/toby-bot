package web.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.ExcuseWebService
import web.util.WebGuildAccess
import web.util.discordIdOrNull
import web.util.discordIdString
import web.util.displayName

@Controller
@RequestMapping("/excuses")
class ExcuseWebController(
    private val excuseWebService: ExcuseWebService,
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
        val guilds = excuseWebService.getMutualGuilds(client.accessToken.tokenValue)
        val counts = excuseWebService.getApprovedCountsForGuilds(guilds.map { it.id.toLong() })
            .mapKeys { it.key.toString() }

        model.addAttribute("guilds", guilds)
        model.addAttribute("approvedCounts", counts)
        model.addAttribute("username", user.displayName())
        model.addAttribute("discordId", user.discordIdString())
        model.addAttribute("inviteUrl", inviteUrl)
        return "excuse-guilds"
    }

    @GetMapping("/{guildId}")
    fun page(
        @PathVariable guildId: Long,
        @RequestParam(required = false, defaultValue = ExcuseWebService.TAB_APPROVED) tab: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireSignedInForPage(user, "/excuses/guilds") { authDiscordId ->
        val guildName = excuseWebService.getGuildName(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireSignedInForPage "redirect:/excuses/guilds"
        }

        val view = excuseWebService.getPage(guildId, tab, q, page, authDiscordId)
        val members = if (view.isSuperUser) excuseWebService.getGuildMembers(guildId) else emptyList()

        model.addAttribute("guildId", guildId)
        model.addAttribute("guildName", guildName)
        model.addAttribute("rows", view.rows)
        model.addAttribute("page", view.page)
        model.addAttribute("totalPages", view.totalPages)
        model.addAttribute("totalCount", view.totalCount)
        model.addAttribute("hasPrev", view.hasPrev)
        model.addAttribute("hasNext", view.hasNext)
        model.addAttribute("activeTab", view.requestedTab)
        model.addAttribute("query", view.query)
        model.addAttribute("isSuperUser", view.isSuperUser)
        model.addAttribute("members", members)
        model.addAttribute("maxLength", ExcuseWebService.MAX_EXCUSE_LENGTH)
        model.addAttribute("username", user.displayName())
        "excuses"
    }

    @GetMapping("/{guildId}/random")
    @ResponseBody
    fun random(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<RandomResponse> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInRandom,
    ) { _ ->
        val pick = excuseWebService.getRandomApproved(guildId)
        if (pick == null) {
            ResponseEntity.ok(RandomResponse(ok = false, error = "There are no approved excuses yet."))
        } else {
            ResponseEntity.ok(RandomResponse(ok = true, id = pick.id, text = pick.text, author = pick.author))
        }
    }

    @PostMapping("/{guildId}/submit")
    fun submit(
        @PathVariable guildId: Long,
        @RequestParam text: String,
        @RequestParam(required = false) authorDiscordId: Long?,
        @RequestParam(required = false) tab: String?,
        @RequestParam(required = false) q: String?,
        @AuthenticationPrincipal user: OAuth2User,
        ra: RedirectAttributes,
    ): String = WebGuildAccess.requireSignedInForPage(user, "/excuses/guilds") { authDiscordId2 ->
        val result = excuseWebService.submit(guildId, text, authorDiscordId, authDiscordId2)
        if (result.ok) {
            ra.addFlashAttribute("success", "Submitted for approval (id #${result.id}).")
        } else {
            ra.addFlashAttribute("error", result.error)
        }
        redirectToPage(guildId, tab, q)
    }

    @PostMapping("/{guildId}/{id}/approve")
    @ResponseBody
    fun approve(
        @PathVariable guildId: Long,
        @PathVariable id: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<ExcuseApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi,
    ) { authDiscordId ->
        val error = excuseWebService.approve(id, authDiscordId, guildId)
        if (error != null) ResponseEntity.badRequest().body(ExcuseApiResult(false, error))
        else ResponseEntity.ok(ExcuseApiResult(true, null))
    }

    @PostMapping("/{guildId}/{id}/delete")
    @ResponseBody
    fun delete(
        @PathVariable guildId: Long,
        @PathVariable id: Long,
        @AuthenticationPrincipal user: OAuth2User,
    ): ResponseEntity<ExcuseApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi,
    ) { authDiscordId ->
        val error = excuseWebService.delete(id, authDiscordId, guildId)
        if (error != null) ResponseEntity.badRequest().body(ExcuseApiResult(false, error))
        else ResponseEntity.ok(ExcuseApiResult(true, null))
    }

    private val notSignedInApi: () -> ExcuseApiResult = { ExcuseApiResult(false, "Not signed in.") }
    private val notSignedInRandom: () -> RandomResponse = {
        RandomResponse(ok = false, error = "Not signed in.")
    }

    private fun redirectToPage(guildId: Long, tab: String?, q: String?): String {
        val params = mutableListOf<String>()
        if (!tab.isNullOrBlank()) params += "tab=$tab"
        if (!q.isNullOrBlank()) params += "q=${java.net.URLEncoder.encode(q, Charsets.UTF_8)}"
        val suffix = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return "redirect:/excuses/$guildId$suffix"
    }
}

data class ExcuseApiResult(val ok: Boolean, val error: String?)

data class RandomResponse(
    val ok: Boolean,
    val id: Long? = null,
    val text: String? = null,
    val author: String? = null,
    val error: String? = null,
)

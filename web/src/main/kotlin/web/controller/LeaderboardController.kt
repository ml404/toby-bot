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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.LeaderboardSort
import web.service.LeaderboardWebService
import web.util.DefaultGuildCookie
import web.util.DefaultGuildRedirect
import web.util.WebGuildAccess
import web.util.discordIdOrNull
import web.util.displayName
import java.time.LocalDate
import java.time.ZoneOffset

@Controller
class LeaderboardController(
    private val leaderboardWebService: LeaderboardWebService
) {

    @GetMapping("/leaderboards")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        @RequestParam(required = false, defaultValue = "false") pick: Boolean,
        request: HttpServletRequest,
        model: Model
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            leaderboardWebService.getGuildsWhereUserCanView(client.accessToken.tokenValue, discordId)
        } else emptyList()

        val defaultGuildId = DefaultGuildCookie.read(request)
        DefaultGuildRedirect.pick(
            guildIds = guilds.mapNotNull { it.id.toLongOrNull() },
            cookieGuildId = defaultGuildId,
            pick = pick,
        )?.let { return "redirect:/leaderboard/$it" }

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("defaultGuildId", defaultGuildId)
        return "leaderboards"
    }

    @GetMapping("/leaderboard/{guildId}")
    fun leaderboardPage(
        @PathVariable guildId: Long,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) month: String?,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireForPage(
        user, guildId, ra, lobbyPath = "/leaderboards",
        check = leaderboardWebService::isMember,
    ) { _ ->
        val view = leaderboardWebService.getGuildView(
            guildId,
            LeaderboardSort.fromQuery(sort),
            parseMonth(month),
        ) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireForPage "redirect:/leaderboards"
        }

        model.addAttribute("view", view)
        model.addAttribute("guildId", guildId.toString())
        model.addAttribute("username", user.displayName())
        "leaderboard"
    }

    /**
     * Visible for testing. Accepts `YYYY-MM`; returns null for null/blank/invalid
     * or any month outside the last 12 (retention window). The service does the
     * final clamp too — this is a cheap front-line filter.
     */
    internal fun parseMonth(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        val parts = s.split("-")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        if (month !in 1..12) return null
        val parsed = runCatching { LocalDate.of(year, month, 1) }.getOrNull() ?: return null
        val today = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val oldest = today.minusMonths(11)
        if (parsed.isBefore(oldest) || parsed.isAfter(today)) return null
        return parsed
    }
}

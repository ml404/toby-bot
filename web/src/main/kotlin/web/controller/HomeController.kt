package web.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import web.service.HomeStatsService
import web.util.DiscordInvite

@Controller
class HomeController(
    @param:Value($$"${spring.security.oauth2.client.registration.discord.client-id}")
    private val discordClientId: String,
    private val homeStatsService: HomeStatsService,
) {

    @GetMapping("/")
    fun home(
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestParam(name = "frame_id", required = false) frameId: String?,
        request: HttpServletRequest,
        model: Model,
    ): String {
        // Discord Activity launches always load the proxy root "/" — there
        // is no entry-path setting in the Developer Portal. The launch is
        // recognisable by the SDK params Discord appends (frame_id et al).
        // Render the activity shell DIRECTLY rather than redirecting to
        // /activity: the servlet container turns a redirect into an
        // absolute Location on the real host, which the Discord sandbox
        // treats as opening a disallowed web page and kills the activity.
        // An in-place render keeps the iframe URL on *.discordsays.com and
        // leaves the SDK params intact in location.search.
        if (frameId != null) {
            model.addAttribute("clientId", discordClientId.trim())
            return "activity"
        }
        model.addAttribute("inviteUrl", DiscordInvite.urlFor(discordClientId))
        model.addAttribute("username", user?.getAttribute<String>("username"))
        model.addAttribute("homeStats", homeStatsService.get())
        return "home"
    }

    @GetMapping("/terms")
    fun terms(@AuthenticationPrincipal user: OAuth2User?, model: Model): String {
        model.addAttribute("username", user?.getAttribute<String>("username"))
        return "terms"
    }

    @GetMapping("/privacy")
    fun privacy(@AuthenticationPrincipal user: OAuth2User?, model: Model): String {
        model.addAttribute("username", user?.getAttribute<String>("username"))
        return "privacy"
    }
}

package web.controller.admin

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.AdminInstallsService
import web.service.BotOwnerAuthorizer
import web.service.InstallChartsService
import web.util.discordIdOrNull
import web.util.displayName

/**
 * Bot-operator-only surface. Gated by [BotOwnerAuthorizer.isBotOwner]
 * (the `BOT_OWNER_IDS` env var) rather than the per-guild moderation
 * gate — these pages are for whoever runs the bot, not per-server admins.
 *
 * Spring Security already bounces anonymous callers to OAuth login
 * (the `/admin` paths require authentication), so the
 * controller only has to enforce the operator check. Non-operators are
 * redirected home with a flash error, matching the moderation pages'
 * redirect-on-deny house style.
 */
@Controller
@RequestMapping("/admin")
class AdminController(
    private val adminInstallsService: AdminInstallsService,
    private val installChartsService: InstallChartsService,
    private val botOwnerAuthorizer: BotOwnerAuthorizer,
) {

    @GetMapping("/installs")
    fun installs(
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model,
        ra: RedirectAttributes,
    ): String {
        if (!botOwnerAuthorizer.isBotOwner(user?.discordIdOrNull())) {
            ra.addFlashAttribute("error", "Not authorized.")
            return "redirect:/"
        }
        val rows = adminInstallsService.listInstalls()
        model.addAttribute("installs", rows)
        model.addAttribute("stats", adminInstallsService.buildStats(rows))
        model.addAttribute("insights", adminInstallsService.buildInsights(rows))
        model.addAttribute("chart", installChartsService.build(rows))
        model.addAttribute("username", user.displayName())
        return "admin-installs"
    }
}

package web.controller

import common.notification.NotificationChannelKind
import common.notification.Surface
import database.service.UserNotificationPrefService
import jakarta.servlet.http.HttpServletRequest
import net.dv8tion.jda.api.JDA
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.util.DefaultGuildCookie
import web.util.DefaultGuildRedirect
import web.util.GuildMembership
import web.util.discordIdOrNull
import web.util.displayName

/**
 * Guild-scoped notification-preferences page. Renders a kind × surface
 * matrix the user can flip cell-by-cell — each toggle calls the existing
 * JSON REST endpoint at `/api/engagement/{guildId}/notifications/{kindCode}/{surfaceCode}`
 * via the shared `TobyApi.postJson` helper.
 *
 * Auth + membership checks mirror `EngagementApiController` and other
 * guild-scoped pages: 401 redirect to login for unauthenticated;
 * redirect to guild picker for non-members so the user can pick a guild
 * they actually share with the bot.
 */
@Controller
@RequestMapping("/preferences/notifications")
class NotificationPreferencesController(
    private val jda: JDA,
    private val membership: GuildMembership,
    private val prefService: UserNotificationPrefService,
) {

    /**
     * Picker shown when no guildId is supplied — lists the user's
     * mutual guilds with the bot. Mirrors the pattern other guild-scoped
     * controllers use (Profile, Tip, etc.).
     */
    @GetMapping
    fun picker(
        @AuthenticationPrincipal user: OAuth2User?,
        @RequestParam(required = false, defaultValue = "false") pick: Boolean,
        request: HttpServletRequest,
        model: Model,
    ): String {
        if (user == null) return "redirect:/login"
        val discordId = user.discordIdOrNull() ?: return "redirect:/login"
        val mutual = jda.guilds.filter { it.getMemberById(discordId) != null }
        DefaultGuildRedirect.pick(
            guildIds = mutual.mapNotNull { it.id.toLongOrNull() },
            cookieGuildId = DefaultGuildCookie.read(request),
            pick = pick,
        )?.let { return "redirect:/preferences/notifications/$it" }

        val guilds = mutual
            .map { PickerGuild(id = it.id, name = it.name, iconUrl = it.iconUrl) }
            .sortedBy { it.name.lowercase() }
        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        return "preferences-notifications-picker"
    }

    @GetMapping("/{guildId}")
    fun page(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model,
        ra: RedirectAttributes,
    ): String {
        if (user == null) return "redirect:/login"
        val discordId = user.discordIdOrNull() ?: return "redirect:/login"
        if (!membership.isMember(discordId, guildId)) {
            ra.addFlashAttribute("error", "You are not a member of that server.")
            return "redirect:/preferences/notifications"
        }
        val guild = jda.getGuildById(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return "redirect:/preferences/notifications"
        }

        val explicit = prefService.listForUser(discordId, guildId)
            .associateBy { it.channelKind to it.surface }

        // Build the matrix: one row per kind, one cell per Surface.entries.
        // Cells for unsupported (kind, surface) render as placeholders.
        val rows = NotificationChannelKind.entries.map { kind ->
            MatrixRow(
                kind = kind.name,
                displayName = kind.displayName,
                description = kind.description,
                cells = Surface.entries.map { surface ->
                    if (!kind.supports(surface)) {
                        MatrixCell.Placeholder(surface = surface.name)
                    } else {
                        val row = explicit[kind.name to surface.name]
                        MatrixCell.Toggle(
                            surface = surface.name,
                            optIn = row?.optIn ?: kind.defaultOptIn(surface),
                            isDefault = row == null,
                        )
                    }
                }
            )
        }

        model.addAttribute("guildId", guildId)
        model.addAttribute("guildName", guild.name)
        model.addAttribute("username", user.displayName())
        model.addAttribute("surfaces", Surface.entries.map { it.name })
        model.addAttribute("matrix", rows)
        return "preferences-notifications"
    }

    data class PickerGuild(val id: String, val name: String, val iconUrl: String?)

    data class MatrixRow(
        val kind: String,
        val displayName: String,
        val description: String,
        val cells: List<MatrixCell>,
    )

    sealed class MatrixCell {
        abstract val surface: String

        /** User-clickable on/off toggle for a supported (kind, surface). */
        data class Toggle(
            override val surface: String,
            val optIn: Boolean,
            val isDefault: Boolean,
        ) : MatrixCell()

        /** Non-interactive "—" cell for unsupported (kind, surface). */
        data class Placeholder(override val surface: String) : MatrixCell()
    }
}

package web.controller.moderation

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
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
import web.service.ActivityChartsService
import web.service.ModerationWebService
import web.util.DefaultGuildCookie
import web.util.DefaultGuildRedirect
import web.util.DiscordInvite
import web.util.WebGuildAccess
import web.util.discordIdOrNull
import web.util.discordIdString
import web.util.displayName

/**
 * Read-only side of the moderation dashboard — every `@GetMapping`
 * route under `/moderation`. The mutations (POST/DELETE) live in
 * [ModerationMutationsController]; both share the same `@RequestMapping`
 * base so the URL space is one logical surface even though the Kotlin
 * is split for cohesion.
 */
@Controller
@RequestMapping("/moderation")
class ModerationPagesController(
    private val moderationWebService: ModerationWebService,
    private val activityChartsService: ActivityChartsService,
    @param:Value($$"${spring.security.oauth2.client.registration.discord.client-id}")
    private val discordClientId: String,
) {
    private val inviteUrl: String
        get() = DiscordInvite.urlFor(discordClientId)

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
            moderationWebService.getModeratableGuilds(client.accessToken.tokenValue, discordId)
        } else emptyList()

        val defaultGuildId = DefaultGuildCookie.read(request)
        DefaultGuildRedirect.pick(
            guildIds = guilds.mapNotNull { it.id.toLongOrNull() },
            cookieGuildId = defaultGuildId,
            pick = pick,
        )?.let { return "redirect:/moderation/$it" }

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("discordId", user.discordIdString())
        model.addAttribute("inviteUrl", inviteUrl)
        model.addAttribute("defaultGuildId", defaultGuildId)
        return "moderation-guilds"
    }

    /**
     * Bare per-guild URL is a stable redirect into the most-used sub-page
     * (Users). Old bookmarks still land somewhere sensible; the actual
     * tabs each have their own route now so admins can deep-link into a
     * single section.
     */
    @GetMapping("/{guildId}")
    fun moderationLanding(@PathVariable guildId: Long): String =
        "redirect:/moderation/$guildId/users"

    @GetMapping("/{guildId}/users")
    fun usersPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = renderSubPage(guildId, user, model, ra, "moderation/users")

    @GetMapping("/{guildId}/actions")
    fun actionsPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = renderSubPage(guildId, user, model, ra, "moderation/actions")

    @GetMapping("/{guildId}/settings")
    fun settingsPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = renderSubPage(guildId, user, model, ra, "moderation/settings")

    @GetMapping("/{guildId}/voice")
    fun voicePage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = renderSubPage(guildId, user, model, ra, "moderation/voice")

    @GetMapping("/{guildId}/poll")
    fun pollPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = renderSubPage(guildId, user, model, ra, "moderation/poll")

    @GetMapping("/{guildId}/casino")
    fun casinoPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = renderSubPage(guildId, user, model, ra, "moderation/casino")

    @GetMapping("/{guildId}/lottery")
    fun lotteryPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = renderSubPage(guildId, user, model, ra, "moderation/lottery")

    @GetMapping("/{guildId}/welcome")
    fun welcomePage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireForPage(
        user, guildId, ra, lobbyPath = "/moderation/guilds",
        check = moderationWebService::canModerate,
        deniedMessage = "You are not allowed to moderate that server.",
    ) { discordId ->
        val overview = moderationWebService.getGuildOverview(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireForPage "redirect:/moderation/guilds"
        }
        val welcome = moderationWebService.getWelcomeOverview(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireForPage "redirect:/moderation/guilds"
        }
        model.addAttribute("overview", overview)
        model.addAttribute("welcome", welcome)
        model.addAttribute("isOwner", moderationWebService.isOwner(discordId, guildId))
        model.addAttribute("username", user.displayName())
        model.addAttribute("actorDiscordId", discordId.toString())
        "moderation/welcome"
    }

    @GetMapping("/{guildId}/leveling")
    fun levelingPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireForPage(
        user, guildId, ra, lobbyPath = "/moderation/guilds",
        check = moderationWebService::canModerate,
        deniedMessage = "You are not allowed to moderate that server.",
    ) { discordId ->
        val overview = moderationWebService.getGuildOverview(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireForPage "redirect:/moderation/guilds"
        }
        val leveling = moderationWebService.getLevelingOverview(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireForPage "redirect:/moderation/guilds"
        }
        model.addAttribute("overview", overview)
        model.addAttribute("leveling", leveling)
        model.addAttribute("isOwner", moderationWebService.isOwner(discordId, guildId))
        model.addAttribute("username", user.displayName())
        model.addAttribute("actorDiscordId", discordId.toString())
        "moderation/leveling"
    }

    @GetMapping("/{guildId}/activity")
    fun activityPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String = WebGuildAccess.requireForPage(
        user, guildId, ra, lobbyPath = "/moderation/guilds",
        check = moderationWebService::canModerate,
        deniedMessage = "You are not allowed to moderate that server.",
    ) { discordId ->
        val overview = moderationWebService.getGuildOverview(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireForPage "redirect:/moderation/guilds"
        }
        val messagesChart = activityChartsService.buildMessagesChart(guildId)
        val voiceHoursChart = activityChartsService.buildVoiceHoursChart(guildId)
        model.addAttribute("overview", overview)
        model.addAttribute("messagesChart", messagesChart)
        model.addAttribute("voiceHoursChart", voiceHoursChart)
        model.addAttribute("isOwner", moderationWebService.isOwner(discordId, guildId))
        model.addAttribute("username", user.displayName())
        model.addAttribute("actorDiscordId", discordId.toString())
        "moderation/activity"
    }

    /**
     * Shared resolver for every per-tab sub-page: runs the same access
     * check + overview fetch + model-population the old single-page
     * handler did, then returns the supplied [viewName]. The full
     * `GuildOverview` is added on every sub-page even when only some
     * fields are read by the template — it's one JDA call already, and
     * sharing the model keeps the sub-templates plug-and-play with the
     * `moderationHeader` fragment.
     */
    private fun renderSubPage(
        guildId: Long,
        user: OAuth2User,
        model: Model,
        ra: RedirectAttributes,
        viewName: String,
    ): String = WebGuildAccess.requireForPage(
        user, guildId, ra, lobbyPath = "/moderation/guilds",
        check = moderationWebService::canModerate,
        deniedMessage = "You are not allowed to moderate that server.",
    ) { discordId ->
        val overview = moderationWebService.getGuildOverview(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return@requireForPage "redirect:/moderation/guilds"
        }

        model.addAttribute("overview", overview)
        model.addAttribute("isOwner", moderationWebService.isOwner(discordId, guildId))
        model.addAttribute("username", user.displayName())
        model.addAttribute("actorDiscordId", discordId.toString())
        model.addAttribute("jackpotPool", moderationWebService.getJackpotPool(guildId))
        viewName
    }
}

package web.controller

import database.dto.ConfigDto
import database.dto.UserDto
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.ModerationWebService
import web.util.discordIdOrNull
import web.util.discordIdString
import web.util.displayName

@Controller
@RequestMapping("/moderation")
class ModerationController(
    private val moderationWebService: ModerationWebService,
    @param:Value($$"${spring.security.oauth2.client.registration.discord.client-id}")
    private val discordClientId: String
) {
    private val inviteUrl: String
        get() = "https://discord.com/api/oauth2/authorize?client_id=$discordClientId&permissions=8&scope=bot%20applications.commands"

    @GetMapping("/guilds")
    fun guildList(
        @RegisteredOAuth2AuthorizedClient("discord") client: OAuth2AuthorizedClient,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model
    ): String {
        val discordId = user.discordIdOrNull()
        val guilds = if (discordId != null) {
            moderationWebService.getModeratableGuilds(client.accessToken.tokenValue, discordId)
        } else emptyList()

        model.addAttribute("guilds", guilds)
        model.addAttribute("username", user.displayName())
        model.addAttribute("discordId", user.discordIdString())
        model.addAttribute("inviteUrl", inviteUrl)
        return "moderation-guilds"
    }

    @GetMapping("/{guildId}")
    fun moderationPage(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User,
        model: Model,
        ra: RedirectAttributes
    ): String {
        val discordId = user.discordIdOrNull()
            ?: return "redirect:/moderation/guilds"

        if (!moderationWebService.canModerate(discordId, guildId)) {
            ra.addFlashAttribute("error", "You are not allowed to moderate that server.")
            return "redirect:/moderation/guilds"
        }

        val overview = moderationWebService.getGuildOverview(guildId) ?: run {
            ra.addFlashAttribute("error", "Bot is not in that server.")
            return "redirect:/moderation/guilds"
        }
        val leaderboard = moderationWebService.getLeaderboard(guildId)

        model.addAttribute("overview", overview)
        model.addAttribute("leaderboard", leaderboard)
        model.addAttribute("isOwner", moderationWebService.isOwner(discordId, guildId))
        model.addAttribute("username", user.displayName())
        model.addAttribute("actorDiscordId", discordId.toString())
        return "moderation"
    }

    @PostMapping("/{guildId}/user/{targetDiscordId}/permission", consumes = ["application/json"])
    @ResponseBody
    fun togglePermission(
        @PathVariable guildId: Long,
        @PathVariable targetDiscordId: Long,
        @RequestBody body: PermissionRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        val perm = runCatching { UserDto.Permissions.valueOf(body.permission.uppercase()) }.getOrNull()
            ?: return ResponseEntity.badRequest().body(ApiResult(false, "Unknown permission '${body.permission}'."))
        val error = moderationWebService.togglePermission(actor, guildId, targetDiscordId, perm)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/user/{targetDiscordId}/initiative", consumes = ["application/json"])
    @ResponseBody
    fun setInitiative(
        @PathVariable guildId: Long,
        @PathVariable targetDiscordId: Long,
        @RequestBody body: InitiativeRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        val error = moderationWebService.setInitiativeModifier(actor, guildId, targetDiscordId, body.modifier)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/user/{targetDiscordId}/social-credit", consumes = ["application/json"])
    @ResponseBody
    fun adjustSocialCredit(
        @PathVariable guildId: Long,
        @PathVariable targetDiscordId: Long,
        @RequestBody body: SocialCreditRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        val error = moderationWebService.adjustSocialCredit(actor, guildId, targetDiscordId, body.delta)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/config", consumes = ["application/json"])
    @ResponseBody
    fun updateConfig(
        @PathVariable guildId: Long,
        @RequestBody body: ConfigRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        val key = runCatching { ConfigDto.Configurations.valueOf(body.key.uppercase()) }.getOrNull()
            ?: return ResponseEntity.badRequest().body(ApiResult(false, "Unknown config key '${body.key}'."))
        val error = moderationWebService.updateConfig(actor, guildId, key, body.value)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/kick", consumes = ["application/json"])
    @ResponseBody
    fun kick(
        @PathVariable guildId: Long,
        @RequestBody body: KickRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        val error = moderationWebService.kickMember(actor, guildId, body.targetDiscordId, body.reason)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/voice/move", consumes = ["application/json"])
    @ResponseBody
    fun move(
        @PathVariable guildId: Long,
        @RequestBody body: MoveRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<MoveResponse> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(MoveResponse(false, "Not signed in."))
        val result = moderationWebService.moveMembers(actor, guildId, body.targetChannelId, body.memberIds)
        if (result.error != null) {
            return ResponseEntity.badRequest().body(MoveResponse(false, result.error, result.moved, result.skipped))
        }
        return ResponseEntity.ok(MoveResponse(true, null, result.moved, result.skipped))
    }

    @PostMapping("/{guildId}/voice/{channelId}/mute", consumes = ["application/json"])
    @ResponseBody
    fun muteVoiceChannel(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody body: MuteRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<MuteResponse> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(MuteResponse(false, "Not signed in."))
        val result = moderationWebService.muteVoiceChannel(actor, guildId, channelId, body.mute)
        if (result.error != null) {
            return ResponseEntity.badRequest().body(MuteResponse(false, result.error, result.changed, result.skipped))
        }
        return ResponseEntity.ok(MuteResponse(true, null, result.changed, result.skipped))
    }

    @PostMapping("/{guildId}/poll", consumes = ["application/json"])
    @ResponseBody
    fun createPoll(
        @PathVariable guildId: Long,
        @RequestBody body: PollRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(ApiResult(false, "Not signed in."))
        val error = moderationWebService.createPoll(actor, guildId, body.channelId, body.question, body.options)
        return if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }
}

data class PermissionRequest(val permission: String = "")
data class InitiativeRequest(val modifier: Int = 0)
data class SocialCreditRequest(val delta: Long = 0)
data class ConfigRequest(val key: String = "", val value: String = "")
data class KickRequest(val targetDiscordId: Long = 0, val reason: String? = null)
data class MoveRequest(val targetChannelId: Long = 0, val memberIds: List<Long> = emptyList())
data class MuteRequest(val mute: Boolean = true)
data class PollRequest(val channelId: Long = 0, val question: String = "", val options: List<String> = emptyList())

data class MoveResponse(
    val ok: Boolean,
    val error: String?,
    val moved: List<String> = emptyList(),
    val skipped: List<String> = emptyList()
)

data class MuteResponse(
    val ok: Boolean,
    val error: String?,
    val changed: List<String> = emptyList(),
    val skipped: List<String> = emptyList()
)

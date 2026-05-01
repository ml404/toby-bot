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
import web.util.WebGuildAccess
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
        "moderation"
    }

    @PostMapping("/{guildId}/user/{targetDiscordId}/permission", consumes = ["application/json"])
    @ResponseBody
    fun togglePermission(
        @PathVariable guildId: Long,
        @PathVariable targetDiscordId: Long,
        @RequestBody body: PermissionRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = withSignedInActor(user, ::ApiResult) { actor ->
        val perm = runCatching { UserDto.Permissions.valueOf(body.permission.uppercase()) }.getOrNull()
            ?: return@withSignedInActor ResponseEntity.badRequest().body(ApiResult(false, "Unknown permission."))
        val error = moderationWebService.togglePermission(actor, guildId, targetDiscordId, perm)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/user/{targetDiscordId}/social-credit", consumes = ["application/json"])
    @ResponseBody
    fun adjustSocialCredit(
        @PathVariable guildId: Long,
        @PathVariable targetDiscordId: Long,
        @RequestBody body: SocialCreditRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = withSignedInActor(user, ::ApiResult) { actor ->
        val error = moderationWebService.adjustSocialCredit(actor, guildId, targetDiscordId, body.delta)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/config", consumes = ["application/json"])
    @ResponseBody
    fun updateConfig(
        @PathVariable guildId: Long,
        @RequestBody body: ConfigRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = withSignedInActor(user, ::ApiResult) { actor ->
        val key = runCatching { ConfigDto.Configurations.valueOf(body.key.uppercase()) }.getOrNull()
            ?: return@withSignedInActor ResponseEntity.badRequest().body(ApiResult(false, "Unknown config key."))
        val error = moderationWebService.updateConfig(actor, guildId, key, body.value)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/kick", consumes = ["application/json"])
    @ResponseBody
    fun kick(
        @PathVariable guildId: Long,
        @RequestBody body: KickRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = withSignedInActor(user, ::ApiResult) { actor ->
        val error = moderationWebService.kickMember(actor, guildId, body.targetDiscordId, body.reason)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/voice/move", consumes = ["application/json"])
    @ResponseBody
    fun move(
        @PathVariable guildId: Long,
        @RequestBody body: MoveRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<MoveResponse> = withSignedInActor(
        user, { ok, err -> MoveResponse(ok, err) }
    ) { actor ->
        val result = moderationWebService.moveMembers(actor, guildId, body.targetChannelId, body.memberIds)
        if (result.error != null) {
            ResponseEntity.badRequest().body(MoveResponse(false, result.error, result.moved, result.skipped))
        } else {
            ResponseEntity.ok(MoveResponse(true, null, result.moved, result.skipped))
        }
    }

    @PostMapping("/{guildId}/voice/{channelId}/mute", consumes = ["application/json"])
    @ResponseBody
    fun muteVoiceChannel(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody body: MuteRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<MuteResponse> = withSignedInActor(
        user, { ok, err -> MuteResponse(ok, err) }
    ) { actor ->
        val result = moderationWebService.muteVoiceChannel(actor, guildId, channelId, body.mute)
        if (result.error != null) {
            ResponseEntity.badRequest().body(MuteResponse(false, result.error, result.changed, result.skipped))
        } else {
            ResponseEntity.ok(MuteResponse(true, null, result.changed, result.skipped))
        }
    }

    @PostMapping("/{guildId}/poll", consumes = ["application/json"])
    @ResponseBody
    fun createPoll(
        @PathVariable guildId: Long,
        @RequestBody body: PollRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = withSignedInActor(user, ::ApiResult) { actor ->
        val error = moderationWebService.createPoll(actor, guildId, body.channelId, body.question, body.options)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    /**
     * Per-action JSON endpoints don't re-check membership — the service
     * does its own permission audit on the actor — so all the controller
     * needs is "401 if not signed in." This collapses 6 copies of the
     * same `discordIdOrNull() ?: return ResponseEntity.status(401)…`
     * into one place.
     */
    private inline fun <T : Any> withSignedInActor(
        user: OAuth2User,
        noinline notSignedInBuilder: (ok: Boolean, error: String) -> T,
        block: (actor: Long) -> ResponseEntity<T>,
    ): ResponseEntity<T> {
        val actor = user.discordIdOrNull()
            ?: return ResponseEntity.status(401).body(notSignedInBuilder(false, "Not signed in."))
        return block(actor)
    }
}

data class PermissionRequest(val permission: String = "")
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

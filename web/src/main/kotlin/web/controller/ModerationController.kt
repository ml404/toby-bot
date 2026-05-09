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
        model.addAttribute("jackpotPool", moderationWebService.getJackpotPool(guildId))
        "moderation"
    }

    @PostMapping("/{guildId}/user/{targetDiscordId}/permission", consumes = ["application/json"])
    @ResponseBody
    fun togglePermission(
        @PathVariable guildId: Long,
        @PathVariable targetDiscordId: Long,
        @RequestBody body: PermissionRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val perm = runCatching { UserDto.Permissions.valueOf(body.permission.uppercase()) }.getOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(ApiResult(false, "Unknown permission."))
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
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
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
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val key = runCatching { ConfigDto.Configurations.valueOf(body.key.uppercase()) }.getOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(ApiResult(false, "Unknown config key."))
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
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val targetId = body.targetDiscordId.toLongOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(ApiResult(false, "Invalid user id."))
        val error = moderationWebService.kickMember(actor, guildId, targetId, body.reason)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/voice/move", consumes = ["application/json"])
    @ResponseBody
    fun move(
        @PathVariable guildId: Long,
        @RequestBody body: MoveRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<MoveResponse> = WebGuildAccess.requireSignedInForJson(
        user, { MoveResponse(false, "Not signed in.") }
    ) { actor ->
        val channelId = body.targetChannelId.toLongOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(MoveResponse(false, "Invalid channel id."))
        val memberIds = body.memberIds.mapNotNull { it.toLongOrNull() }
        val result = moderationWebService.moveMembers(actor, guildId, channelId, memberIds)
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
    ): ResponseEntity<MuteResponse> = WebGuildAccess.requireSignedInForJson(
        user, { MuteResponse(false, "Not signed in.") }
    ) { actor ->
        val result = moderationWebService.muteVoiceChannel(actor, guildId, channelId, body.mute)
        if (result.error != null) {
            ResponseEntity.badRequest().body(MuteResponse(false, result.error, result.changed, result.skipped))
        } else {
            ResponseEntity.ok(MuteResponse(true, null, result.changed, result.skipped))
        }
    }

    @PostMapping("/{guildId}/jackpot/reset", consumes = ["application/json"])
    @ResponseBody
    fun resetJackpot(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<JackpotAdminResponse> = WebGuildAccess.requireSignedInForJson(
        user, { JackpotAdminResponse(false, "Not signed in.") }
    ) { actor ->
        val result = moderationWebService.resetJackpotPool(actor, guildId)
        if (result.error != null) {
            ResponseEntity.badRequest().body(JackpotAdminResponse(false, result.error, drained = result.drained, newPool = result.newPool))
        } else {
            ResponseEntity.ok(JackpotAdminResponse(true, null, drained = result.drained, newPool = result.newPool))
        }
    }

    @PostMapping("/{guildId}/jackpot/refund", consumes = ["application/json"])
    @ResponseBody
    fun refundJackpot(
        @PathVariable guildId: Long,
        @RequestBody body: JackpotRefundRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<JackpotAdminResponse> = WebGuildAccess.requireSignedInForJson(
        user, { JackpotAdminResponse(false, "Not signed in.") }
    ) { actor ->
        val sourceId = body.sourceDiscordId.toLongOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(
                JackpotAdminResponse(false, "Invalid user id.")
            )
        val result = moderationWebService.refundJackpotFromUser(actor, guildId, sourceId, body.amount)
        if (result.error != null) {
            ResponseEntity.badRequest().body(
                JackpotAdminResponse(false, result.error, drained = result.drained, newPool = result.newPool, newSourceBalance = result.newSourceBalance)
            )
        } else {
            ResponseEntity.ok(
                JackpotAdminResponse(true, null, drained = result.drained, newPool = result.newPool, newSourceBalance = result.newSourceBalance)
            )
        }
    }

    /**
     * Force the daily match-numbers lottery cycle to run *now* for
     * [guildId]. Closes the current open draw (paying tier-based
     * prizes), opens a fresh one seeded from the jackpot. Equivalent
     * to what `LotteryDailyJob` does at 00:00 UTC, exposed manually so
     * admins can fast-forward (testing, missed crons, demo).
     */
    @PostMapping("/{guildId}/lottery/draw", consumes = ["application/json"])
    @ResponseBody
    fun forceDailyLotteryDraw(
        @PathVariable guildId: Long,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ForceDrawLotteryResponse> = WebGuildAccess.requireSignedInForJson(
        user, { ForceDrawLotteryResponse(false, "Not signed in.") }
    ) { actor ->
        val result = moderationWebService.forceDailyDraw(actor, guildId)
        if (result.error != null) {
            ResponseEntity.badRequest().body(
                ForceDrawLotteryResponse(
                    ok = false, error = result.error,
                    drewPrior = result.drewPrior, priorTotalPaid = result.priorTotalPaid,
                    priorRolledBack = result.priorRolledBack, priorDrawn = result.priorDrawn,
                    priorBelowMinBuyers = result.priorBelowMinBuyers,
                    priorBuyersHave = result.priorBuyersHave,
                    priorBuyersNeed = result.priorBuyersNeed,
                    openedNew = result.openedNew, newSeeded = result.newSeeded,
                )
            )
        } else {
            ResponseEntity.ok(
                ForceDrawLotteryResponse(
                    ok = true, error = null,
                    drewPrior = result.drewPrior, priorTotalPaid = result.priorTotalPaid,
                    priorRolledBack = result.priorRolledBack, priorDrawn = result.priorDrawn,
                    priorBelowMinBuyers = result.priorBelowMinBuyers,
                    priorBuyersHave = result.priorBuyersHave,
                    priorBuyersNeed = result.priorBuyersNeed,
                    openedNew = result.openedNew, newSeeded = result.newSeeded,
                )
            )
        }
    }

    /**
     * Create a brand-new read-only text channel and auto-set the
     * supplied channel-config to it. Powers the "Create" buttons next
     * to channel-id dropdowns (LOTTERY_CHANNEL, LEADERBOARD_CHANNEL).
     */
    @PostMapping("/{guildId}/channel/create-read-only", consumes = ["application/json"])
    @ResponseBody
    fun createReadOnlyChannel(
        @PathVariable guildId: Long,
        @RequestBody body: CreateReadOnlyChannelRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<CreateReadOnlyChannelResponse> = WebGuildAccess.requireSignedInForJson(
        user, { CreateReadOnlyChannelResponse(false, "Not signed in.") }
    ) { actor ->
        when (val result = moderationWebService.createReadOnlyChannel(
            actorDiscordId = actor,
            guildId = guildId,
            rawName = body.name,
            targetConfigName = body.targetConfig,
            parentCategoryId = body.parentCategoryId,
            newCategoryName = body.newCategoryName,
        )) {
            is ModerationWebService.CreateChannelOutcome.Ok -> ResponseEntity.ok(
                CreateReadOnlyChannelResponse(
                    ok = true,
                    error = null,
                    channelId = result.channelId,
                    channelName = result.channelName,
                    targetConfig = result.targetConfig,
                )
            )
            is ModerationWebService.CreateChannelOutcome.Error ->
                ResponseEntity.badRequest().body(
                    CreateReadOnlyChannelResponse(ok = false, error = result.message)
                )
        }
    }

    @PostMapping("/{guildId}/poll", consumes = ["application/json"])
    @ResponseBody
    fun createPoll(
        @PathVariable guildId: Long,
        @RequestBody body: PollRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val channelId = body.channelId.toLongOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(ApiResult(false, "Invalid channel id."))
        val error = moderationWebService.createPoll(actor, guildId, channelId, body.question, body.options)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    private val notSignedInApi: () -> ApiResult = { ApiResult(false, "Not signed in.") }
}

data class PermissionRequest(val permission: String = "")
data class SocialCreditRequest(val delta: Long = 0)
data class ConfigRequest(val key: String = "", val value: String = "")
data class KickRequest(val targetDiscordId: String = "", val reason: String? = null)
data class MoveRequest(val targetChannelId: String = "", val memberIds: List<String> = emptyList())
data class MuteRequest(val mute: Boolean = true)
data class PollRequest(val channelId: String = "", val question: String = "", val options: List<String> = emptyList())

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

data class JackpotRefundRequest(
    val sourceDiscordId: String = "",
    val amount: Long = 0L,
)

data class JackpotAdminResponse(
    val ok: Boolean,
    val error: String?,
    val drained: Long = 0L,
    val newPool: Long = 0L,
    val newSourceBalance: Long? = null,
)

data class ForceDrawLotteryResponse(
    val ok: Boolean,
    val error: String?,
    val drewPrior: Boolean = false,
    val priorTotalPaid: Long = 0L,
    val priorRolledBack: Long = 0L,
    val priorDrawn: List<Int> = emptyList(),
    val priorBelowMinBuyers: Boolean = false,
    val priorBuyersHave: Int = 0,
    val priorBuyersNeed: Int = 0,
    val openedNew: Boolean = false,
    val newSeeded: Long = 0L,
)

/**
 * `targetConfig` is the canonical [database.dto.ConfigDto.Configurations]
 * enum name (e.g. "LOTTERY_CHANNEL"); the service validates it against
 * the allow-list. `name` is the human-typed channel name; the service
 * normalises it to Discord's lowercase-dashed convention.
 *
 * Category placement is optional and resolved in the service:
 *   - `newCategoryName` non-blank → create a new category with that
 *     name and put the channel inside (takes precedence).
 *   - `parentCategoryId` non-blank → put the channel under the existing
 *     category with that id.
 *   - both blank → channel is top-level (no parent).
 */
data class CreateReadOnlyChannelRequest(
    val name: String = "",
    val targetConfig: String = "",
    val parentCategoryId: String? = null,
    val newCategoryName: String? = null,
)

data class CreateReadOnlyChannelResponse(
    val ok: Boolean,
    val error: String?,
    val channelId: String? = null,
    val channelName: String? = null,
    val targetConfig: String? = null,
)

package web.controller.moderation

import database.dto.ConfigDto
import database.dto.UserDto
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import web.controller.ApiResult
import web.service.ModerationWebService
import web.util.WebGuildAccess

/**
 * Write side of the moderation dashboard — every `@PostMapping` and
 * `@DeleteMapping` route under `/moderation`. The read-only pages
 * (`@GetMapping`) live in [ModerationPagesController]; both share the
 * same `@RequestMapping` base so the URL space is one logical surface
 * even though the Kotlin is split for cohesion.
 */
@Controller
@RequestMapping("/moderation")
class ModerationMutationsController(
    private val moderationWebService: ModerationWebService,
) {

    private val notSignedInApi: () -> ApiResult = { ApiResult(false, "Not signed in.") }

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

    @PostMapping("/{guildId}/ban", consumes = ["application/json"])
    @ResponseBody
    fun ban(
        @PathVariable guildId: Long,
        @RequestBody body: BanRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val targetId = body.targetDiscordId.toLongOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(ApiResult(false, "Invalid user id."))
        val error = moderationWebService.banMember(actor, guildId, targetId, body.reason, body.deleteDays)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/unban", consumes = ["application/json"])
    @ResponseBody
    fun unban(
        @PathVariable guildId: Long,
        @RequestBody body: UnbanRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val targetId = body.targetDiscordId.toLongOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(ApiResult(false, "Invalid user id."))
        val error = moderationWebService.unbanUser(actor, guildId, targetId)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/timeout", consumes = ["application/json"])
    @ResponseBody
    fun timeout(
        @PathVariable guildId: Long,
        @RequestBody body: TimeoutRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val targetId = body.targetDiscordId.toLongOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(ApiResult(false, "Invalid user id."))
        val error = moderationWebService.timeoutMember(actor, guildId, targetId, body.minutes, body.reason)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/untimeout", consumes = ["application/json"])
    @ResponseBody
    fun untimeout(
        @PathVariable guildId: Long,
        @RequestBody body: UntimeoutRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val targetId = body.targetDiscordId.toLongOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(ApiResult(false, "Invalid user id."))
        val error = moderationWebService.untimeoutMember(actor, guildId, targetId)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/purge", consumes = ["application/json"])
    @ResponseBody
    fun purge(
        @PathVariable guildId: Long,
        @RequestBody body: PurgeRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<PurgeResponse> = WebGuildAccess.requireSignedInForJson(
        user, { PurgeResponse(false, "Not signed in.") }
    ) { actor ->
        val channelId = body.channelId.toLongOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(PurgeResponse(false, "Invalid channel id."))
        val filterUserId = body.filterUserId?.takeIf { it.isNotBlank() }?.toLongOrNull()
        val result = moderationWebService.purgeMessages(actor, guildId, channelId, body.count, filterUserId)
        if (result.error != null) {
            ResponseEntity.badRequest().body(PurgeResponse(false, result.error, result.deleted, result.skipped))
        } else {
            ResponseEntity.ok(PurgeResponse(true, null, result.deleted, result.skipped))
        }
    }

    @PostMapping("/{guildId}/channel/{channelId}/lock", consumes = ["application/json"])
    @ResponseBody
    fun lockChannel(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody body: LockRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val error = moderationWebService.lockChannel(actor, guildId, channelId, body.lock)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    @PostMapping("/{guildId}/channel/{channelId}/slowmode", consumes = ["application/json"])
    @ResponseBody
    fun slowmode(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestBody body: SlowmodeRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val error = moderationWebService.setSlowmode(actor, guildId, channelId, body.seconds)
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

    /**
     * Create a *private* text channel where only TobyBot and server
     * admins can read. Used for the casino mod-log so anti-autoclicker
     * session embeds aren't visible to regular members. Mirrors the
     * read-only flow's request/response shape; only the @everyone
     * permission overrides differ on the server side.
     *
     * `targetConfig` must be allow-listed in
     * [ModerationWebService.ADMIN_CHANNEL_CONFIG_ALLOWLIST] (currently
     * just `CASINO_MODLOG_CHANNEL_ID`) so a public-facing config can't
     * be routed through this endpoint by accident.
     */
    @PostMapping("/{guildId}/channel/create-admin-only", consumes = ["application/json"])
    @ResponseBody
    fun createAdminOnlyChannel(
        @PathVariable guildId: Long,
        @RequestBody body: CreateReadOnlyChannelRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<CreateReadOnlyChannelResponse> = WebGuildAccess.requireSignedInForJson(
        user, { CreateReadOnlyChannelResponse(false, "Not signed in.") }
    ) { actor ->
        when (val result = moderationWebService.createAdminOnlyChannel(
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

    /**
     * Add a role to the per-guild auto-assign list. Owner-only.
     * Triggered by the "Add auto-role" form on the welcome moderation tab.
     */
    @PostMapping("/{guildId}/auto-role", consumes = ["application/json"])
    @ResponseBody
    fun addAutoRole(
        @PathVariable guildId: Long,
        @RequestBody body: AutoRoleRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val roleId = body.roleId.toLongOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(ApiResult(false, "Invalid role id."))
        val error = moderationWebService.addAutoRole(actor, guildId, roleId)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    /**
     * Drop a `(guildId, roleId)` auto-role binding. Owner-only.
     * Triggered by the per-row Delete button on the welcome moderation tab.
     */
    @DeleteMapping("/{guildId}/auto-role/{roleId}")
    @ResponseBody
    fun removeAutoRole(
        @PathVariable guildId: Long,
        @PathVariable roleId: Long,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val error = moderationWebService.removeAutoRole(actor, guildId, roleId)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    /**
     * Upsert a level → role-reward binding. Owner-only. Triggered by
     * the "Add reward" form on the leveling moderation tab.
     */
    @PostMapping("/{guildId}/level-reward", consumes = ["application/json"])
    @ResponseBody
    fun upsertLevelReward(
        @PathVariable guildId: Long,
        @RequestBody body: LevelRewardRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val roleId = body.roleId.toLongOrNull()
            ?: return@requireSignedInForJson ResponseEntity.badRequest().body(ApiResult(false, "Invalid role id."))
        val error = moderationWebService.upsertLevelReward(actor, guildId, body.level, roleId)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    /**
     * Drop a level → role-reward binding. Owner-only. Triggered by the
     * per-row Delete button on the leveling moderation tab.
     */
    @DeleteMapping("/{guildId}/level-reward/{level}")
    @ResponseBody
    fun deleteLevelReward(
        @PathVariable guildId: Long,
        @PathVariable level: Int,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val error = moderationWebService.deleteLevelReward(actor, guildId, level)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
    }

    /**
     * Set required_level on a title. Owner-only. Triggered by the
     * per-title Save button on the leveling moderation tab.
     */
    @PostMapping("/{guildId}/title/{titleId}/required-level", consumes = ["application/json"])
    @ResponseBody
    fun setTitleRequiredLevel(
        @PathVariable guildId: Long,
        @PathVariable titleId: Long,
        @RequestBody body: RequiredLevelRequest,
        @AuthenticationPrincipal user: OAuth2User
    ): ResponseEntity<ApiResult> = WebGuildAccess.requireSignedInForJson(
        user, notSignedInApi
    ) { actor ->
        val error = moderationWebService.setTitleRequiredLevel(actor, guildId, titleId, body.requiredLevel)
        if (error != null) ResponseEntity.badRequest().body(ApiResult(false, error))
        else ResponseEntity.ok(ApiResult(true, null))
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
}

data class PermissionRequest(val permission: String = "")
data class SocialCreditRequest(val delta: Long = 0)
data class ConfigRequest(val key: String = "", val value: String = "")
data class KickRequest(val targetDiscordId: String = "", val reason: String? = null)
data class BanRequest(
    val targetDiscordId: String = "",
    val reason: String? = null,
    val deleteDays: Int = 0,
)
data class UnbanRequest(val targetDiscordId: String = "")
data class TimeoutRequest(
    val targetDiscordId: String = "",
    val minutes: Long = 10,
    val reason: String? = null,
)
data class UntimeoutRequest(val targetDiscordId: String = "")
data class PurgeRequest(
    val channelId: String = "",
    val count: Int = 10,
    val filterUserId: String? = null,
)
data class LockRequest(val lock: Boolean = true)
data class SlowmodeRequest(val seconds: Int = 0)
data class MoveRequest(val targetChannelId: String = "", val memberIds: List<String> = emptyList())
data class PollRequest(val channelId: String = "", val question: String = "", val options: List<String> = emptyList())
data class LevelRewardRequest(val level: Int = 0, val roleId: String = "")
data class RequiredLevelRequest(val requiredLevel: Int = 0)
data class AutoRoleRequest(val roleId: String = "")

data class PurgeResponse(
    val ok: Boolean,
    val error: String?,
    val deleted: Int = 0,
    val skipped: Int = 0,
)

data class MoveResponse(
    val ok: Boolean,
    val error: String?,
    val moved: List<String> = emptyList(),
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

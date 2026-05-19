package web.service

import common.events.ActivityTrackingEnabled
import common.logging.DiscordLogger
import database.dto.ConfigDto
import database.dto.UserDto
import database.dto.LevelRoleRewardDto
import database.service.ConfigService
import database.service.LevelRoleRewardService
import database.service.LotteryHelper
import database.service.MonthlyCreditSnapshotService
import database.service.TitleService
import database.service.UbiDailyService
import database.service.UserService
import database.service.VoiceSessionService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.entities.emoji.Emoji
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import web.view.BulkBonusTierView
import web.view.LotteryIncentivesView
import web.view.MultiplierTierView
import web.view.PoolMilestoneView
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.EnumSet
import java.util.concurrent.TimeUnit

@Service
class ModerationWebService(
    private val jda: JDA,
    private val userService: UserService,
    private val configService: ConfigService,
    private val introWebService: IntroWebService,
    private val voiceSessionService: VoiceSessionService,
    private val titleService: TitleService,
    private val snapshotService: MonthlyCreditSnapshotService,
    private val ubiDailyService: UbiDailyService,
    private val levelRoleRewardService: LevelRoleRewardService,
    private val casinoAuditService: CasinoAuditService,
    private val eventPublisher: ApplicationEventPublisher
) {
    companion object {
        const val MAX_POLL_OPTIONS = 4
        private val POLL_EMOJI = listOf("1️⃣", "2️⃣", "3️⃣", "4️⃣")
        private val logger = DiscordLogger(ModerationWebService::class.java)

        /**
         * Channel-config keys eligible for the
         * [createReadOnlyChannel] flow. Hard-coded allow-list so a
         * malformed (or maliciously crafted) request can't point the
         * `targetConfig` at, say, `JACKPOT_WHEEL_SEGMENTS` and clobber a
         * non-channel-id value with a channel id.
         */
        private val CHANNEL_CONFIG_ALLOWLIST: Set<ConfigDto.Configurations> = setOf(
            ConfigDto.Configurations.LOTTERY_CHANNEL,
            ConfigDto.Configurations.LEADERBOARD_CHANNEL,
            ConfigDto.Configurations.LEVEL_UP_CHANNEL,
        )

        /** Allow-list for the admin-only `create-admin-only-channel`
         *  flow — channels meant for moderator eyes only (anti-
         *  autoclicker session embeds). Separate from the public
         *  read-only allow-list so a future contributor can't
         *  accidentally route a public-facing config (e.g.
         *  LOTTERY_CHANNEL) through the admin-only endpoint. */
        private val ADMIN_CHANNEL_CONFIG_ALLOWLIST: Set<ConfigDto.Configurations> = setOf(
            ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID,
        )
    }

    /**
     * Visibility shape for a newly-created admin channel. Only differs
     * from the public read-only flow on the @everyone permission set;
     * everything else (sanitisation, category placement, bot perms,
     * config upsert) is shared via [createTargetedChannel].
     */
    private enum class ChannelVisibility {
        /** Public — @everyone allowed VIEW_CHANNEL but denied
         *  MESSAGE_SEND + MESSAGE_ADD_REACTION. Bot can post. */
        PUBLIC_READONLY,

        /** Private — @everyone explicitly denied VIEW_CHANNEL. Server
         *  admins (Administrator-permission roles) still see it via
         *  Discord's built-in rule that Administrator overrides every
         *  channel-level deny. Bot can post. */
        ADMIN_ONLY,
    }

    private fun <T> safely(label: String, default: T, block: () -> T): T =
        runCatching(block)
            .onFailure { logger.warn("Leaderboard enrichment failed ($label): ${it::class.simpleName}: ${it.message}") }
            .getOrDefault(default)

    fun getModeratableGuilds(accessToken: String, discordId: Long): List<GuildInfo> {
        return introWebService.getMutualGuilds(accessToken).filter { info ->
            val guildId = info.id.toLongOrNull() ?: return@filter false
            canModerate(discordId, guildId)
        }
    }

    fun canModerate(discordId: Long, guildId: Long): Boolean {
        val guild = jda.getGuildById(guildId) ?: return false
        val member = guild.getMemberById(discordId)
        if (member?.isOwner == true) return true
        return introWebService.isSuperUser(discordId, guildId)
    }

    fun isOwner(discordId: Long, guildId: Long): Boolean {
        val guild = jda.getGuildById(guildId) ?: return false
        return guild.getMemberById(discordId)?.isOwner == true
    }

    fun getGuildOverview(guildId: Long): GuildOverview? {
        val guild = jda.getGuildById(guildId) ?: return null
        val members = guild.members.filter { !it.user.isBot }
        val dtoByDiscordId = userService.listGuildUsers(guildId).filterNotNull().associateBy { it.discordId }
        val ownerId = guild.ownerIdLong

        val rows = members.map { member ->
            val dto = dtoByDiscordId[member.idLong]
            ModeratedMember(
                id = member.id,
                name = member.effectiveName,
                avatarUrl = member.effectiveAvatarUrl,
                isOwner = member.idLong == ownerId,
                isBot = member.user.isBot,
                musicPermission = dto?.musicPermission ?: true,
                memePermission = dto?.memePermission ?: true,
                digPermission = dto?.digPermission ?: true,
                superUser = dto?.superUser ?: false,
                socialCredit = dto?.socialCredit ?: 0L
            )
        }.sortedBy { it.name.lowercase() }

        val voiceChannels = guild.voiceChannels.map {
            VoiceChannelInfo(
                id = it.id,
                name = it.name,
                memberIds = it.members.mapNotNull { m -> if (m.user.isBot) null else m.id }
            )
        }
        val textChannels = guild.textChannels
            .filter { guild.selfMember.hasPermission(it, Permission.MESSAGE_SEND) }
            .map { TextChannelInfo(id = it.id, name = it.name) }
        // Categories are only used by the create-read-only-channel form
        // for grouping. Order matches Discord's left-rail order so
        // admins can scan top-down.
        val categories = guild.categories
            .sortedBy { it.position }
            .map { CategoryInfo(id = it.id, name = it.name) }

        val configByKey = ConfigDto.Configurations.entries.associate { cfg ->
            cfg.name to configService.getConfigByName(cfg.configValue, guild.id)?.value
        }

        // Resolve the incentive tier triples through LotteryHelper so the
        // template, the buy-time service, and the announcer all share one
        // definition of "active" — no risk of the web summary saying
        // "Tier 2 is on" while the embed silently ignores it.
        val guildIdLong = guild.id.toLongOrNull()
        val incentives = if (guildIdLong == null) LotteryIncentivesView.empty() else {
            LotteryIncentivesView(
                bulkTiers = LotteryHelper.bulkBonusTiers(configService, guildIdLong)
                    .map { (buy, bonus) -> BulkBonusTierView(buy = buy, bonus = bonus) },
                multiplierTiers = LotteryHelper.volumeMultiplierTiers(configService, guildIdLong)
                    .map { (total, bp) -> MultiplierTierView(total = total, bp = bp) },
                poolMilestones = LotteryHelper.poolMilestones(configService, guildIdLong)
                    .map { (tickets, pct) -> PoolMilestoneView(tickets = tickets, pct = pct) },
            )
        }

        return GuildOverview(
            guildId = guild.id,
            guildName = guild.name,
            members = rows,
            voiceChannels = voiceChannels,
            textChannels = textChannels,
            categories = categories,
            config = configByKey,
            lotteryIncentives = incentives,
        )
    }

    fun getLeaderboard(guildId: Long): List<LeaderboardRow> {
        val guild = jda.getGuildById(guildId) ?: return emptyList()
        val users = userService.listGuildUsers(guildId).filterNotNull()

        val thisMonthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val nextMonthStart = thisMonthStart.plusMonths(1)

        val lifetimeVoice = safely("lifetime voice", emptyMap()) {
            voiceSessionService.sumCountedSecondsLifetimeByUser(guildId)
        }
        // Range is [thisMonthStart, nextMonthStart) — sessions whose joinedAt
        // falls inside the current calendar month. The MonthlyLeaderboardJob
        // uses [prevMonthStart, thisMonthStart) because it runs on the 1st
        // and reports the JUST-FINISHED month; that range is wrong here.
        val thisMonthVoice = safely("this-month voice", emptyMap()) {
            voiceSessionService.sumCountedSecondsInRangeByUser(
                guildId,
                thisMonthStart.atStartOfDay().toInstant(ZoneOffset.UTC),
                nextMonthStart.atStartOfDay().toInstant(ZoneOffset.UTC)
            )
        }
        val existingBaselines = safely("prior snapshots", emptyList()) {
            snapshotService.listForGuildDate(guildId, thisMonthStart)
        }.associateBy { it.discordId }.toMutableMap()

        // UBI grants land in socialCredit but represent a fixed handout, not
        // earned activity. Subtract them so the leaderboard reflects voice +
        // command + intro + trade earnings only.
        val ubiByUser = safely("this-month UBI", emptyMap()) {
            ubiDailyService.sumGrantedInRangeByUser(guildId, thisMonthStart, nextMonthStart)
        }

        // Lazy-baseline: if a user has no snapshot row for this month's 1st
        // (fresh deploy, new guild, bot was down on the 1st), the scheduled
        // job hasn't run for them and "this month" delta would report 0
        // forever. Snapshot their current balance now so the NEXT earn
        // produces a correct delta. One-time cost: earnings between the 1st
        // and this first visit are baked into the baseline.
        users.forEach { dto ->
            if (!existingBaselines.containsKey(dto.discordId)) {
                safely("lazy baseline for ${dto.discordId}", null as database.dto.MonthlyCreditSnapshotDto?) {
                    snapshotService.upsertIfMissing(
                        database.dto.MonthlyCreditSnapshotDto(
                            discordId = dto.discordId,
                            guildId = guildId,
                            snapshotDate = thisMonthStart,
                            socialCredit = dto.socialCredit ?: 0L,
                            tobyCoins = dto.tobyCoins
                        )
                    )
                }?.let { existingBaselines[dto.discordId] = it }
            }
        }

        return users
            .sortedByDescending { it.socialCredit ?: 0L }
            .mapIndexed { index, dto ->
                val member = guild.getMemberById(dto.discordId)
                val title = safely("title for ${dto.discordId}", null as String?) {
                    dto.activeTitleId?.let { titleService.getById(it) }?.label
                }
                val current = dto.socialCredit ?: 0L
                val baseline = existingBaselines[dto.discordId]?.socialCredit
                val rawDelta = if (baseline == null) 0L else current - baseline
                val ubiThisMonth = ubiByUser[dto.discordId] ?: 0L
                val creditsDelta = (rawDelta - ubiThisMonth).coerceAtLeast(0L)
                LeaderboardRow(
                    rank = index + 1,
                    discordId = dto.discordId.toString(),
                    name = member?.effectiveName ?: "Unknown",
                    avatarUrl = member?.effectiveAvatarUrl,
                    socialCredit = current,
                    title = title,
                    voiceSecondsLifetime = lifetimeVoice[dto.discordId] ?: 0L,
                    voiceSecondsThisMonth = thisMonthVoice[dto.discordId] ?: 0L,
                    creditsEarnedThisMonth = creditsDelta
                )
            }
    }

    fun togglePermission(
        actorDiscordId: Long,
        guildId: Long,
        targetDiscordId: Long,
        permission: UserDto.Permissions
    ): String? {
        if (!canModerate(actorDiscordId, guildId)) return "You are not allowed to moderate this server."
        if (actorDiscordId == targetDiscordId) return "You cannot change your own permissions."
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        if (guild.getMemberById(targetDiscordId) == null) return "Target member is not in that server."
        val actorIsOwner = isOwner(actorDiscordId, guildId)

        if (permission == UserDto.Permissions.SUPERUSER && !actorIsOwner) {
            return "Only the server owner can toggle SUPERUSER."
        }

        val target = userService.getUserById(targetDiscordId, guildId)
            ?: UserDto(discordId = targetDiscordId, guildId = guildId).also { userService.createNewUser(it) }

        // Mirror AdjustUserCommand: requester must be owner OR (superuser AND target is not superuser).
        val actorDto = userService.getUserById(actorDiscordId, guildId)
        val canAdjust = actorIsOwner || (actorDto?.superUser == true && !target.superUser)
        if (!canAdjust) return "You cannot adjust a user with equal or greater privileges."

        when (permission) {
            UserDto.Permissions.MUSIC -> target.musicPermission = !target.musicPermission
            UserDto.Permissions.MEME -> target.memePermission = !target.memePermission
            UserDto.Permissions.DIG -> target.digPermission = !target.digPermission
            UserDto.Permissions.SUPERUSER -> target.superUser = !target.superUser
        }
        userService.updateUser(target)
        return null
    }

    fun adjustSocialCredit(
        actorDiscordId: Long,
        guildId: Long,
        targetDiscordId: Long,
        delta: Long
    ): String? {
        if (!isOwner(actorDiscordId, guildId)) return "Only the server owner can adjust social credit."
        if (delta == Long.MIN_VALUE) return "Invalid delta."
        val target = userService.getUserById(targetDiscordId, guildId)
            ?: UserDto(discordId = targetDiscordId, guildId = guildId).also { userService.createNewUser(it) }
        target.socialCredit = (target.socialCredit ?: 0L) + delta
        userService.updateUser(target)
        return null
    }

    // Casino/jackpot admin operations were extracted to [CasinoAuditService]
    // (see also [ModerationAuthorizer] for the shared canModerate gate).
    // These shims are kept so existing controller wiring doesn't change
    // in this PR; new callers should depend on CasinoAuditService directly.

    /** Delegates to [CasinoAuditService.getJackpotPool]. */
    fun getJackpotPool(guildId: Long): Long =
        casinoAuditService.getJackpotPool(guildId)

    /** Delegates to [CasinoAuditService.resetJackpotPool]. */
    fun resetJackpotPool(actorDiscordId: Long, guildId: Long): CasinoAuditService.ResetJackpotResult =
        casinoAuditService.resetJackpotPool(actorDiscordId, guildId)

    /** Delegates to [CasinoAuditService.refundJackpotFromUser]. */
    fun refundJackpotFromUser(
        actorDiscordId: Long,
        guildId: Long,
        sourceDiscordId: Long,
        amount: Long,
    ): CasinoAuditService.RefundJackpotResult =
        casinoAuditService.refundJackpotFromUser(actorDiscordId, guildId, sourceDiscordId, amount)

    /** Delegates to [CasinoAuditService.forceDailyDraw]. */
    fun forceDailyDraw(actorDiscordId: Long, guildId: Long): CasinoAuditService.ForceDrawLotteryResult =
        casinoAuditService.forceDailyDraw(actorDiscordId, guildId)

    // ---------------- Leveling moderation ----------------

    /**
     * Build the data backing the per-guild leveling moderation page: the
     * current `(level, role)` reward bindings joined to live JDA role
     * info (so missing/renamed roles surface clearly), and every title
     * with its current `requiredLevel` so admins can gate purchases.
     * Returns null if JDA can't see the guild.
     */
    fun getLevelingOverview(guildId: Long): LevelingOverview? {
        val guild = jda.getGuildById(guildId) ?: return null
        val rewards = levelRoleRewardService.listForGuild(guildId).map { dto ->
            val role = guild.getRoleById(dto.roleId)
            LevelRoleRewardView(
                level = dto.level,
                roleId = dto.roleId.toString(),
                roleName = role?.name ?: "(deleted role)",
                roleColorHex = role?.colorRaw?.takeIf { it != 0 }?.let { String.format("#%06x", it and 0xFFFFFF) },
                roleMissing = role == null,
            )
        }
        val roles = guild.roles
            .filter { !it.isManaged && it.idLong != guild.idLong } // hide @everyone and integration roles
            .sortedByDescending { it.position }
            .map { RoleInfo(id = it.id, name = it.name) }
        val titles = titleService.listAll().map { t ->
            TitleGateView(
                id = t.id ?: 0L,
                label = t.label,
                colorHex = t.colorHex,
                cost = t.cost,
                requiredLevel = t.requiredLevel,
            )
        }
        return LevelingOverview(
            guildId = guild.id,
            guildName = guild.name,
            levelRewards = rewards,
            roles = roles,
            titles = titles,
        )
    }

    /**
     * Upsert a `(level, roleId)` binding. Owner-only. The level must be
     * a positive integer (level 0 is the starting state — rewards at 0
     * would fire on every new user). The role must exist in the guild
     * and must not be managed (integration-owned roles can't be assigned
     * by the bot). Returns null on success, otherwise a user-facing
     * error message.
     */
    fun upsertLevelReward(
        actorDiscordId: Long,
        guildId: Long,
        level: Int,
        roleId: Long,
    ): String? {
        if (!isOwner(actorDiscordId, guildId)) return "Only the server owner can change guild config."
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        if (level <= 0) return "Level must be 1 or higher."
        val role = guild.getRoleById(roleId) ?: return "Role not found in this server."
        if (role.isManaged) return "That role is managed by an integration and can't be assigned by the bot."
        if (!guild.selfMember.canInteract(role)) {
            return "The bot's highest role is below ${role.name} — move TobyBot's role above it to allow assignment."
        }
        levelRoleRewardService.upsert(
            LevelRoleRewardDto(guildId = guildId, level = level, roleId = roleId)
        )
        return null
    }

    /**
     * Drop the `(guildId, level)` binding. Owner-only. No-op if no row
     * exists at that level — surfaces success either way so the UI's
     * delete button is idempotent.
     */
    fun deleteLevelReward(actorDiscordId: Long, guildId: Long, level: Int): String? {
        if (!isOwner(actorDiscordId, guildId)) return "Only the server owner can change guild config."
        levelRoleRewardService.delete(guildId, level)
        return null
    }

    /**
     * Set `required_level` on a title. Owner-only. A value of 0 means
     * "no gate" (the default). Returns null on success or a user-facing
     * error message.
     */
    fun setTitleRequiredLevel(
        actorDiscordId: Long,
        guildId: Long,
        titleId: Long,
        requiredLevel: Int,
    ): String? {
        if (!isOwner(actorDiscordId, guildId)) return "Only the server owner can change guild config."
        if (requiredLevel < 0) return "Required level must be zero or higher."
        if (requiredLevel > 1000) return "Required level can't exceed 1000."
        titleService.updateRequiredLevel(titleId, requiredLevel)
            ?: return "Title not found."
        return null
    }

    /**
     * Create a brand-new text channel that's read-only for `@everyone`
     * and post-able by the bot, then auto-set the supplied
     * channel-config to point at it.
     *
     * Generic over the target config key — admin-fired from beside
     * either `LOTTERY_CHANNEL` or `LEADERBOARD_CHANNEL` rows on the
     * moderation tab. New channel-config keys can be allow-listed in
     * [CHANNEL_CONFIG_ALLOWLIST] to gain the same affordance.
     *
     * Permission overrides on the new channel:
     *   - `@everyone`: VIEW_CHANNEL allowed; MESSAGE_SEND +
     *     MESSAGE_ADD_REACTION denied → readers see results, can't
     *     reply or react-spam.
     *   - Bot member: VIEW_CHANNEL + MESSAGE_SEND + MESSAGE_EMBED_LINKS
     *     allowed → can post embeds even if the bot's role doesn't
     *     grant SEND server-wide.
     */
    sealed interface CreateChannelOutcome {
        data class Ok(
            val channelId: String,
            val channelName: String,
            val targetConfig: String,
        ) : CreateChannelOutcome
        data class Error(val message: String) : CreateChannelOutcome
    }

    fun createReadOnlyChannel(
        actorDiscordId: Long,
        guildId: Long,
        rawName: String,
        targetConfigName: String,
        parentCategoryId: String? = null,
        newCategoryName: String? = null,
    ): CreateChannelOutcome = createTargetedChannel(
        actorDiscordId = actorDiscordId,
        guildId = guildId,
        rawName = rawName,
        targetConfigName = targetConfigName,
        allowList = CHANNEL_CONFIG_ALLOWLIST,
        visibility = ChannelVisibility.PUBLIC_READONLY,
        parentCategoryId = parentCategoryId,
        newCategoryName = newCategoryName,
    )

    /**
     * Create a private text channel where only TobyBot and server admins
     * can read or post — used for the casino mod-log so anti-autoclicker
     * session embeds aren't visible to regular members. Mirrors the
     * read-only flow's API exactly; only the @everyone permission set
     * differs (denied VIEW_CHANNEL instead of allowed-with-write-deny).
     *
     * Server admins keep visibility for free via Discord's built-in
     * rule that the Administrator permission overrides any channel-
     * level deny — no explicit role grant required, no per-server
     * mod-role configuration. The bot is granted VIEW_CHANNEL +
     * MESSAGE_SEND + MESSAGE_EMBED_LINKS so it can post even if its
     * server-wide role doesn't have those.
     */
    fun createAdminOnlyChannel(
        actorDiscordId: Long,
        guildId: Long,
        rawName: String,
        targetConfigName: String,
        parentCategoryId: String? = null,
        newCategoryName: String? = null,
    ): CreateChannelOutcome = createTargetedChannel(
        actorDiscordId = actorDiscordId,
        guildId = guildId,
        rawName = rawName,
        targetConfigName = targetConfigName,
        allowList = ADMIN_CHANNEL_CONFIG_ALLOWLIST,
        visibility = ChannelVisibility.ADMIN_ONLY,
        parentCategoryId = parentCategoryId,
        newCategoryName = newCategoryName,
    )

    /**
     * Shared backbone for the read-only and admin-only channel-create
     * flows. The two callers differ only on:
     *   1. The allow-list of configs they're permitted to set (so
     *      `LOTTERY_CHANNEL` can't be routed through the admin-only
     *      endpoint and vice-versa).
     *   2. The @everyone permission overrides on the new channel.
     * Everything else — moderation auth, JDA Manage Channels check,
     * name sanitisation, parent-category resolution, JDA failure
     * handling, config upsert — is identical across both flows.
     */
    private fun createTargetedChannel(
        actorDiscordId: Long,
        guildId: Long,
        rawName: String,
        targetConfigName: String,
        allowList: Set<ConfigDto.Configurations>,
        visibility: ChannelVisibility,
        /**
         * Existing category id to drop the new channel under, or null
         * for top-level. Ignored when [newCategoryName] is non-blank
         * (new-category path takes precedence so a stale dropdown
         * value can't override an explicit "create new" intent).
         */
        parentCategoryId: String? = null,
        /**
         * If non-blank, create a new category with this (sanitised)
         * name and drop the channel inside. Validated + sanitised
         * the same way as the channel name.
         */
        newCategoryName: String? = null,
    ): CreateChannelOutcome {
        if (!canModerate(actorDiscordId, guildId)) {
            return CreateChannelOutcome.Error("Not allowed.")
        }
        val targetConfig = runCatching {
            ConfigDto.Configurations.valueOf(targetConfigName.trim().uppercase())
        }.getOrNull()
        if (targetConfig == null || targetConfig !in allowList) {
            return CreateChannelOutcome.Error("Unknown channel config: $targetConfigName")
        }
        val guild = jda.getGuildById(guildId)
            ?: return CreateChannelOutcome.Error("Bot is not in that server.")
        val bot = guild.selfMember
        if (!bot.hasPermission(Permission.MANAGE_CHANNEL)) {
            return CreateChannelOutcome.Error(
                "Bot needs the Manage Channels permission. Grant it in Server Settings → Roles."
            )
        }
        // Setting permission overrides at channel-create time is gated
        // by MANAGE_ROLES (a.k.a. Manage Permissions in the Discord UI)
        // even when the bot already has MANAGE_CHANNEL — Discord
        // returns 50013 Missing Permissions on the create call
        // otherwise. Both flows here always apply overrides (read-only
        // denies @everyone MESSAGE_SEND; admin-only denies @everyone
        // VIEW_CHANNEL), so the check applies universally.
        if (!bot.hasPermission(Permission.MANAGE_ROLES)) {
            return CreateChannelOutcome.Error(
                "Bot needs the Manage Roles permission to apply channel-level " +
                    "permission overrides. Grant it in Server Settings → Roles, or " +
                    "give the bot the Administrator permission as a shortcut."
            )
        }
        val name = sanitizeChannelName(rawName)
            ?: return CreateChannelOutcome.Error(
                "Channel name must be 1-90 chars (a-z, 0-9, dashes)."
            )

        // Resolve parent category. Three paths:
        //   1. newCategoryName non-blank → create a category, use that.
        //   2. parentCategoryId non-blank → look up existing category.
        //   3. neither → channel is top-level (no parent).
        val parentCategory: net.dv8tion.jda.api.entities.channel.concrete.Category? = when {
            !newCategoryName.isNullOrBlank() -> {
                val rawCatName = newCategoryName.trim()
                if (rawCatName.length > 100) {
                    return CreateChannelOutcome.Error(
                        "Category name must be 1-100 chars."
                    )
                }
                try {
                    guild.createCategory(rawCatName).complete()
                } catch (e: Exception) {
                    logger.error(
                        "Failed to create category '$rawCatName' for guild=$guildId: ${e.message}"
                    )
                    return CreateChannelOutcome.Error(
                        "Failed to create category: ${e.message ?: "unknown error"}"
                    )
                }
            }
            !parentCategoryId.isNullOrBlank() -> {
                val parsedId = parentCategoryId.toLongOrNull()
                    ?: return CreateChannelOutcome.Error("Invalid category id.")
                guild.getCategoryById(parsedId)
                    ?: return CreateChannelOutcome.Error("No category with that id exists in this server.")
            }
            else -> null
        }

        val everyone = guild.publicRole
        val (everyoneAllow, everyoneDeny) = when (visibility) {
            ChannelVisibility.PUBLIC_READONLY -> listOf(Permission.VIEW_CHANNEL) to
                listOf(Permission.MESSAGE_SEND, Permission.MESSAGE_ADD_REACTION)
            ChannelVisibility.ADMIN_ONLY -> emptyList<Permission>() to
                listOf(Permission.VIEW_CHANNEL)
        }
        val newChannel = try {
            val action = guild.createTextChannel(name)
                .addRolePermissionOverride(everyone.idLong, everyoneAllow, everyoneDeny)
                .addMemberPermissionOverride(
                    bot.idLong,
                    listOf(
                        Permission.VIEW_CHANNEL,
                        Permission.MESSAGE_SEND,
                        Permission.MESSAGE_EMBED_LINKS,
                    ),
                    emptyList(),
                )
            if (parentCategory != null) action.setParent(parentCategory)
            action.complete()
        } catch (e: net.dv8tion.jda.api.exceptions.ErrorResponseException) {
            // JDA wraps Discord's `errors` JSON map. The generic toast
            // "50013 Missing Permissions" buries which permission
            // bounced — surface JDA's `meaning` so the toast at least
            // hints at the cause. The preflight checks at the top of
            // this method catch the common cases before we get here.
            logger.error(
                "Discord rejected channel-create for guild=$guildId target=$targetConfig " +
                    "code=${e.errorCode} response=${e.errorResponse} meaning=${e.meaning}: ${e.message}"
            )
            return CreateChannelOutcome.Error(
                "Discord rejected the create: ${e.meaning} (code ${e.errorCode}). " +
                    "Most often the bot's role is missing Manage Channels or " +
                    "Manage Roles — check Server Settings → Roles → @TobyBot, " +
                    "or re-invite via the homepage to re-grant the install defaults."
            )
        } catch (e: Exception) {
            logger.error(
                "Failed to create channel for guild=$guildId target=$targetConfig: ${e.message}"
            )
            return CreateChannelOutcome.Error(
                "Failed to create channel: ${e.message ?: "unknown error"}"
            )
        }

        configService.upsertConfig(
            targetConfig.configValue,
            newChannel.id,
            guildId.toString(),
        )
        logger.info(
            "Created ${visibility.name.lowercase()} channel guild=$guildId actor=$actorDiscordId " +
                "channel=${newChannel.id} name=${newChannel.name} target=$targetConfig"
        )
        return CreateChannelOutcome.Ok(
            channelId = newChannel.id,
            channelName = newChannel.name,
            targetConfig = targetConfig.name,
        )
    }

    /**
     * Normalise a user-supplied channel name to Discord's lowercase /
     * dashed convention. Returns null for input that ends up empty
     * (all-special-chars, blank, etc) so the caller can surface a
     * validation error rather than create a `""`-named channel.
     */
    internal fun sanitizeChannelName(raw: String): String? {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            // Collapse consecutive dashes (covers both runs of original
            // dashes and runs introduced by special-char replacement)
            // so "--lottery--results--" doesn't end up "lottery--results".
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(90)
        return cleaned.takeIf { it.isNotEmpty() }
    }

    fun updateConfig(
        actorDiscordId: Long,
        guildId: Long,
        key: ConfigDto.Configurations,
        rawValue: String
    ): String? {
        if (!isOwner(actorDiscordId, guildId)) return "Only the server owner can change guild config."
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."

        val value: String = when (key) {
            ConfigDto.Configurations.VOLUME,
            ConfigDto.Configurations.INTRO_VOLUME,
            ConfigDto.Configurations.DELETE_DELAY -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number."
                if (n < 0) return "Value must be zero or positive."
                n.toString()
            }
            ConfigDto.Configurations.MOVE -> {
                val name = rawValue.trim()
                if (name.isEmpty()) return "Channel name is required."
                if (guild.getVoiceChannelsByName(name, true).isEmpty()) {
                    return "No voice channel with that name exists in this server."
                }
                name
            }
            ConfigDto.Configurations.LEADERBOARD_CHANNEL -> {
                val id = rawValue.trim()
                if (id.isEmpty()) return "Channel id is required."
                val idLong = id.toLongOrNull()
                    ?: return "Channel id must be numeric."
                if (guild.getTextChannelById(idLong) == null) {
                    return "No text channel with that id exists in this server."
                }
                id
            }
            ConfigDto.Configurations.ACTIVITY_TRACKING -> {
                val v = rawValue.trim().lowercase()
                if (v != "true" && v != "false") return "Value must be true or false."
                v
            }
            ConfigDto.Configurations.ACTIVITY_TRACKING_NOTIFIED ->
                return "This flag is managed automatically and cannot be edited."
            ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number percentage (0-50)."
                if (n !in 0..50) return "Value must be between 0 and 50 (capped server-side)."
                n.toString()
            }
            ConfigDto.Configurations.JACKPOT_WIN_PCT -> {
                val n = rawValue.trim().toDoubleOrNull()
                    ?: return "Value must be a number between 0 and 50 (decimals allowed; default 1)."
                if (n.isNaN() || n.isInfinite() || n < 0.0 || n > 50.0) {
                    return "Value must be a number between 0 and 50 (decimals allowed; default 1)."
                }
                n.toString()
            }
            ConfigDto.Configurations.TRADE_BUY_FEE_PCT,
            ConfigDto.Configurations.TRADE_SELL_FEE_PCT -> {
                val n = rawValue.trim().toDoubleOrNull()
                    ?: return "Value must be a number between 0 and 25 (decimals allowed; default 1)."
                if (n.isNaN() || n.isInfinite() || n < 0.0 || n > 25.0) {
                    return "Value must be a number between 0 and 25 (decimals allowed; default 1)."
                }
                n.toString()
            }
            ConfigDto.Configurations.POKER_RAKE_PCT -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number percentage (0-20)."
                if (n !in 0..20) return "Value must be between 0 and 20 (capped server-side)."
                n.toString()
            }
            ConfigDto.Configurations.POKER_SMALL_BLIND,
            ConfigDto.Configurations.POKER_BIG_BLIND,
            ConfigDto.Configurations.POKER_SMALL_BET,
            ConfigDto.Configurations.POKER_BIG_BET,
            ConfigDto.Configurations.POKER_MIN_BUY_IN -> {
                val n = rawValue.trim().toLongOrNull()
                    ?: return "Value must be a whole number of chips."
                if (n < 1L) return "Value must be at least 1 chip."
                n.toString()
            }
            ConfigDto.Configurations.POKER_MAX_BUY_IN -> {
                val n = rawValue.trim().toLongOrNull()
                    ?: return "Value must be a whole number of chips."
                if (n < 0L) return "Value must be 0 (unlimited) or a positive number of chips."
                n.toString()
            }
            ConfigDto.Configurations.POKER_MAX_SEATS -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number."
                if (n !in 2..9) return "Value must be between 2 and 9 seats."
                n.toString()
            }
            ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number of seconds."
                if (n !in 0..600) return "Value must be between 0 and 600 seconds."
                n.toString()
            }
            ConfigDto.Configurations.BLACKJACK_RAKE_PCT -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number percentage (0-20)."
                if (n !in 0..20) return "Value must be between 0 and 20 (capped server-side)."
                n.toString()
            }
            // Min-cap stake keys + the jackpot anchor — must be >= 1.
            // The anchor is a divisor in JackpotHelper.rollOnWin so 0 is
            // pathological, not "unlimited"; mins below 1 have no meaning.
            ConfigDto.Configurations.BLACKJACK_MIN_ANTE,
            ConfigDto.Configurations.DICE_MIN_STAKE,
            ConfigDto.Configurations.COINFLIP_MIN_STAKE,
            ConfigDto.Configurations.SLOTS_MIN_STAKE,
            ConfigDto.Configurations.HIGHLOW_MIN_STAKE,
            ConfigDto.Configurations.BACCARAT_MIN_STAKE,
            ConfigDto.Configurations.KENO_MIN_STAKE,
            ConfigDto.Configurations.SCRATCH_MIN_STAKE,
            ConfigDto.Configurations.ROULETTE_MIN_STAKE,
            ConfigDto.Configurations.HOLDEM_MIN_STAKE,
            ConfigDto.Configurations.DUEL_MIN_STAKE,
            ConfigDto.Configurations.PLINKO_MIN_STAKE,
            ConfigDto.Configurations.HORSE_RACING_MIN_STAKE,
            ConfigDto.Configurations.WHEEL_OF_FORTUNE_MIN_STAKE,
            ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR -> {
                val n = rawValue.trim().toLongOrNull()
                    ?: return "Value must be a whole number of credits."
                if (n < 1L) return "Value must be at least 1 credit."
                n.toString()
            }
            // Max-cap stake keys — accept 0 as "no upper cap"
            // (cfgLongMax expands stored 0 to Long.MAX_VALUE at read time).
            // Otherwise must be a positive whole number.
            ConfigDto.Configurations.BLACKJACK_MAX_ANTE,
            ConfigDto.Configurations.DICE_MAX_STAKE,
            ConfigDto.Configurations.COINFLIP_MAX_STAKE,
            ConfigDto.Configurations.SLOTS_MAX_STAKE,
            ConfigDto.Configurations.HIGHLOW_MAX_STAKE,
            ConfigDto.Configurations.BACCARAT_MAX_STAKE,
            ConfigDto.Configurations.KENO_MAX_STAKE,
            ConfigDto.Configurations.SCRATCH_MAX_STAKE,
            ConfigDto.Configurations.ROULETTE_MAX_STAKE,
            ConfigDto.Configurations.HOLDEM_MAX_STAKE,
            ConfigDto.Configurations.DUEL_MAX_STAKE,
            ConfigDto.Configurations.PLINKO_MAX_STAKE,
            ConfigDto.Configurations.HORSE_RACING_MAX_STAKE,
            ConfigDto.Configurations.WHEEL_OF_FORTUNE_MAX_STAKE -> {
                val n = rawValue.trim().toLongOrNull()
                    ?: return "Value must be a whole number of credits."
                if (n < 0L) return "Value must be 0 (unlimited) or a positive number of credits."
                n.toString()
            }
            ConfigDto.Configurations.BLACKJACK_MAX_SEATS -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number."
                if (n !in 2..7) return "Value must be between 2 and 7 seats."
                n.toString()
            }
            ConfigDto.Configurations.BLACKJACK_SHOT_CLOCK_SECONDS -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number of seconds."
                if (n !in 0..600) return "Value must be between 0 and 600 seconds."
                n.toString()
            }
            ConfigDto.Configurations.BLACKJACK_DEALER_HITS_SOFT_17 -> {
                val v = rawValue.trim().lowercase()
                if (v !in setOf("true", "false")) return "Value must be true or false."
                v
            }
            ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_NUM,
            ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_DEN -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number (1-10)."
                if (n !in 1..10) return "Value must be between 1 and 10."
                n.toString()
            }
            ConfigDto.Configurations.UBI_DAILY_AMOUNT -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number (0-1000)."
                if (n !in 0..1000) return "Value must be between 0 and 1000 (0 disables UBI)."
                n.toString()
            }
            ConfigDto.Configurations.DAILY_CREDIT_CAP -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number (0-10000)."
                if (n !in 0..10000) return "Value must be between 0 and 10000 (default 90)."
                n.toString()
            }
            ConfigDto.Configurations.DAILY_XP_CAP -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number (0-100000)."
                if (n !in 0..100000) return "Value must be between 0 and 100000 (default 1000)."
                n.toString()
            }
            ConfigDto.Configurations.DAILY_CAP_PER_LEVEL_BONUS -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number (0-1000)."
                if (n !in 0..1000) return "Value must be between 0 and 1000 (default 10; 0 disables the perk)."
                n.toString()
            }
            ConfigDto.Configurations.UBI_PER_LEVEL_BONUS -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number (0-1000)."
                if (n !in 0..1000) return "Value must be between 0 and 1000 (default 5; 0 disables the perk)."
                n.toString()
            }
            ConfigDto.Configurations.LEVEL_UP_CHANNEL -> {
                val v = rawValue.trim()
                if (v.isEmpty()) {
                    // Empty value clears the override. LevelUpListener falls
                    // back to the originating channel (where the XP was
                    // earned) and finally to the guild's system channel.
                    ""
                } else {
                    val id = v.toLongOrNull()
                        ?: return "Channel id must be numeric."
                    val channel = guild.getTextChannelById(id)
                        ?: return "No text channel with that id exists in this server."
                    channel.id
                }
            }
            ConfigDto.Configurations.JACKPOT_WHEEL_SEGMENTS -> {
                // Empty resets to default. Otherwise must parse cleanly via
                // [JackpotWheel.validateConfigString] — the live reader uses
                // the same parser so "saved OK" implies "read OK".
                val trimmed = rawValue.trim()
                if (trimmed.isEmpty()) {
                    ""
                } else {
                    database.economy.JackpotWheel.validateConfigString(trimmed)?.let { return it }
                    trimmed
                }
            }
            ConfigDto.Configurations.JACKPOT_WINNER_COOLDOWN_DAYS -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number of days (0-365; 0 disables)."
                if (n !in 0..365) return "Value must be between 0 and 365 days."
                n.toString()
            }
            ConfigDto.Configurations.JACKPOT_ACTIVITY_WINDOW_DAYS -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number of days (0-365; 0 disables)."
                if (n !in 0..365) return "Value must be between 0 and 365 days."
                n.toString()
            }
            ConfigDto.Configurations.JACKPOT_ACTIVITY_MIN_DAYS -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number (1-365; default 1)."
                if (n !in 1..365) return "Value must be between 1 and 365."
                n.toString()
            }
            ConfigDto.Configurations.JACKPOT_RTP_MAX_PCT -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number percentage (0-100; 0 disables; recommended 95)."
                if (n !in 0..100) return "Value must be between 0 and 100 (0 disables; recommended 95)."
                n.toString()
            }
            ConfigDto.Configurations.COINFLIP_BOT_EDGE_MAX_PCT,
            ConfigDto.Configurations.DICE_BOT_EDGE_MAX_PCT,
            ConfigDto.Configurations.SLOTS_BOT_EDGE_MAX_PCT,
            ConfigDto.Configurations.PLINKO_BOT_EDGE_MAX_PCT,
            ConfigDto.Configurations.WHEEL_OF_FORTUNE_BOT_EDGE_MAX_PCT -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number percentage (0-50; 0 disables)."
                if (n !in 0..50) return "Value must be between 0 and 50."
                n.toString()
            }
            ConfigDto.Configurations.LOTTERY_DAILY_ENABLED -> {
                val v = rawValue.trim().lowercase()
                if (v !in setOf("true", "false")) return "Value must be true or false."
                v
            }
            ConfigDto.Configurations.LOTTERY_DAILY_TICKET_PRICE -> {
                val n = rawValue.trim().toLongOrNull()
                    ?: return "Value must be a whole number of credits (1-1000000)."
                if (n !in 1L..1_000_000L) return "Value must be between 1 and 1,000,000 credits."
                n.toString()
            }
            ConfigDto.Configurations.LOTTERY_DAILY_SEED_PCT -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number percentage (1-100; default 5)."
                if (n !in 1..100) return "Value must be between 1 and 100."
                n.toString()
            }
            ConfigDto.Configurations.LOTTERY_DAILY_REVENUE_JACKPOT_PCT -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number percentage (0-100; default 30)."
                if (n !in 0..100) return "Value must be between 0 and 100."
                n.toString()
            }
            ConfigDto.Configurations.LOTTERY_DAILY_MODE -> {
                val v = rawValue.trim().uppercase()
                if (v !in setOf("NUMBER_MATCH", "WEIGHTED")) {
                    return "Value must be NUMBER_MATCH or WEIGHTED."
                }
                v
            }
            ConfigDto.Configurations.LOTTERY_DAILY_MIN_BUYERS -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number (1-50; default 2)."
                if (n !in 1..50) return "Value must be between 1 and 50."
                n.toString()
            }
            ConfigDto.Configurations.LOTTERY_PING_MODE -> {
                val v = rawValue.trim().uppercase()
                if (v !in setOf("OFF", "HERE", "EVERYONE")) {
                    return "Value must be OFF, HERE, or EVERYONE."
                }
                v
            }
            // Bulk-buy bonus tiers (TICKET_WEIGHTED only). Both halves
            // accept any non-negative whole number; 0 on `BUY` disables
            // that tier cleanly. Capped at a generous 100k so a typo
            // can't conjure absurd values, but big enough that a future
            // mega-server isn't blocked.
            ConfigDto.Configurations.LOTTERY_BULK_TIER1_BUY,
            ConfigDto.Configurations.LOTTERY_BULK_TIER1_BONUS,
            ConfigDto.Configurations.LOTTERY_BULK_TIER2_BUY,
            ConfigDto.Configurations.LOTTERY_BULK_TIER2_BONUS,
            ConfigDto.Configurations.LOTTERY_BULK_TIER3_BUY,
            ConfigDto.Configurations.LOTTERY_BULK_TIER3_BONUS -> {
                val n = rawValue.trim().toLongOrNull()
                    ?: return "Value must be a whole number (0 disables this tier; max 100000)."
                if (n !in 0L..100_000L) return "Value must be between 0 and 100,000."
                n.toString()
            }
            // Volume-multiplier tier thresholds — whole-number ticket
            // counts, 0 disables. Capped at 100k for the same reason as
            // the bulk thresholds.
            ConfigDto.Configurations.LOTTERY_MULT_TIER1_TOTAL,
            ConfigDto.Configurations.LOTTERY_MULT_TIER2_TOTAL,
            ConfigDto.Configurations.LOTTERY_MULT_TIER3_TOTAL -> {
                val n = rawValue.trim().toLongOrNull()
                    ?: return "Value must be a whole number (0 disables this tier; max 100000)."
                if (n !in 0L..100_000L) return "Value must be between 0 and 100,000."
                n.toString()
            }
            // Volume-multiplier basis points. 10000 = 1×, 12500 = 1.25×,
            // 50000 = 5×. We reject sub-1× values so a tier can't
            // *reduce* a player's weight; 0 is a special "treat as
            // unset" sentinel the helper coerces upward to identity.
            ConfigDto.Configurations.LOTTERY_MULT_TIER1_BP,
            ConfigDto.Configurations.LOTTERY_MULT_TIER2_BP,
            ConfigDto.Configurations.LOTTERY_MULT_TIER3_BP -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number basis-point value (10000 = 1×, max 50000 = 5×)."
                if (n != 0 && n !in 10_000..50_000) {
                    return "Value must be 0 (unset) or between 10000 and 50000."
                }
                n.toString()
            }
            // Pool-milestone ticket thresholds. 0 disables. 100k cap
            // matches the bulk thresholds.
            ConfigDto.Configurations.LOTTERY_MILESTONE1_TICKETS,
            ConfigDto.Configurations.LOTTERY_MILESTONE2_TICKETS,
            ConfigDto.Configurations.LOTTERY_MILESTONE3_TICKETS -> {
                val n = rawValue.trim().toLongOrNull()
                    ?: return "Value must be a whole number (0 disables this tier; max 100000)."
                if (n !in 0L..100_000L) return "Value must be between 0 and 100,000."
                n.toString()
            }
            // Pool-milestone % of jackpot. Capped at 50 so a single
            // milestone can never drain more than half the jackpot in
            // one purchase — protects the pool from typo-shaped
            // catastrophe.
            ConfigDto.Configurations.LOTTERY_MILESTONE1_PCT,
            ConfigDto.Configurations.LOTTERY_MILESTONE2_PCT,
            ConfigDto.Configurations.LOTTERY_MILESTONE3_PCT -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number percentage (0-50)."
                if (n !in 0..50) return "Value must be between 0 and 50."
                n.toString()
            }
            ConfigDto.Configurations.LOTTERY_CHANNEL,
            ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID,
            ConfigDto.Configurations.ACHIEVEMENT_ANNOUNCE_CHANNEL -> {
                val v = rawValue.trim()
                if (v.isEmpty()) {
                    // Empty value clears the override. LOTTERY_CHANNEL falls
                    // back to LEADERBOARD_CHANNEL → systemChannel at runtime;
                    // CASINO_MODLOG_CHANNEL_ID falls back to systemChannel.
                    // ACHIEVEMENT_ANNOUNCE_CHANNEL is DM-only when unset.
                    ""
                } else {
                    val id = v.toLongOrNull()
                        ?: return "Channel id must be numeric."
                    val channel = guild.getTextChannelById(id)
                        ?: return "No text channel with that id exists in this server."
                    channel.id
                }
            }
            // Streak reward shape — whole-number XP/credit amounts, 0
            // disables either floor (base) or scaling (per-day) cleanly.
            ConfigDto.Configurations.STREAK_BASE_REWARD_XP,
            ConfigDto.Configurations.STREAK_PER_DAY_BONUS_XP,
            ConfigDto.Configurations.STREAK_MAX_REWARD_XP,
            ConfigDto.Configurations.STREAK_BASE_REWARD_CREDIT,
            ConfigDto.Configurations.STREAK_PER_DAY_BONUS_CREDIT,
            ConfigDto.Configurations.STREAK_MAX_REWARD_CREDIT -> {
                val n = rawValue.trim().toLongOrNull()
                    ?: return "Value must be a whole number (>= 0)."
                if (n < 0L) return "Value must be zero or positive."
                n.toString()
            }
        }

        val guildIdString = guild.id
        val result = configService.upsertConfig(key.configValue, value, guildIdString)
        val path = when (result) {
            is ConfigService.UpsertResult.Created -> "create"
            is ConfigService.UpsertResult.Updated -> "update"
        }
        logger.info(
            "Config $path: guild=$guildIdString actor=$actorDiscordId key=${key.configValue} value=$value"
        )

        // Mirror SetConfigCommand's Discord-side behaviour: when activity
        // tracking is switched on, fire the first-enable notifier. The
        // ActivityTrackingNotifier listener de-duplicates via the
        // ACTIVITY_TRACKING_NOTIFIED flag, so it's safe to publish on every
        // "true" write (including true->true no-ops) — no false-positive DM
        // floods. We publish regardless of the previous value for the same
        // reason.
        if (key == ConfigDto.Configurations.ACTIVITY_TRACKING && value == "true") {
            eventPublisher.publishEvent(ActivityTrackingEnabled(guildId))
        }
        return null
    }

    fun kickMember(
        actorDiscordId: Long,
        guildId: Long,
        targetDiscordId: Long,
        reason: String?
    ): String? {
        if (!canModerate(actorDiscordId, guildId)) return "You are not allowed to moderate this server."
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        val actor = guild.getMemberById(actorDiscordId) ?: return "You are not a member of that server."
        val target = guild.getMemberById(targetDiscordId) ?: return "Target is not a member of that server."
        val bot = guild.selfMember
        val deniedReason = kickDeniedReason(actor, target, bot)
        if (deniedReason != null) return deniedReason
        return try {
            guild.kick(target).reason(reason?.takeIf { it.isNotBlank() } ?: "Kicked via web moderation UI.").complete()
            null
        } catch (e: Exception) {
            "Could not kick: ${e.message}"
        }
    }

    private fun kickDeniedReason(actor: Member, target: Member, bot: Member): String? {
        if (!actor.canInteract(target) || !actor.hasPermission(Permission.KICK_MEMBERS)) {
            return "You can't kick ${target.effectiveName}."
        }
        if (!bot.canInteract(target) || !bot.hasPermission(Permission.KICK_MEMBERS)) {
            return "Bot can't kick ${target.effectiveName}."
        }
        return null
    }

    fun banMember(
        actorDiscordId: Long,
        guildId: Long,
        targetDiscordId: Long,
        reason: String?,
        deleteDays: Int
    ): String? {
        if (!canModerate(actorDiscordId, guildId)) return "You are not allowed to moderate this server."
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        val actor = guild.getMemberById(actorDiscordId) ?: return "You are not a member of that server."
        val target = guild.getMemberById(targetDiscordId) ?: return "Target is not a member of that server."
        val bot = guild.selfMember
        val clampedDays = deleteDays.coerceIn(0, 7)
        if (!actor.canInteract(target) || !actor.hasPermission(Permission.BAN_MEMBERS)) {
            return "You can't ban ${target.effectiveName}."
        }
        if (!bot.canInteract(target) || !bot.hasPermission(Permission.BAN_MEMBERS)) {
            return "Bot can't ban ${target.effectiveName}."
        }
        return try {
            guild.ban(target, clampedDays, TimeUnit.DAYS)
                .reason(reason?.takeIf { it.isNotBlank() } ?: "Banned via web moderation UI.")
                .complete()
            null
        } catch (e: Exception) {
            "Could not ban: ${e.message}"
        }
    }

    fun unbanUser(actorDiscordId: Long, guildId: Long, targetDiscordId: Long): String? {
        if (!canModerate(actorDiscordId, guildId)) return "You are not allowed to moderate this server."
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        val actor = guild.getMemberById(actorDiscordId) ?: return "You are not a member of that server."
        val bot = guild.selfMember
        if (!actor.hasPermission(Permission.BAN_MEMBERS)) return "You need the Ban Members permission."
        if (!bot.hasPermission(Permission.BAN_MEMBERS)) return "Bot needs the Ban Members permission."
        return try {
            guild.unban(UserSnowflake.fromId(targetDiscordId))
                .reason("Unbanned via web moderation UI.")
                .complete()
            null
        } catch (e: Exception) {
            "Could not unban: ${e.message}"
        }
    }

    fun timeoutMember(
        actorDiscordId: Long,
        guildId: Long,
        targetDiscordId: Long,
        minutes: Long,
        reason: String?
    ): String? {
        if (!canModerate(actorDiscordId, guildId)) return "You are not allowed to moderate this server."
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        val actor = guild.getMemberById(actorDiscordId) ?: return "You are not a member of that server."
        val target = guild.getMemberById(targetDiscordId) ?: return "Target is not a member of that server."
        val bot = guild.selfMember
        if (minutes < 1 || minutes > 28L * 24L * 60L) {
            return "Duration must be between 1 and ${28L * 24L * 60L} minutes."
        }
        if (!actor.canInteract(target) || !actor.hasPermission(Permission.MODERATE_MEMBERS)) {
            return "You can't timeout ${target.effectiveName}."
        }
        if (!bot.canInteract(target) || !bot.hasPermission(Permission.MODERATE_MEMBERS)) {
            return "Bot can't timeout ${target.effectiveName}."
        }
        return try {
            target.timeoutFor(Duration.ofMinutes(minutes))
                .reason(reason?.takeIf { it.isNotBlank() } ?: "Timed out via web moderation UI.")
                .complete()
            null
        } catch (e: Exception) {
            "Could not timeout: ${e.message}"
        }
    }

    fun untimeoutMember(actorDiscordId: Long, guildId: Long, targetDiscordId: Long): String? {
        if (!canModerate(actorDiscordId, guildId)) return "You are not allowed to moderate this server."
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        val actor = guild.getMemberById(actorDiscordId) ?: return "You are not a member of that server."
        val target = guild.getMemberById(targetDiscordId) ?: return "Target is not a member of that server."
        val bot = guild.selfMember
        if (!actor.canInteract(target) || !actor.hasPermission(Permission.MODERATE_MEMBERS)) {
            return "You can't untimeout ${target.effectiveName}."
        }
        if (!bot.canInteract(target) || !bot.hasPermission(Permission.MODERATE_MEMBERS)) {
            return "Bot can't untimeout ${target.effectiveName}."
        }
        return try {
            target.removeTimeout().reason("Cleared via web moderation UI.").complete()
            null
        } catch (e: Exception) {
            "Could not remove timeout: ${e.message}"
        }
    }

    fun purgeMessages(
        actorDiscordId: Long,
        guildId: Long,
        channelId: Long,
        count: Int,
        filterUserId: Long?
    ): PurgeResult {
        if (!canModerate(actorDiscordId, guildId)) {
            return PurgeResult(error = "You are not allowed to moderate this server.")
        }
        if (count < 1 || count > 100) {
            return PurgeResult(error = "Count must be between 1 and 100.")
        }
        val guild = jda.getGuildById(guildId) ?: return PurgeResult(error = "Bot is not in that server.")
        val actor = guild.getMemberById(actorDiscordId)
            ?: return PurgeResult(error = "You are not in that server.")
        val bot = guild.selfMember
        val channel = guild.getTextChannelById(channelId)
            ?: return PurgeResult(error = "Text channel not found.")
        if (!actor.hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            return PurgeResult(error = "You need Manage Messages in that channel.")
        }
        if (!bot.hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            return PurgeResult(error = "Bot needs Manage Messages in that channel.")
        }

        return try {
            val history = channel.history.retrievePast(count).complete()
            val matching = if (filterUserId != null) {
                history.filter { it.author.idLong == filterUserId }
            } else history
            // Discord rejects bulk-deletes for any message older than 14
            // days, so filter those out up-front and report them as skipped
            // rather than letting the request error mid-purge.
            val cutoff = Instant.now().minus(14, ChronoUnit.DAYS)
            val recent = matching.filter { it.timeCreated.toInstant().isAfter(cutoff) }
            val skipped = matching.size - recent.size
            if (recent.isEmpty()) return PurgeResult(deleted = 0, skipped = skipped)
            if (recent.size == 1) {
                recent[0].delete().reason("Purged via web moderation UI.").complete()
            } else {
                channel.deleteMessages(recent).complete()
            }
            PurgeResult(deleted = recent.size, skipped = skipped)
        } catch (e: Exception) {
            PurgeResult(error = "Could not purge: ${e.message}")
        }
    }

    fun lockChannel(
        actorDiscordId: Long,
        guildId: Long,
        channelId: Long,
        lock: Boolean
    ): String? {
        if (!canModerate(actorDiscordId, guildId)) return "You are not allowed to moderate this server."
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        val actor = guild.getMemberById(actorDiscordId) ?: return "You are not in that server."
        val bot = guild.selfMember
        val channel = guild.getTextChannelById(channelId) ?: return "Text channel not found."
        if (!actor.hasPermission(channel, Permission.MANAGE_PERMISSIONS)) {
            return "You need Manage Permissions in that channel."
        }
        if (!bot.hasPermission(channel, Permission.MANAGE_PERMISSIONS)) {
            return "Bot needs Manage Permissions in that channel."
        }
        val everyone = guild.publicRole
        val sendSet = EnumSet.of(Permission.MESSAGE_SEND)
        val action = channel.upsertPermissionOverride(everyone)
        return try {
            if (lock) {
                action.deny(sendSet).reason("Locked via web moderation UI.").complete()
            } else {
                action.clear(sendSet).reason("Unlocked via web moderation UI.").complete()
            }
            null
        } catch (e: Exception) {
            "Could not ${if (lock) "lock" else "unlock"} channel: ${e.message}"
        }
    }

    fun setSlowmode(
        actorDiscordId: Long,
        guildId: Long,
        channelId: Long,
        seconds: Int
    ): String? {
        if (!canModerate(actorDiscordId, guildId)) return "You are not allowed to moderate this server."
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        val actor = guild.getMemberById(actorDiscordId) ?: return "You are not in that server."
        val bot = guild.selfMember
        val channel = guild.getTextChannelById(channelId) ?: return "Text channel not found."
        if (seconds < 0 || seconds > 21_600) return "Seconds must be between 0 and 21600."
        if (!actor.hasPermission(channel, Permission.MANAGE_CHANNEL)) {
            return "You need Manage Channel in that channel."
        }
        if (!bot.hasPermission(channel, Permission.MANAGE_CHANNEL)) {
            return "Bot needs Manage Channel in that channel."
        }
        return try {
            channel.manager.setSlowmode(seconds).reason("Set via web moderation UI.").complete()
            null
        } catch (e: Exception) {
            "Could not set slowmode: ${e.message}"
        }
    }

    fun moveMembers(
        actorDiscordId: Long,
        guildId: Long,
        targetChannelId: Long,
        memberIds: List<Long>
    ): MoveResult {
        if (!canModerate(actorDiscordId, guildId)) {
            return MoveResult(error = "You are not allowed to moderate this server.")
        }
        if (memberIds.isEmpty()) return MoveResult(error = "Select at least one member to move.")
        val guild = jda.getGuildById(guildId) ?: return MoveResult(error = "Bot is not in that server.")
        val actor = guild.getMemberById(actorDiscordId) ?: return MoveResult(error = "You are not in that server.")
        val bot = guild.selfMember
        val destination = guild.getVoiceChannelById(targetChannelId)
            ?: return MoveResult(error = "Target voice channel not found.")
        if (!actor.hasPermission(Permission.VOICE_MOVE_OTHERS)) {
            return MoveResult(error = "You need the Move Members permission.")
        }
        if (!bot.hasPermission(Permission.VOICE_MOVE_OTHERS)) {
            return MoveResult(error = "Bot needs the Move Members permission.")
        }

        val moved = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        memberIds.forEach { id ->
            val target = guild.getMemberById(id)
            if (target == null) {
                skipped += "Unknown member $id"
                return@forEach
            }
            if (target.voiceState?.inAudioChannel() != true) {
                skipped += "${target.effectiveName} not in a voice channel"
                return@forEach
            }
            if (!actor.canInteract(target)) {
                skipped += "Cannot move ${target.effectiveName}"
                return@forEach
            }
            try {
                guild.moveVoiceMember(target, destination).complete()
                moved += target.effectiveName
            } catch (e: Exception) {
                skipped += "${target.effectiveName}: ${e.message}"
            }
        }
        return MoveResult(moved = moved, skipped = skipped)
    }

    fun createPoll(
        actorDiscordId: Long,
        guildId: Long,
        channelId: Long,
        question: String,
        options: List<String>
    ): String? {
        if (!canModerate(actorDiscordId, guildId)) return "You are not allowed to moderate this server."
        val cleanedQuestion = question.trim()
        if (cleanedQuestion.isEmpty()) return "Question is required."
        val cleanedOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanedOptions.isEmpty()) return "Provide at least 1 option."
        if (cleanedOptions.size > MAX_POLL_OPTIONS) return "A poll can have at most $MAX_POLL_OPTIONS options."

        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        val channel = guild.getTextChannelById(channelId) ?: return "Text channel not found."
        val bot = guild.selfMember
        if (!bot.hasPermission(channel, Permission.MESSAGE_SEND, Permission.MESSAGE_ADD_REACTION)) {
            return "Bot cannot post in that channel."
        }

        val actorName = guild.getMemberById(actorDiscordId)?.effectiveName ?: "Moderator"
        val description = cleanedOptions.mapIndexed { idx, opt -> "${POLL_EMOJI[idx]} - **$opt**" }
            .joinToString("\n")
        val embed = EmbedBuilder()
            .setTitle(cleanedQuestion)
            .setAuthor(actorName)
            .setDescription(description)
            .setFooter("React with the emoji for your choice.")
            .build()

        return try {
            val message = channel.sendMessageEmbeds(embed).complete()
            cleanedOptions.indices.forEach { idx ->
                message.addReaction(Emoji.fromUnicode(POLL_EMOJI[idx])).queue()
            }
            null
        } catch (e: Exception) {
            "Could not post poll: ${e.message}"
        }
    }
}

data class GuildOverview(
    val guildId: String,
    val guildName: String,
    val members: List<ModeratedMember>,
    val voiceChannels: List<VoiceChannelInfo>,
    val textChannels: List<TextChannelInfo>,
    val categories: List<CategoryInfo> = emptyList(),
    val config: Map<String, String?>,
    val lotteryIncentives: LotteryIncentivesView = LotteryIncentivesView.empty(),
)

data class ModeratedMember(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val isOwner: Boolean,
    val isBot: Boolean,
    val musicPermission: Boolean,
    val memePermission: Boolean,
    val digPermission: Boolean,
    val superUser: Boolean,
    val socialCredit: Long
)

data class VoiceChannelInfo(
    val id: String,
    val name: String,
    val memberIds: List<String>
)

data class TextChannelInfo(
    val id: String,
    val name: String
)

/**
 * Subset of guild data needed to render the leveling moderation tab.
 * Sits alongside [GuildOverview] (which the tab also pulls — config map
 * + textChannels + categories for the channel picker / create-channel
 * widget). This struct adds the leveling-specific projections: live
 * level→role bindings with role display info, and titles with their
 * current `requiredLevel` so admins can gate purchases.
 */
data class LevelingOverview(
    val guildId: String,
    val guildName: String,
    val levelRewards: List<LevelRoleRewardView>,
    val roles: List<RoleInfo>,
    val titles: List<TitleGateView>,
)

data class LevelRoleRewardView(
    val level: Int,
    val roleId: String,
    val roleName: String,
    val roleColorHex: String?,
    /** True if the role id no longer resolves in JDA — surfaced in the
     *  UI so admins can clean up dangling rewards after a role delete. */
    val roleMissing: Boolean,
)

data class RoleInfo(
    val id: String,
    val name: String,
)

data class TitleGateView(
    val id: Long,
    val label: String,
    val colorHex: String?,
    val cost: Long,
    val requiredLevel: Int,
)

/**
 * Channel-category projection for the create-read-only-channel form.
 * Categories are Discord's left-rail groupers; an admin can drop a
 * new lottery / leaderboard channel under one for natural
 * segregation.
 */
data class CategoryInfo(
    val id: String,
    val name: String
)

data class LeaderboardRow(
    val rank: Int,
    val discordId: String,
    val name: String,
    val avatarUrl: String?,
    val socialCredit: Long,
    val title: String? = null,
    val voiceSecondsLifetime: Long = 0,
    val voiceSecondsThisMonth: Long = 0,
    val creditsEarnedThisMonth: Long = 0
) {
    val voiceLifetimeDisplay: String get() = formatDuration(voiceSecondsLifetime)
    val voiceThisMonthDisplay: String get() = formatDuration(voiceSecondsThisMonth)

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}

data class MoveResult(
    val moved: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val error: String? = null
)

data class PurgeResult(
    val deleted: Int = 0,
    val skipped: Int = 0,
    val error: String? = null
)

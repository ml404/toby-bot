package web.service

import common.events.ActivityTrackingEnabled
import common.logging.DiscordLogger
import database.dto.ConfigDto
import database.dto.UserDto
import database.service.CasinoAdminService
import database.service.ConfigService
import database.service.JackpotLotteryService
import database.service.JackpotService
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
import net.dv8tion.jda.api.entities.emoji.Emoji
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneOffset

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
    private val jackpotService: JackpotService,
    private val casinoAdminService: CasinoAdminService,
    private val jackpotLotteryService: JackpotLotteryService,
    private val eventPublisher: ApplicationEventPublisher
) {
    companion object {
        const val MAX_POLL_OPTIONS = 10
        private val POLL_EMOJI = listOf(
            "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣",
            "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"
        )
        private val logger = DiscordLogger(ModerationWebService::class.java)

        /**
         * Channel-config keys eligible for the
         * [createReadOnlyChannel] flow. Hard-coded allow-list so a
         * malformed (or maliciously crafted) request can't point the
         * `targetConfig` at, say, `JACKPOT_PAYOUT_PCT` and clobber a
         * percentage value with a channel id.
         */
        private val CHANNEL_CONFIG_ALLOWLIST: Set<ConfigDto.Configurations> = setOf(
            ConfigDto.Configurations.LOTTERY_CHANNEL,
            ConfigDto.Configurations.LEADERBOARD_CHANNEL,
        )
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

        val configByKey = ConfigDto.Configurations.entries.associate { cfg ->
            cfg.name to configService.getConfigByName(cfg.configValue, guild.id)?.value
        }

        return GuildOverview(
            guildId = guild.id,
            guildName = guild.name,
            members = rows,
            voiceChannels = voiceChannels,
            textChannels = textChannels,
            config = configByKey
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

    /** Current per-guild jackpot pool size; for the admin tab banner. */
    fun getJackpotPool(guildId: Long): Long = jackpotService.getPool(guildId)

    /**
     * Reset (zero) the per-guild jackpot pool. Returns null on success
     * with [drained] populated; non-null on permission failure.
     */
    data class ResetJackpotResult(val error: String?, val drained: Long, val newPool: Long)
    fun resetJackpotPool(actorDiscordId: Long, guildId: Long): ResetJackpotResult {
        if (!canModerate(actorDiscordId, guildId)) {
            return ResetJackpotResult("You are not allowed to moderate this server.", 0L, jackpotService.getPool(guildId))
        }
        val drained = casinoAdminService.resetJackpot(guildId)
        logger.info("Casino admin reset jackpot for guild=$guildId by actor=$actorDiscordId, drained=$drained")
        return ResetJackpotResult(null, drained, 0L)
    }

    /**
     * Debit [amount] from [sourceDiscordId] and deposit it into the
     * per-guild jackpot pool. Used to claw back exploit gains and
     * return them to the pool that funded them.
     */
    data class RefundJackpotResult(
        val error: String?,
        val drained: Long,
        val newPool: Long,
        val newSourceBalance: Long,
    )
    fun refundJackpotFromUser(
        actorDiscordId: Long,
        guildId: Long,
        sourceDiscordId: Long,
        amount: Long,
    ): RefundJackpotResult {
        if (!canModerate(actorDiscordId, guildId)) {
            return RefundJackpotResult(
                "You are not allowed to moderate this server.",
                0L, jackpotService.getPool(guildId), 0L,
            )
        }
        if (amount <= 0L) {
            return RefundJackpotResult("Amount must be positive.", 0L, jackpotService.getPool(guildId), 0L)
        }
        return when (val outcome = casinoAdminService.refundToJackpot(sourceDiscordId, guildId, amount)) {
            is CasinoAdminService.RefundOutcome.Ok -> {
                logger.info(
                    "Casino admin refund-to-jackpot guild=$guildId actor=$actorDiscordId " +
                        "source=$sourceDiscordId amount=${outcome.drained} newPool=${outcome.newPool}"
                )
                RefundJackpotResult(null, outcome.drained, outcome.newPool, outcome.newSourceBalance)
            }
            is CasinoAdminService.RefundOutcome.Insufficient ->
                RefundJackpotResult(
                    "Source user has only ${outcome.have} credits, can't refund ${outcome.needed}.",
                    0L, jackpotService.getPool(guildId), outcome.have,
                )
            is CasinoAdminService.RefundOutcome.InvalidAmount ->
                RefundJackpotResult("Amount must be positive.", 0L, jackpotService.getPool(guildId), 0L)
        }
    }

    /**
     * Force-draw the daily match-numbers lottery for [guildId]: closes
     * the current open draw (paying tier-based prizes) and opens a
     * fresh one seeded from the jackpot. Mirrors what
     * [bot.toby.scheduling.LotteryDailyJob] does at 00:00 UTC, but
     * admin-triggered for testing or when the cron missed (bot was
     * down at midnight, etc).
     */
    data class ForceDrawLotteryResult(
        val error: String?,
        val drewPrior: Boolean,
        val priorTotalPaid: Long,
        val priorRolledBack: Long,
        val priorDrawn: List<Int>,
        val priorBelowMinBuyers: Boolean = false,
        val priorBuyersHave: Int = 0,
        val priorBuyersNeed: Int = 0,
        val openedNew: Boolean,
        val newSeeded: Long,
    )

    fun forceDailyDraw(actorDiscordId: Long, guildId: Long): ForceDrawLotteryResult {
        if (!canModerate(actorDiscordId, guildId)) {
            return ForceDrawLotteryResult(
                error = "You are not allowed to moderate this server.",
                drewPrior = false, priorTotalPaid = 0L, priorRolledBack = 0L,
                priorDrawn = emptyList(), openedNew = false, newSeeded = 0L,
            )
        }
        val mode = LotteryHelper.dailyMode(configService, guildId)
        // Step 1: close open daily if present (mode-dispatched).
        // Note: this admin-only path doesn't post the channel announcement
        // (the announcer lives in the discord-bot module which the web
        // module doesn't depend on; see LotteryDailyJob for the announce
        // wiring). Force-draw is admin debug / recovery; the response
        // payload + toast are sufficient feedback.
        var drewPrior = false
        var priorTotalPaid = 0L
        var priorRolledBack = 0L
        var priorDrawn: List<Int> = emptyList()
        var priorBelowMinBuyers = false
        var priorBuyersHave = 0
        var priorBuyersNeed = 0
        if (mode == LotteryHelper.MODE_WEIGHTED) {
            when (val drawResult = jackpotLotteryService.drawLottery(guildId)) {
                is JackpotLotteryService.DrawOutcome.Ok -> {
                    drewPrior = true
                    priorTotalPaid = drawResult.totalPaid
                    priorRolledBack = (drawResult.drained - drawResult.totalPaid).coerceAtLeast(0L)
                }
                JackpotLotteryService.DrawOutcome.NoTickets -> {
                    jackpotLotteryService.cancelLottery(guildId)
                    drewPrior = true
                }
                is JackpotLotteryService.DrawOutcome.BelowMinBuyers -> {
                    jackpotLotteryService.cancelLottery(guildId)
                    drewPrior = true
                    priorBelowMinBuyers = true
                    priorBuyersHave = drawResult.have
                    priorBuyersNeed = drawResult.need
                }
                JackpotLotteryService.DrawOutcome.NoOpenLottery -> Unit
            }
        } else {
            when (val drawResult = jackpotLotteryService.drawMatchLottery(guildId)) {
                is JackpotLotteryService.DrawMatchOutcome.Ok -> {
                    drewPrior = true
                    priorTotalPaid = drawResult.totalPaid
                    priorRolledBack = drawResult.rolledBackToJackpot
                    priorDrawn = drawResult.drawnNumbers
                }
                JackpotLotteryService.DrawMatchOutcome.NoTickets -> {
                    jackpotLotteryService.cancelMatchLottery(guildId)
                    drewPrior = true
                }
                is JackpotLotteryService.DrawMatchOutcome.BelowMinBuyers -> {
                    jackpotLotteryService.cancelMatchLottery(guildId)
                    drewPrior = true
                    priorBelowMinBuyers = true
                    priorBuyersHave = drawResult.have
                    priorBuyersNeed = drawResult.need
                }
                JackpotLotteryService.DrawMatchOutcome.NoOpenLottery -> Unit
            }
        }
        // Step 2: open fresh daily (mode-dispatched).
        val ticketPrice = LotteryHelper.dailyTicketPrice(configService, guildId)
        val seedPct = LotteryHelper.dailySeedPct(configService, guildId)
        val open = if (mode == LotteryHelper.MODE_WEIGHTED) {
            jackpotLotteryService.openLottery(
                guildId = guildId,
                ticketPrice = ticketPrice,
                durationHours = 24L,
                winnerCount = LotteryHelper.WEIGHTED_DAILY_WINNER_COUNT,
                drainPct = (seedPct.toDouble() / 100.0).coerceIn(0.0, 1.0),
            )
        } else {
            jackpotLotteryService.openMatchLottery(
                guildId = guildId,
                ticketPrice = ticketPrice,
                seedPct = seedPct,
                durationHours = 24L,
            )
        }
        return when (open) {
            is JackpotLotteryService.OpenOutcome.Ok -> {
                logger.info(
                    "Casino admin force-drew daily lottery: guild=$guildId actor=$actorDiscordId " +
                        "drewPrior=$drewPrior priorTotalPaid=$priorTotalPaid newSeeded=${open.seeded}"
                )
                ForceDrawLotteryResult(
                    error = null,
                    drewPrior = drewPrior, priorTotalPaid = priorTotalPaid,
                    priorRolledBack = priorRolledBack, priorDrawn = priorDrawn,
                    priorBelowMinBuyers = priorBelowMinBuyers,
                    priorBuyersHave = priorBuyersHave,
                    priorBuyersNeed = priorBuyersNeed,
                    openedNew = true, newSeeded = open.seeded,
                )
            }
            JackpotLotteryService.OpenOutcome.AlreadyOpen ->
                ForceDrawLotteryResult(
                    error = "A daily lottery is already open — close it first.",
                    drewPrior = drewPrior, priorTotalPaid = priorTotalPaid,
                    priorRolledBack = priorRolledBack, priorDrawn = priorDrawn,
                    priorBelowMinBuyers = priorBelowMinBuyers,
                    priorBuyersHave = priorBuyersHave,
                    priorBuyersNeed = priorBuyersNeed,
                    openedNew = false, newSeeded = 0L,
                )
            JackpotLotteryService.OpenOutcome.EmptyPool ->
                ForceDrawLotteryResult(
                    error = null,
                    drewPrior = drewPrior, priorTotalPaid = priorTotalPaid,
                    priorRolledBack = priorRolledBack, priorDrawn = priorDrawn,
                    priorBelowMinBuyers = priorBelowMinBuyers,
                    priorBuyersHave = priorBuyersHave,
                    priorBuyersNeed = priorBuyersNeed,
                    openedNew = false, newSeeded = 0L,
                )
            is JackpotLotteryService.OpenOutcome.InvalidParams ->
                ForceDrawLotteryResult(
                    error = open.reason,
                    drewPrior = drewPrior, priorTotalPaid = priorTotalPaid,
                    priorRolledBack = priorRolledBack, priorDrawn = priorDrawn,
                    priorBelowMinBuyers = priorBelowMinBuyers,
                    priorBuyersHave = priorBuyersHave,
                    priorBuyersNeed = priorBuyersNeed,
                    openedNew = false, newSeeded = 0L,
                )
        }
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
    ): CreateChannelOutcome {
        if (!canModerate(actorDiscordId, guildId)) {
            return CreateChannelOutcome.Error("Not allowed.")
        }
        val targetConfig = runCatching {
            ConfigDto.Configurations.valueOf(targetConfigName.trim().uppercase())
        }.getOrNull()
        if (targetConfig == null || targetConfig !in CHANNEL_CONFIG_ALLOWLIST) {
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
        val name = sanitizeChannelName(rawName)
            ?: return CreateChannelOutcome.Error(
                "Channel name must be 1-90 chars (a-z, 0-9, dashes)."
            )

        val everyone = guild.publicRole
        val newChannel = try {
            guild.createTextChannel(name)
                .addRolePermissionOverride(
                    everyone.idLong,
                    listOf(Permission.VIEW_CHANNEL),                                   // allow
                    listOf(Permission.MESSAGE_SEND, Permission.MESSAGE_ADD_REACTION),  // deny
                )
                .addMemberPermissionOverride(
                    bot.idLong,
                    listOf(
                        Permission.VIEW_CHANNEL,
                        Permission.MESSAGE_SEND,
                        Permission.MESSAGE_EMBED_LINKS,
                    ),
                    emptyList(),
                )
                .complete()
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
            "Created read-only channel guild=$guildId actor=$actorDiscordId " +
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
            ConfigDto.Configurations.DUEL_MAX_STAKE -> {
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
            ConfigDto.Configurations.JACKPOT_PAYOUT_PCT -> {
                val n = rawValue.trim().toIntOrNull()
                    ?: return "Value must be a whole number percentage (1-100; default 100)."
                if (n !in 1..100) return "Value must be between 1 and 100."
                n.toString()
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
            ConfigDto.Configurations.COINFLIP_BOT_EDGE_MAX_PCT,
            ConfigDto.Configurations.DICE_BOT_EDGE_MAX_PCT,
            ConfigDto.Configurations.SLOTS_BOT_EDGE_MAX_PCT -> {
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
            ConfigDto.Configurations.LOTTERY_CHANNEL -> {
                val v = rawValue.trim()
                if (v.isEmpty()) {
                    // Empty value clears the override and falls back to
                    // LEADERBOARD_CHANNEL → systemChannel at runtime.
                    ""
                } else {
                    val id = v.toLongOrNull()
                        ?: return "Channel id must be numeric."
                    val channel = guild.getTextChannelById(id)
                        ?: return "No text channel with that id exists in this server."
                    channel.id
                }
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

    fun muteVoiceChannel(
        actorDiscordId: Long,
        guildId: Long,
        channelId: Long,
        mute: Boolean
    ): MuteResult {
        if (!canModerate(actorDiscordId, guildId)) {
            return MuteResult(error = "You are not allowed to moderate this server.")
        }
        val guild = jda.getGuildById(guildId) ?: return MuteResult(error = "Bot is not in that server.")
        val actor = guild.getMemberById(actorDiscordId) ?: return MuteResult(error = "You are not in that server.")
        val bot = guild.selfMember
        val channel = guild.getVoiceChannelById(channelId)
            ?: return MuteResult(error = "Voice channel not found.")
        if (!actor.hasPermission(Permission.VOICE_MUTE_OTHERS)) {
            return MuteResult(error = "You need the Mute Members permission.")
        }
        if (!bot.hasPermission(Permission.VOICE_MUTE_OTHERS)) {
            return MuteResult(error = "Bot needs the Mute Members permission.")
        }

        val action = if (mute) "Muted" else "Unmuted"
        val changed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        channel.members.filter { !it.user.isBot }.forEach { target ->
            if (!actor.canInteract(target)) {
                skipped += target.effectiveName
                return@forEach
            }
            try {
                guild.mute(target, mute).reason(action).complete()
                changed += target.effectiveName
            } catch (e: Exception) {
                skipped += "${target.effectiveName}: ${e.message}"
            }
        }
        return MuteResult(changed = changed, skipped = skipped)
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
        if (cleanedOptions.size < 2) return "Provide at least 2 options."
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
    val config: Map<String, String?>
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

data class MuteResult(
    val changed: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val error: String? = null
)

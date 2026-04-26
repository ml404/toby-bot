package web.service

import common.events.ActivityTrackingEnabled
import common.logging.DiscordLogger
import database.dto.ConfigDto
import database.dto.UserDto
import database.service.ConfigService
import database.service.MonthlyCreditSnapshotService
import database.service.TitleService
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
    private val eventPublisher: ApplicationEventPublisher
) {
    companion object {
        const val MAX_POLL_OPTIONS = 10
        private val POLL_EMOJI = listOf(
            "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣",
            "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"
        )
        private val logger = DiscordLogger(ModerationWebService::class.java)
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
                val creditsDelta = if (baseline == null) 0L else current - baseline
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
        }

        val guildIdString = guild.id
        val newDto = ConfigDto(key.configValue, value, guildIdString)
        val existing = configService.getConfigByName(key.configValue, guildIdString)
        val path = if (existing != null && existing.guildId == guildIdString) {
            configService.updateConfig(newDto)
            "update"
        } else {
            configService.createNewConfig(newDto)
            "create"
        }
        logger.info(
            "Config $path: guild=$guildIdString actor=$actorDiscordId key=${key.configValue} " +
                "value=$value existing.guildId=${existing?.guildId}"
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

package web.service

import database.dto.ConfigDto
import database.dto.UserDto
import database.service.ConfigService
import database.service.UserService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.emoji.Emoji
import org.springframework.stereotype.Service

@Service
class ModerationWebService(
    private val jda: JDA,
    private val userService: UserService,
    private val configService: ConfigService,
    private val introWebService: IntroWebService
) {
    companion object {
        const val MAX_POLL_OPTIONS = 10
        private val POLL_EMOJI = listOf(
            "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣",
            "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"
        )
    }

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
                superUser = dto?.superUser ?: false
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
        return userService.listGuildUsers(guildId)
            .filterNotNull()
            .sortedByDescending { it.socialCredit ?: 0L }
            .mapIndexed { index, dto ->
                val member = guild.getMemberById(dto.discordId)
                LeaderboardRow(
                    rank = index + 1,
                    discordId = dto.discordId.toString(),
                    name = member?.effectiveName ?: "Unknown",
                    avatarUrl = member?.effectiveAvatarUrl,
                    socialCredit = dto.socialCredit ?: 0L
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
        }

        val guildIdString = guild.id
        val newDto = ConfigDto(key.configValue, value, guildIdString)
        val existing = configService.getConfigByName(key.configValue, guildIdString)
        if (existing != null && existing.guildId == guildIdString) {
            configService.updateConfig(newDto)
        } else {
            configService.createNewConfig(newDto)
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
    val superUser: Boolean
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
    val socialCredit: Long
)

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

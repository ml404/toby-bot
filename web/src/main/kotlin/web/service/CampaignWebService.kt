package web.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import database.dto.CampaignDto
import database.dto.CampaignPlayerDto
import database.dto.CampaignPlayerId
import database.dto.UserDto
import database.service.CampaignPlayerService
import database.service.CampaignService
import database.service.CharacterSheetService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service

data class CampaignDetail(
    val campaign: CampaignDto,
    val players: List<PlayerInfo>,
    val dmName: String,
    val isCurrentUserPlayer: Boolean = false,
    val currentUserCharacterId: Long? = null
) {
    fun isDm(discordId: Long): Boolean = campaign.dmDiscordId == discordId
}

data class PlayerInfo(
    val discordId: Long,
    val displayName: String,
    val characterId: Long?,
    val alive: Boolean,
    val characterName: String? = null,
    val characterRace: String? = null,
    val characterClasses: String? = null,
    val characterLevel: Int? = null
) {
    val characterLink: String?
        get() = characterId?.let { "https://www.dndbeyond.com/characters/$it" }
}

data class GuildCampaignInfo(
    val id: String,
    val name: String,
    val iconHash: String?,
    val activeCampaign: CampaignDto?
) {
    val iconUrl: String?
        get() = iconHash?.let { "https://cdn.discordapp.com/icons/$id/$it.png?size=64" }
}

enum class JoinResult { JOINED, NO_ACTIVE_CAMPAIGN, ALREADY_JOINED, IS_DM }
enum class LeaveResult { LEFT, NO_ACTIVE_CAMPAIGN, NOT_A_PLAYER }
enum class SetCharacterResult { UPDATED, CLEARED, INVALID }

@Service
class CampaignWebService(
    private val campaignService: CampaignService,
    private val campaignPlayerService: CampaignPlayerService,
    private val introWebService: IntroWebService,
    private val userService: UserService,
    private val characterSheetService: CharacterSheetService,
    private val jda: JDA
) {

    private val objectMapper = ObjectMapper().apply {
        configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    fun getMutualGuildsWithCampaigns(accessToken: String): List<GuildCampaignInfo> {
        val mutualGuilds = introWebService.getMutualGuilds(accessToken)
        return mutualGuilds.map { guild ->
            val guildId = guild.id.toLongOrNull()
            val campaign = guildId?.let { campaignService.getActiveCampaignForGuild(it) }
            GuildCampaignInfo(
                id = guild.id,
                name = guild.name,
                iconHash = guild.iconHash,
                activeCampaign = campaign
            )
        }
    }

    fun getGuildName(guildId: Long): String? = jda.getGuildById(guildId)?.name

    fun getCampaignDetail(guildId: Long, requestingDiscordId: Long): CampaignDetail? {
        val campaign = campaignService.getActiveCampaignForGuild(guildId) ?: return null
        val guild = jda.getGuildById(guildId)

        val dmName = guild?.getMemberById(campaign.dmDiscordId)?.effectiveName
            ?: "Unknown (ID: ${campaign.dmDiscordId})"

        val players = campaignPlayerService.getPlayersForCampaign(campaign.id).map { player ->
            val memberName = guild?.getMemberById(player.id.playerDiscordId)?.effectiveName
                ?: "Unknown (ID: ${player.id.playerDiscordId})"
            val summary = player.characterId?.let(::loadCharacterSummary)
            PlayerInfo(
                discordId = player.id.playerDiscordId,
                displayName = memberName,
                characterId = player.characterId,
                alive = player.alive,
                characterName = summary?.name,
                characterRace = summary?.raceName(),
                characterClasses = summary?.classesString(),
                characterLevel = summary?.totalLevel()
            )
        }

        val isCurrentUserPlayer = players.any { it.discordId == requestingDiscordId }
        val currentUserCharacterId = userService.getUserById(requestingDiscordId, guildId)?.dndBeyondCharacterId

        return CampaignDetail(
            campaign = campaign,
            players = players,
            dmName = dmName,
            isCurrentUserPlayer = isCurrentUserPlayer,
            currentUserCharacterId = currentUserCharacterId
        )
    }

    fun createCampaign(guildId: Long, dmDiscordId: Long, name: String): CampaignDto? {
        if (campaignService.getActiveCampaignForGuild(guildId) != null) return null
        return campaignService.createCampaign(
            CampaignDto(guildId = guildId, channelId = 0L, dmDiscordId = dmDiscordId, name = name)
        )
    }

    fun joinCampaign(guildId: Long, discordId: Long): JoinResult {
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return JoinResult.NO_ACTIVE_CAMPAIGN
        if (campaign.dmDiscordId == discordId) return JoinResult.IS_DM

        val playerId = CampaignPlayerId(campaign.id, discordId)
        if (campaignPlayerService.getPlayer(playerId) != null) return JoinResult.ALREADY_JOINED

        val characterId = userService.getUserById(discordId, guildId)?.dndBeyondCharacterId
        campaignPlayerService.addPlayer(
            CampaignPlayerDto(
                id = playerId,
                guildId = guildId,
                characterId = characterId,
                alive = true
            )
        )
        return JoinResult.JOINED
    }

    fun leaveCampaign(guildId: Long, discordId: Long): LeaveResult {
        val campaign = campaignService.getActiveCampaignForGuild(guildId)
            ?: return LeaveResult.NO_ACTIVE_CAMPAIGN
        val playerId = CampaignPlayerId(campaign.id, discordId)
        if (campaignPlayerService.getPlayer(playerId) == null) return LeaveResult.NOT_A_PLAYER
        campaignPlayerService.removePlayer(playerId)
        return LeaveResult.LEFT
    }

    fun setLinkedCharacter(guildId: Long, discordId: Long, input: String): SetCharacterResult {
        val trimmed = input.trim()
        val user = userService.getUserById(discordId, guildId)
            ?: userService.createNewUser(UserDto(discordId = discordId, guildId = guildId))

        if (trimmed.isEmpty()) {
            user.dndBeyondCharacterId = null
            userService.updateUser(user)
            return SetCharacterResult.CLEARED
        }

        val id = extractCharacterId(trimmed) ?: return SetCharacterResult.INVALID
        user.dndBeyondCharacterId = id
        userService.updateUser(user)
        return SetCharacterResult.UPDATED
    }

    private fun loadCharacterSummary(characterId: Long): CharacterSummary? {
        val json = characterSheetService.getSheet(characterId) ?: return null
        return runCatching { objectMapper.readValue(json, CharacterSummary::class.java) }.getOrNull()
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CharacterSummary(
        val name: String? = null,
        val race: Race? = null,
        val classes: List<ClassEntry>? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Race(val fullName: String? = null, val baseName: String? = null)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class ClassEntry(
            val level: Int? = null,
            val definition: Definition? = null,
            val subclassDefinition: Definition? = null
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Definition(val name: String? = null)

        fun raceName(): String? = race?.fullName ?: race?.baseName
        fun totalLevel(): Int? = classes?.sumOf { it.level ?: 0 }?.takeIf { it > 0 }
        fun classesString(): String? = classes?.joinToString(", ") { entry ->
            val sub = entry.subclassDefinition?.name
            val base = entry.definition?.name ?: "?"
            if (sub != null) "$base ($sub)" else base
        }?.takeIf { it.isNotBlank() }
    }

    companion object {
        private val CHARACTER_ID_REGEX = Regex("(\\d+)")

        internal fun extractCharacterId(input: String): Long? =
            CHARACTER_ID_REGEX.findAll(input).lastOrNull()?.value?.toLongOrNull()
    }
}

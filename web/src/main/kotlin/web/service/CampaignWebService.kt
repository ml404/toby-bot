package web.service

import database.dto.CampaignDto
import database.dto.CampaignPlayerDto
import database.service.CampaignPlayerService
import database.service.CampaignService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service

data class CampaignDetail(
    val campaign: CampaignDto,
    val players: List<PlayerInfo>,
    val dmName: String
) {
    fun isDm(discordId: Long): Boolean = campaign.dmDiscordId == discordId
}

data class PlayerInfo(
    val discordId: Long,
    val displayName: String,
    val characterId: Long?,
    val alive: Boolean
)

data class GuildCampaignInfo(
    val id: String,
    val name: String,
    val iconHash: String?,
    val activeCampaign: CampaignDto?
) {
    val iconUrl: String?
        get() = iconHash?.let { "https://cdn.discordapp.com/icons/$id/$it.png?size=64" }
}

@Service
class CampaignWebService(
    private val campaignService: CampaignService,
    private val campaignPlayerService: CampaignPlayerService,
    private val introWebService: IntroWebService,
    private val jda: JDA
) {

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
            PlayerInfo(
                discordId = player.id.playerDiscordId,
                displayName = memberName,
                characterId = player.characterId,
                alive = player.alive
            )
        }

        return CampaignDetail(campaign, players, dmName)
    }
}

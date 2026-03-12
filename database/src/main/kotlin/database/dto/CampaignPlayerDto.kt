package database.dto

import jakarta.persistence.*
import java.io.Serializable

@Embeddable
data class CampaignPlayerId(
    @Column(name = "campaign_id")
    val campaignId: Long = 0,

    @Column(name = "player_discord_id")
    val playerDiscordId: Long = 0
) : Serializable

@NamedQueries(
    NamedQuery(
        name = "CampaignPlayerDto.getByCampaign",
        query = "select p from CampaignPlayerDto p where p.id.campaignId = :campaignId"
    ),
    NamedQuery(
        name = "CampaignPlayerDto.deleteById",
        query = "delete from CampaignPlayerDto p where p.id.campaignId = :campaignId and p.id.playerDiscordId = :playerDiscordId"
    )
)
@Entity
@Table(name = "dnd_campaign_player", schema = "public")
class CampaignPlayerDto(
    @EmbeddedId
    var id: CampaignPlayerId = CampaignPlayerId(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", insertable = false, updatable = false)
    var campaign: CampaignDto? = null,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "character_id")
    var characterId: Long? = null,

    @Column(name = "alive", nullable = false)
    var alive: Boolean = true
) : Serializable {

    override fun toString(): String =
        "CampaignPlayerDto(campaignId=${id.campaignId}, playerDiscordId=${id.playerDiscordId}, alive=$alive)"
}

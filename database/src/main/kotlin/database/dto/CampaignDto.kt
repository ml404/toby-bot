package database.dto

import jakarta.persistence.*
import java.io.Serializable

@NamedQueries(
    NamedQuery(
        name = "CampaignDto.getByGuildActive",
        query = "select c from CampaignDto c where c.guildId = :guildId and c.active = true"
    ),
    NamedQuery(
        name = "CampaignDto.getById",
        query = "select c from CampaignDto c where c.id = :id"
    ),
    NamedQuery(
        name = "CampaignDto.deactivateByGuild",
        query = "update CampaignDto c set c.active = false where c.guildId = :guildId and c.active = true"
    )
)
@Entity
@Table(name = "dnd_campaign", schema = "public")
class CampaignDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "channel_id", nullable = false)
    var channelId: Long = 0,

    @Column(name = "dm_discord_id", nullable = false)
    var dmDiscordId: Long = 0,

    @Column(name = "name", nullable = false, length = 100)
    var name: String = "",

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "state", columnDefinition = "TEXT")
    var state: String? = null,

    @OneToMany(mappedBy = "campaign", fetch = FetchType.EAGER, cascade = [CascadeType.ALL], orphanRemoval = true)
    var players: MutableList<CampaignPlayerDto> = mutableListOf()
) : Serializable {

    override fun toString(): String =
        "CampaignDto(id=$id, guildId=$guildId, name=$name, active=$active)"
}

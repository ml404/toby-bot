package database.dto

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime

@NamedQueries(
    NamedQuery(
        name = "CampaignEventDto.getByCampaignDesc",
        query = "select e from CampaignEventDto e where e.campaignId = :campaignId order by e.id desc"
    ),
    NamedQuery(
        name = "CampaignEventDto.getSinceId",
        query = "select e from CampaignEventDto e where e.campaignId = :campaignId and e.id > :sinceId order by e.id asc"
    ),
    NamedQuery(
        name = "CampaignEventDto.getById",
        query = "select e from CampaignEventDto e where e.id = :id"
    )
)
@Entity
@Table(name = "dnd_campaign_event", schema = "public")
class CampaignEventDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "campaign_id", nullable = false)
    var campaignId: Long = 0,

    @Column(name = "event_type", nullable = false, length = 40)
    var eventType: String = "",

    @Column(name = "actor_discord_id")
    var actorDiscordId: Long? = null,

    @Column(name = "actor_name", length = 100)
    var actorName: String? = null,

    @Column(name = "ref_event_id")
    var refEventId: Long? = null,

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    var payload: String = "{}",

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
) : Serializable {

    override fun toString(): String =
        "CampaignEventDto(id=$id, campaignId=$campaignId, type=$eventType, createdAt=$createdAt)"
}

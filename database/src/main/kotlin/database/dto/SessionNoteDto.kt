package database.dto

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime

@NamedQueries(
    NamedQuery(
        name = "SessionNoteDto.getByCampaign",
        query = "select n from SessionNoteDto n where n.campaignId = :campaignId order by n.createdAt desc"
    ),
    NamedQuery(
        name = "SessionNoteDto.getById",
        query = "select n from SessionNoteDto n where n.id = :id"
    ),
    NamedQuery(
        name = "SessionNoteDto.deleteById",
        query = "delete from SessionNoteDto n where n.id = :id"
    )
)
@Entity
@Table(name = "dnd_campaign_session_note", schema = "public")
class SessionNoteDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "campaign_id", nullable = false)
    var campaignId: Long = 0,

    @Column(name = "author_discord_id", nullable = false)
    var authorDiscordId: Long = 0,

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    var body: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
) : Serializable {

    override fun toString(): String =
        "SessionNoteDto(id=$id, campaignId=$campaignId, authorDiscordId=$authorDiscordId, createdAt=$createdAt)"
}

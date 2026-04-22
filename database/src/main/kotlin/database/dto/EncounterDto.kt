package database.dto

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime

@NamedQueries(
    NamedQuery(
        name = "EncounterDto.getByDm",
        query = "select e from EncounterDto e where e.dmDiscordId = :dmDiscordId order by e.name asc"
    ),
    NamedQuery(
        name = "EncounterDto.getById",
        query = "select e from EncounterDto e where e.id = :id"
    ),
    NamedQuery(
        name = "EncounterDto.deleteById",
        query = "delete from EncounterDto e where e.id = :id"
    )
)
@Entity
@Table(name = "dnd_encounter", schema = "public")
class EncounterDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "dm_discord_id", nullable = false)
    var dmDiscordId: Long = 0,

    @Column(name = "name", nullable = false, length = 100)
    var name: String = "",

    @Column(name = "notes", length = 500)
    var notes: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) : Serializable {

    override fun toString(): String =
        "EncounterDto(id=$id, dmDiscordId=$dmDiscordId, name=$name)"
}

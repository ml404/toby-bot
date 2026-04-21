package database.dto

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime

@NamedQueries(
    NamedQuery(
        name = "MonsterTemplateDto.getByDm",
        query = "select t from MonsterTemplateDto t where t.dmDiscordId = :dmDiscordId order by t.name asc"
    ),
    NamedQuery(
        name = "MonsterTemplateDto.getById",
        query = "select t from MonsterTemplateDto t where t.id = :id"
    ),
    NamedQuery(
        name = "MonsterTemplateDto.deleteById",
        query = "delete from MonsterTemplateDto t where t.id = :id"
    )
)
@Entity
@Table(name = "dnd_monster_template", schema = "public")
class MonsterTemplateDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "dm_discord_id", nullable = false)
    var dmDiscordId: Long = 0,

    @Column(name = "name", nullable = false, length = 100)
    var name: String = "",

    @Column(name = "initiative_modifier", nullable = false)
    var initiativeModifier: Int = 0,

    @Column(name = "hp_expression", length = 32)
    var hpExpression: String? = null,

    @Column(name = "ac")
    var ac: Int? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) : Serializable {

    override fun toString(): String =
        "MonsterTemplateDto(id=$id, dmDiscordId=$dmDiscordId, name=$name, initiativeModifier=$initiativeModifier)"
}

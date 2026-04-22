package database.dto

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime

@NamedQueries(
    NamedQuery(
        name = "EncounterEntryDto.listByEncounter",
        query = "select e from EncounterEntryDto e where e.encounterId = :encounterId order by e.sortOrder asc, e.id asc"
    ),
    NamedQuery(
        name = "EncounterEntryDto.countByEncounter",
        query = "select count(e) from EncounterEntryDto e where e.encounterId = :encounterId"
    ),
    NamedQuery(
        name = "EncounterEntryDto.maxSortOrder",
        query = "select coalesce(max(e.sortOrder), -1) from EncounterEntryDto e where e.encounterId = :encounterId"
    ),
    NamedQuery(
        name = "EncounterEntryDto.getById",
        query = "select e from EncounterEntryDto e where e.id = :id"
    ),
    NamedQuery(
        name = "EncounterEntryDto.deleteById",
        query = "delete from EncounterEntryDto e where e.id = :id"
    ),
    NamedQuery(
        name = "EncounterEntryDto.deleteByEncounter",
        query = "delete from EncounterEntryDto e where e.encounterId = :encounterId"
    )
)
@Entity
@Table(name = "dnd_encounter_entry", schema = "public")
class EncounterEntryDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "encounter_id", nullable = false)
    var encounterId: Long = 0,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "monster_template_id")
    var monsterTemplateId: Long? = null,

    @Column(name = "quantity", nullable = false)
    var quantity: Int = 1,

    @Column(name = "adhoc_name", length = 100)
    var adhocName: String? = null,

    @Column(name = "adhoc_initiative_modifier", nullable = false)
    var adhocInitiativeModifier: Int = 0,

    @Column(name = "adhoc_hp_expression", length = 32)
    var adhocHpExpression: String? = null,

    @Column(name = "adhoc_ac")
    var adhocAc: Int? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
) : Serializable {

    override fun toString(): String =
        "EncounterEntryDto(id=$id, encounterId=$encounterId, sortOrder=$sortOrder, " +
            "monsterTemplateId=$monsterTemplateId, quantity=$quantity, adhocName=$adhocName)"
}

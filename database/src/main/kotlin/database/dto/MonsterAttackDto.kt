package database.dto

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime

@NamedQueries(
    NamedQuery(
        name = "MonsterAttackDto.listByTemplate",
        query = "select a from MonsterAttackDto a where a.monsterTemplateId = :templateId order by a.id asc"
    ),
    NamedQuery(
        name = "MonsterAttackDto.countByTemplate",
        query = "select count(a) from MonsterAttackDto a where a.monsterTemplateId = :templateId"
    ),
    NamedQuery(
        name = "MonsterAttackDto.getById",
        query = "select a from MonsterAttackDto a where a.id = :id"
    ),
    NamedQuery(
        name = "MonsterAttackDto.deleteById",
        query = "delete from MonsterAttackDto a where a.id = :id"
    )
)
@Entity
@Table(name = "dnd_monster_attack", schema = "public")
class MonsterAttackDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "monster_template_id", nullable = false)
    var monsterTemplateId: Long = 0,

    @Column(name = "name", nullable = false, length = 60)
    var name: String = "",

    @Column(name = "to_hit_modifier", nullable = false)
    var toHitModifier: Int = 0,

    @Column(name = "damage_expression", nullable = false, length = 32)
    var damageExpression: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
) : Serializable {

    override fun toString(): String =
        "MonsterAttackDto(id=$id, monsterTemplateId=$monsterTemplateId, name=$name, toHit=$toHitModifier, dmg=$damageExpression)"
}

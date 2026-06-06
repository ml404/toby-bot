package database.dto.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

/**
 * A cube card list a user saved on the Magic toolkit web tool (`/magic`), keyed by
 * (discord_id, name) so a name is unique per account and re-saving the same
 * name upserts. `cards` is the raw pasted text (one card per line); the web
 * layer re-parses it on load so the format can evolve without a migration.
 *
 * Composite key via two `@Id` fields + `Serializable`, matching [UserDto].
 */
@NamedQueries(
    NamedQuery(
        name = "CubeListDto.listForUser",
        query = "select c from CubeListDto c where c.discordId = :discordId order by c.name"
    ),
    NamedQuery(
        name = "CubeListDto.get",
        query = "select c from CubeListDto c where c.discordId = :discordId and c.name = :name"
    ),
    NamedQuery(
        name = "CubeListDto.deleteByUserAndName",
        query = "delete from CubeListDto c where c.discordId = :discordId and c.name = :name"
    )
)
@Entity
@Table(name = "cube_list", schema = "public")
@Transactional
class CubeListDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "name")
    var name: String = "",

    @Column(name = "cards", nullable = false)
    var cards: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) : Serializable

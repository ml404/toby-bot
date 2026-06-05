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
 * An immutable, publicly-shareable snapshot of a cube card list, addressed
 * by a short random [token] (`/cube/c/<token>`). Created by a logged-in
 * user; readable by anyone with the link. `cards` is the raw list text so
 * the web layer re-parses it on open (same format as [CubeListDto]).
 */
@NamedQueries(
    NamedQuery(
        name = "SharedCubeDto.get",
        query = "select s from SharedCubeDto s where s.token = :token"
    )
)
@Entity
@Table(name = "shared_cube", schema = "public")
@Transactional
class SharedCubeDto(
    @Id
    @Column(name = "token")
    var token: String = "",

    @Column(name = "discord_id", nullable = false)
    var discordId: Long = 0,

    @Column(name = "name", nullable = false)
    var name: String = "",

    @Column(name = "cards", nullable = false)
    var cards: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) : Serializable

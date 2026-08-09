package database.dto.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

/** What a shared snapshot holds, and therefore how opening it renders. */
enum class SharedCubeKind {
    /** A cube card list, opened at `/magic/c/<token>`. */
    CUBE,

    /** A dealt set of packs, opened at `/magic/d/<token>`. */
    DEAL,
}

/**
 * An immutable, publicly-shareable snapshot of card text, addressed by a
 * short random [token]. Created by a logged-in user; readable by anyone with
 * the link. `cards` is the raw text so the web layer re-parses it on open —
 * a card list (same format as [CubeListDto]) for [SharedCubeKind.CUBE], a
 * pack list for [SharedCubeKind.DEAL].
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

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    var kind: SharedCubeKind = SharedCubeKind.CUBE,
) : Serializable

package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "UserOwnedTitleDto.getByUser",
        query = "select o from UserOwnedTitleDto o where o.discordId = :discordId"
    ),
    NamedQuery(
        name = "UserOwnedTitleDto.exists",
        query = "select count(o) from UserOwnedTitleDto o where o.discordId = :discordId and o.titleId = :titleId"
    )
)
@Entity
@Table(name = "user_owned_title", schema = "public")
@IdClass(UserOwnedTitleId::class)
@Transactional
class UserOwnedTitleDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "title_id")
    var titleId: Long = 0,

    @Column(name = "bought_at", nullable = false)
    var boughtAt: Instant = Instant.now()
) : Serializable

data class UserOwnedTitleId(
    var discordId: Long = 0,
    var titleId: Long = 0
) : Serializable

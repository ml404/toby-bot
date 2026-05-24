package database.dto.economy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "TobyCoinJackpotWinnerDto.get",
        query = "select w from TobyCoinJackpotWinnerDto w " +
                "where w.guildId = :guildId and w.discordId = :discordId"
    )
)
@Entity
@Table(name = "toby_coin_jackpot_winner", schema = "public")
@IdClass(TobyCoinJackpotWinnerId::class)
@Transactional
class TobyCoinJackpotWinnerDto(
    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Column(name = "last_won_at", nullable = false)
    var lastWonAt: Instant = Instant.EPOCH,

    @Column(name = "last_won_amount", nullable = false)
    var lastWonAmount: Long = 0
) : Serializable

data class TobyCoinJackpotWinnerId(
    var guildId: Long = 0,
    var discordId: Long = 0
) : Serializable

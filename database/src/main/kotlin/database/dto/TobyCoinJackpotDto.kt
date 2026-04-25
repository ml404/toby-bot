package database.dto

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(
        name = "TobyCoinJackpotDto.getByGuild",
        query = "select j from TobyCoinJackpotDto j where j.guildId = :guildId"
    )
)
@Entity
@Table(name = "toby_coin_jackpot", schema = "public")
@Transactional
class TobyCoinJackpotDto(
    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Column(name = "pool", nullable = false)
    var pool: Long = 0
) : Serializable

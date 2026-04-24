package database.dto

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "TobyCoinMarketDto.getByGuild",
        query = "select m from TobyCoinMarketDto m where m.guildId = :guildId"
    ),
    NamedQuery(
        name = "TobyCoinMarketDto.listAll",
        query = "select m from TobyCoinMarketDto m"
    )
)
@Entity
@Table(name = "toby_coin_market", schema = "public")
@Transactional
class TobyCoinMarketDto(
    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Column(name = "price", nullable = false)
    var price: Double = 0.0,

    @Column(name = "last_tick_at", nullable = false)
    var lastTickAt: Instant = Instant.now()
) : Serializable

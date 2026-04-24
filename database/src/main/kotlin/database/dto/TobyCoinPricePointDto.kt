package database.dto

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "TobyCoinPricePointDto.listSince",
        query = "select p from TobyCoinPricePointDto p " +
                "where p.guildId = :guildId and p.sampledAt >= :since " +
                "order by p.sampledAt asc"
    ),
    NamedQuery(
        name = "TobyCoinPricePointDto.listAll",
        query = "select p from TobyCoinPricePointDto p " +
                "where p.guildId = :guildId order by p.sampledAt asc"
    ),
    NamedQuery(
        name = "TobyCoinPricePointDto.deleteOlderThan",
        query = "delete from TobyCoinPricePointDto p where p.sampledAt < :cutoff"
    )
)
@Entity
@Table(name = "toby_coin_price_history", schema = "public")
@Transactional
class TobyCoinPricePointDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "sampled_at", nullable = false)
    var sampledAt: Instant = Instant.now(),

    @Column(name = "price", nullable = false)
    var price: Double = 0.0
) : Serializable

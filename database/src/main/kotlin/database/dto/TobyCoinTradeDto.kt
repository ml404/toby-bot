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
        name = "TobyCoinTradeDto.listSince",
        query = "select t from TobyCoinTradeDto t " +
                "where t.guildId = :guildId and t.executedAt >= :since " +
                "order by t.executedAt asc"
    ),
    NamedQuery(
        name = "TobyCoinTradeDto.deleteOlderThan",
        query = "delete from TobyCoinTradeDto t where t.executedAt < :cutoff"
    )
)
@Entity
@Table(name = "toby_coin_trade", schema = "public")
@Transactional
class TobyCoinTradeDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "discord_id", nullable = false)
    var discordId: Long = 0,

    @Column(name = "side", nullable = false)
    var side: String = "BUY",

    @Column(name = "amount", nullable = false)
    var amount: Long = 0,

    @Column(name = "price_per_coin", nullable = false)
    var pricePerCoin: Double = 0.0,

    @Column(name = "executed_at", nullable = false)
    var executedAt: Instant = Instant.now()
) : Serializable

package database.dto.economy

import common.economy.Coin
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

/**
 * Composite primary key for [TobyCoinMarketDto]: one market row per
 * (guild, coin). Property names must match the entity's `@Id` fields.
 */
data class TobyCoinMarketId(
    var guildId: Long = 0,
    var coin: String = Coin.DEFAULT.symbol,
) : Serializable

@NamedQueries(
    NamedQuery(
        name = "TobyCoinMarketDto.getByGuildAndCoin",
        query = "select m from TobyCoinMarketDto m where m.guildId = :guildId and m.coin = :coin"
    ),
    NamedQuery(
        name = "TobyCoinMarketDto.listAll",
        query = "select m from TobyCoinMarketDto m"
    )
)
@Entity
@IdClass(TobyCoinMarketId::class)
@Table(name = "toby_coin_market", schema = "public")
@Transactional
class TobyCoinMarketDto(
    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "coin", nullable = false, length = 16)
    var coin: String = Coin.DEFAULT.symbol,

    @Column(name = "price", nullable = false)
    var price: Double = 0.0,

    @Column(name = "last_tick_at", nullable = false)
    var lastTickAt: Instant = Instant.now()
) : Serializable {

    /** Typed view over the stringly-typed [coin] column. */
    var coinEnum: Coin
        get() = Coin.fromSymbol(coin)
        set(value) { coin = value.symbol }
}

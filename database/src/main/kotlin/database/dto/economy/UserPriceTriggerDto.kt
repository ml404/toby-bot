package database.dto.economy

import common.economy.Coin
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

/**
 * A user-defined TobyCoin price trigger. When the market tick crosses
 * [thresholdPrice] from the side the price was on at creation
 * ([priceAtCreation]), the scheduler auto-executes the declared trade
 * ([side] of [amount] coins) and DMs a receipt.
 *
 * One-shot: the scheduler stamps [firedAt] and flips [enabled] false
 * on fire, so a price oscillating around the threshold can't replay
 * the trade. The user re-arms by re-running `/pricealert add`.
 */
@NamedQueries(
    NamedQuery(
        name = "UserPriceTriggerDto.listEnabledByGuildAndCoin",
        query = "select t from UserPriceTriggerDto t " +
                "where t.guildId = :guildId and t.coin = :coin and t.enabled = true"
    ),
    NamedQuery(
        name = "UserPriceTriggerDto.listByUser",
        query = "select t from UserPriceTriggerDto t " +
                "where t.discordId = :discordId and t.guildId = :guildId " +
                "order by t.id"
    )
)
@Entity
@DynamicUpdate
@Table(name = "user_price_trigger", schema = "public")
@Transactional
class UserPriceTriggerDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "discord_id", nullable = false)
    var discordId: Long = 0,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "coin", nullable = false, length = 16)
    var coin: String = Coin.DEFAULT.symbol,

    @Column(name = "threshold_price", nullable = false)
    var thresholdPrice: Double = 0.0,

    @Column(name = "price_at_creation", nullable = false)
    var priceAtCreation: Double = 0.0,

    @Column(name = "side", nullable = false, length = 8)
    var side: String = Side.BUY.name,

    @Column(name = "amount", nullable = false)
    var amount: Long = 0,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,

    @Column(name = "fired_at")
    var firedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) : Serializable {

    enum class Side { BUY, SELL }

    /**
     * Typed accessor over the stringly-typed [side] column. Reading
     * throws [IllegalArgumentException] when the column holds a value
     * that doesn't map to a [Side] enum constant — callers in the
     * scheduler hot-path wrap this in a `runCatching` guard so a
     * corrupted row disables itself instead of crashing the whole
     * tick. Writes go through the enum's `name` so the column never
     * holds anything but valid identifiers.
     */
    var sideEnum: Side
        get() = Side.valueOf(side)
        set(value) { side = value.name }

    /** Typed view over the stringly-typed [coin] column. */
    var coinEnum: Coin
        get() = Coin.fromSymbol(coin)
        set(value) { coin = value.symbol }
}

package database.dto.user

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
 * A user-defined Magic card price watch. The scheduler DMs [discordId] when
 * the card [cardName]'s market price (in [currency]) crosses [threshold] in
 * [direction]. One-shot: on fire the scheduler stamps [firedAt] and flips
 * [enabled] false, so an oscillating price can't re-alert; the user re-arms by
 * adding the watch again.
 *
 * [guildId] is the guild the watch was created in (0 for web-created watches),
 * used only to resolve the user's per-guild notification opt-in.
 */
@NamedQueries(
    NamedQuery(
        name = "CardPriceWatchDto.listEnabled",
        query = "select w from CardPriceWatchDto w where w.enabled = true"
    ),
    NamedQuery(
        name = "CardPriceWatchDto.listByUser",
        query = "select w from CardPriceWatchDto w where w.discordId = :discordId order by w.id"
    )
)
@Entity
@DynamicUpdate
@Table(name = "card_price_watch", schema = "public")
@Transactional
class CardPriceWatchDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "discord_id", nullable = false)
    var discordId: Long = 0,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "card_name", nullable = false)
    var cardName: String = "",

    @Column(name = "currency", nullable = false, length = 8)
    var currency: String = "usd",

    @Column(name = "direction", nullable = false, length = 8)
    var direction: String = Direction.BELOW.name,

    @Column(name = "threshold", nullable = false)
    var threshold: Double = 0.0,

    @Column(name = "price_at_creation")
    var priceAtCreation: Double? = null,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,

    @Column(name = "fired_at")
    var firedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) : Serializable {

    /** Which way the price must move to fire the alert. */
    enum class Direction { BELOW, ABOVE }

    /** Typed accessor over the stringly-typed [direction] column. */
    var directionEnum: Direction
        get() = Direction.valueOf(direction)
        set(value) { direction = value.name }

    /** True when [price] satisfies this watch's [direction] vs [threshold]. */
    fun isTriggeredBy(price: Double): Boolean = when (directionEnum) {
        Direction.BELOW -> price <= threshold
        Direction.ABOVE -> price >= threshold
    }
}
